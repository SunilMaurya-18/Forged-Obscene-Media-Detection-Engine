"""C2PA Content Credentials (Phase 23).

Reads any C2PA manifest store an upload already carries (signer, claim generator, edit actions,
validation state) and writes a C2PA manifest into the labelled copy, next to the HMAC-signed XMP
label from ``provenance.py``. The written manifest declares the original upload as its parent
ingredient, so an upload that already had Content Credentials keeps its history.

Signing credentials: ``C2PA_SIGNING_CERT`` (PEM chain, leaf first) and ``C2PA_SIGNING_KEY`` (PEM
private key) when set. Otherwise a development CA and ES256 leaf certificate are generated in memory
once per process: C2PA readers will report the signer as untrusted, which is the honest result for
a dev key. Production needs a certificate from a CA on the C2PA trust list, held in a KMS/HSM.
"""

from __future__ import annotations

import datetime
import io
import json
import logging
import os
import threading

logger = logging.getLogger(__name__)

try:
    import c2pa

    AVAILABLE = True
except ImportError:  # pragma: no cover - the Docker image always installs c2pa-python
    c2pa = None
    AVAILABLE = False

CLAIM_GENERATOR = {"name": "forged-media-detection", "version": "0.23.0"}
ANALYSIS_ASSERTION = "com.forged-media-detection.analysis"
AI_SOURCE_TYPES = ("trainedAlgorithmicMedia", "compositeWithTrainedAlgorithmicMedia", "algorithmicMedia")
MAX_ACTIONS = 20
MAX_INGREDIENTS = 10

_lock = threading.Lock()
_credentials: tuple[bytes, bytes, str, bool] | None = None


def sniff_mime(data: bytes) -> str | None:
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    if data[:4] == b"RIFF" and data[8:12] == b"AVI ":
        return "video/x-msvideo"
    if len(data) >= 12 and data[4:8] == b"ftyp":
        brand = data[8:12]
        if brand in (b"heic", b"heix", b"mif1"):
            return "image/heic"
        if brand == b"avif":
            return "image/avif"
        return "video/quicktime" if brand == b"qt  " else "video/mp4"
    if data[:4] in (b"II*\x00", b"MM\x00*"):
        return "image/tiff"
    return None


def write_enabled() -> bool:
    return AVAILABLE and os.getenv("C2PA_WRITE", "1") != "0"


# --------------------------------------------------------------------------- read

def read(data: bytes) -> dict | None:
    """Summary of the active C2PA manifest, or None if the upload carries no Content Credentials."""
    if not AVAILABLE:
        return None
    mime = sniff_mime(data)
    if mime is None:
        return None
    reader = None
    try:
        reader = c2pa.Reader.try_create(mime, io.BytesIO(data))
        if reader is None:
            return None
        return summarize(json.loads(reader.json()))
    except Exception as exc:  # noqa: BLE001 - a corrupt manifest store is a finding, not a server error
        logger.info("C2PA manifest present but unreadable: %s", exc)
        return {"present": True, "status": "INVALID", "error": str(exc)[:240]}
    finally:
        if reader is not None:
            reader.close()


def summarize(store: dict) -> dict:
    manifests = store.get("manifests") or {}
    active_label = store.get("active_manifest")
    active = manifests.get(active_label) or {}
    state = store.get("validation_state")
    failures = [s.get("code") for s in store.get("validation_status") or [] if s.get("code")]
    untrusted = "signingCredential.untrusted" in failures
    hard_failures = [c for c in failures if c != "signingCredential.untrusted"]
    if hard_failures or (state and state.lower() == "invalid"):
        status = "INVALID"
    elif state and state.lower() == "trusted":
        status = "TRUSTED"
    else:
        status = "VALID_UNTRUSTED" if untrusted else "VALID"

    actions = []
    analysis = None
    for assertion in active.get("assertions") or []:
        label = str(assertion.get("label", ""))
        data = assertion.get("data") or {}
        if label.startswith("c2pa.actions"):
            for a in data.get("actions") or []:
                actions.append(_action(a))
        elif label == ANALYSIS_ASSERTION:
            analysis = {k: data.get(k) for k in ("provenance_id", "decision", "forgery_score", "analyzed_at")}

    signature = active.get("signature_info") or {}
    ingredients = [
        {
            "title": i.get("title"),
            "relationship": i.get("relationship"),
            "format": i.get("format"),
            "has_manifest": bool(i.get("active_manifest")),
        }
        for i in (active.get("ingredients") or [])[:MAX_INGREDIENTS]
    ]
    return {
        "present": True,
        "status": status,
        "validation_state": state,
        "failures": hard_failures[:10],
        "signer": {
            "common_name": signature.get("common_name"),
            "issuer": signature.get("issuer"),
            "algorithm": signature.get("alg"),
            "signed_at": signature.get("time"),
            "trusted": status == "TRUSTED",
        },
        "claim_generator": _claim_generator(active),
        "title": active.get("title"),
        "format": active.get("format"),
        "actions": actions[:MAX_ACTIONS],
        "ai_generated": any(
            a["digital_source_type"] and a["digital_source_type"].rsplit("/", 1)[-1] in AI_SOURCE_TYPES for a in actions
        ),
        "ingredients": ingredients,
        "manifest_count": len(manifests),
        "fmd_analysis": analysis,
    }


def _action(a: dict) -> dict:
    agent = a.get("softwareAgent")
    if isinstance(agent, dict):
        agent = " ".join(str(v) for v in (agent.get("name"), agent.get("version")) if v)
    return {
        "action": a.get("action"),
        "when": a.get("when"),
        "software_agent": agent or None,
        "digital_source_type": a.get("digitalSourceType"),
        "description": a.get("description"),
    }


def _claim_generator(active: dict) -> str | None:
    if active.get("claim_generator"):
        return str(active["claim_generator"])
    info = active.get("claim_generator_info") or []
    parts = [" ".join(str(v) for v in (g.get("name"), g.get("version")) if v) for g in info if isinstance(g, dict)]
    return ", ".join(p for p in parts if p) or None


# --------------------------------------------------------------------------- write

def signer_info() -> dict:
    """Who the written manifests are signed as (for /health)."""
    if not AVAILABLE:
        return {"available": False}
    _, _, common_name, dev = _load_credentials()
    return {"available": True, "write": write_enabled(), "signer": common_name, "dev_certificate": dev}


def sign(labelled: bytes, original: bytes, fields: dict) -> bytes:
    """Return ``labelled`` with a C2PA manifest embedded. ``original`` becomes the parent ingredient."""
    analysis = {k: v for k, v in fields.items() if v not in (None, "")}
    manifest = {
        "claim_generator_info": [CLAIM_GENERATOR],
        "title": f"fmd-labelled-{fields.get('provenance_id', 'image')}",
        "assertions": [
            {
                "label": "c2pa.actions",
                "data": {
                    "actions": [
                        {"action": "c2pa.opened", "parameters": {"ingredientIds": ["upload"]}},
                        {
                            "action": "c2pa.published",
                            "softwareAgent": CLAIM_GENERATOR,
                            "description": "Analysed for forgery and explicit content; provenance label attached",
                        },
                    ]
                },
            },
            {"label": ANALYSIS_ASSERTION, "data": analysis},
        ],
    }
    return sign_manifest(labelled, manifest, parent=original, parent_label="upload")


def sign_manifest(data: bytes, manifest: dict, parent: bytes | None = None, parent_label: str = "upload") -> bytes:
    """Embed ``manifest`` (C2PA manifest-definition JSON) into ``data`` and sign it, all in memory."""
    mime = sniff_mime(data)
    if mime not in ("image/jpeg", "image/png"):
        raise ValueError(f"C2PA writing supports JPEG and PNG, not {mime}")
    chain, key, _, _ = _load_credentials()
    settings = c2pa.Settings.from_dict({"builder": {"thumbnail": {"enabled": False}}})
    context = c2pa.Context(settings=settings)
    builder = c2pa.Builder(json.dumps(manifest), context)
    signer = c2pa.Signer.from_info(c2pa.C2paSignerInfo(b"es256", chain, key, _tsa_url()))
    try:
        if parent is not None:
            builder.add_ingredient(
                json.dumps({"title": "original upload", "relationship": "parentOf", "label": parent_label}),
                sniff_mime(parent) or mime,
                io.BytesIO(parent),
            )
        out = io.BytesIO()
        builder.sign(signer, mime, io.BytesIO(data), out)
        return out.getvalue()
    finally:
        for obj in (signer, builder, context, settings):
            close = getattr(obj, "close", None)
            if close:
                close()


def _tsa_url() -> bytes | None:
    url = os.getenv("C2PA_TSA_URL")
    return url.encode() if url else None


def _load_credentials() -> tuple[bytes, bytes, str, bool]:
    global _credentials
    with _lock:
        if _credentials is None:
            cert_path, key_path = os.getenv("C2PA_SIGNING_CERT"), os.getenv("C2PA_SIGNING_KEY")
            if cert_path and key_path:
                with open(cert_path, "rb") as fh:
                    chain = fh.read()
                with open(key_path, "rb") as fh:
                    key = fh.read()
                _credentials = (chain, key, _leaf_common_name(chain), False)
            else:
                chain, key = _dev_chain()
                _credentials = (chain, key, "Forged Media Detection (dev)", True)
                logger.warning("C2PA: signing with an in-memory DEV certificate; readers will report it untrusted")
        return _credentials


def _leaf_common_name(chain: bytes) -> str:
    from cryptography import x509
    from cryptography.x509.oid import NameOID

    leaf = x509.load_pem_x509_certificates(chain)[0]
    names = leaf.subject.get_attributes_for_oid(NameOID.COMMON_NAME)
    return str(names[0].value) if names else leaf.subject.rfc4514_string()


def _dev_chain(leaf_days: int = 90, ca_days: int = 365) -> tuple[bytes, bytes]:
    """Throwaway root CA + ES256 leaf (digitalSignature, emailProtection EKU), in memory only.

    Without a TSA timestamp, readers check the leaf against the current time, so a file signed with it
    stops validating once the leaf expires.
    """
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

    now = datetime.datetime.now(datetime.timezone.utc)

    def name(cn: str) -> x509.Name:
        return x509.Name([
            x509.NameAttribute(NameOID.COMMON_NAME, cn),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Forged Media Detection (development)"),
        ])

    def usage(sign: bool, ca: bool) -> x509.KeyUsage:
        return x509.KeyUsage(
            digital_signature=sign, content_commitment=False, key_encipherment=False, data_encipherment=False,
            key_agreement=False, key_cert_sign=ca, crl_sign=ca, encipher_only=False, decipher_only=False,
        )

    ca_key = ec.generate_private_key(ec.SECP256R1())
    ca_name = name("FMD Development Root CA")
    ca = (
        x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name).public_key(ca_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=ca_days))
        .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
        .add_extension(usage(sign=False, ca=True), critical=True)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), critical=False)
        .sign(ca_key, hashes.SHA256())
    )
    leaf_key = ec.generate_private_key(ec.SECP256R1())
    leaf = (
        x509.CertificateBuilder().subject_name(name("Forged Media Detection (dev)")).issuer_name(ca_name)
        .public_key(leaf_key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=leaf_days))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(usage(sign=True, ca=False), critical=True)
        .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.EMAIL_PROTECTION]), critical=False)
        .add_extension(x509.SubjectKeyIdentifier.from_public_key(leaf_key.public_key()), critical=False)
        .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
        .sign(ca_key, hashes.SHA256())
    )
    chain = leaf.public_bytes(serialization.Encoding.PEM) + ca.public_bytes(serialization.Encoding.PEM)
    key = leaf_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    return chain, key
