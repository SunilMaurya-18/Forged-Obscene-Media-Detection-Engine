"""Provenance manifests embedded as XMP metadata (Phase 14).

Pragmatic C2PA-style labelling: every analysed image gets a signed manifest with a unique
traceable ID, a hash of its decoded pixels, the analysis timestamp, the forgery score and the
routing decision. The manifest is written as an XMP packet (JPEG APP1 segment / PNG iTXt chunk)
without re-encoding pixel data, so the labelled copy scores and hashes identically to the
original. An HMAC signature makes edits to the manifest detectable; the pixel hash makes edits
to the image detectable.
"""

from __future__ import annotations

import hashlib
import hmac
import io
import json
import os
import re
import struct
import uuid
import zlib
from dataclasses import asdict, dataclass
from xml.etree import ElementTree
from xml.sax.saxutils import quoteattr

from PIL import Image

SPEC = "fmd.provenance/1"
NS = "https://forged-media-detection.local/ns/provenance/1.0/"
XMP_JPEG_HEADER = b"http://ns.adobe.com/xap/1.0/\x00"
XMP_PNG_KEYWORD = b"XML:com.adobe.xmp"
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"

_FIELDS = {
    "spec": "Spec",
    "provenance_id": "ProvenanceId",
    "issuer": "Issuer",
    "content_hash": "ContentHash",
    "analyzed_at": "AnalyzedAt",
    "forgery_score": "ForgeryScore",
    "explicit_score": "ExplicitScore",
    "decision": "Decision",
    "derived_from": "DerivedFrom",
}


DEFAULT_SIGNING_KEY = "dev-only-provenance-signing-key"


def _signing_key() -> bytes:
    return os.getenv("PROVENANCE_SIGNING_KEY", DEFAULT_SIGNING_KEY).encode()


def _issuer() -> str:
    return os.getenv("PROVENANCE_ISSUER", "forged-media-detection")


@dataclass(frozen=True)
class Manifest:
    provenance_id: str
    content_hash: str
    analyzed_at: str
    forgery_score: str
    explicit_score: str
    decision: str
    issuer: str
    derived_from: str = ""
    spec: str = SPEC
    signature: str = ""

    def payload(self) -> bytes:
        """Canonical bytes covered by the signature (every field except the signature)."""
        fields = {k: v for k, v in asdict(self).items() if k != "signature"}
        return json.dumps(fields, sort_keys=True, separators=(",", ":")).encode()

    def signed(self) -> "Manifest":
        sig = hmac.new(_signing_key(), self.payload(), hashlib.sha256).hexdigest()
        return Manifest(**{**asdict(self), "signature": sig})

    def signature_valid(self) -> bool:
        expected = hmac.new(_signing_key(), self.payload(), hashlib.sha256).hexdigest()
        return hmac.compare_digest(expected, self.signature)


def new_provenance_id() -> str:
    return str(uuid.uuid4())


def content_hash(image_bytes: bytes) -> str:
    """SHA-256 of the decoded RGBA pixels. Metadata changes don't affect it; pixel edits do."""
    with Image.open(io.BytesIO(image_bytes)) as img:
        rgba = img.convert("RGBA")
        digest = hashlib.sha256(f"{rgba.width}x{rgba.height}:".encode())
        digest.update(rgba.tobytes())
    return "sha256:" + digest.hexdigest()


def build_manifest(
    image_bytes: bytes,
    provenance_id: str,
    analyzed_at: str,
    forgery_score: float,
    explicit_score: float,
    decision: str,
    derived_from: str | None = None,
) -> Manifest:
    return Manifest(
        provenance_id=provenance_id,
        content_hash=content_hash(image_bytes),
        analyzed_at=analyzed_at,
        forgery_score=f"{forgery_score:.4f}",
        explicit_score=f"{explicit_score:.4f}",
        decision=decision,
        issuer=_issuer(),
        derived_from=derived_from or "",
    ).signed()


# --------------------------------------------------------------------------- XMP packet

def to_xmp(manifest: Manifest) -> bytes:
    attrs = "".join(
        f"\n    fmd:{xml_name}={quoteattr(getattr(manifest, field))}" for field, xml_name in _FIELDS.items()
    )
    attrs += f"\n    fmd:Signature={quoteattr(manifest.signature)}"
    packet = (
        '<?xpacket begin="\ufeff" id="W5M0MpCehiHzreSzNTczkc9d"?>\n'
        '<x:xmpmeta xmlns:x="adobe:ns:meta/">\n'
        ' <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">\n'
        f'  <rdf:Description rdf:about="" xmlns:fmd="{NS}"{attrs}/>\n'
        " </rdf:RDF>\n"
        "</x:xmpmeta>\n"
        '<?xpacket end="w"?>'
    )
    return packet.encode("utf-8")


def _parse_xmp(xmp: bytes) -> Manifest | None:
    try:
        root = ElementTree.fromstring(xmp)
    except ElementTree.ParseError:
        return None
    for desc in root.iter("{http://www.w3.org/1999/02/22-rdf-syntax-ns#}Description"):
        pid = desc.get(f"{{{NS}}}ProvenanceId")
        if not pid:
            continue
        values = {field: desc.get(f"{{{NS}}}{xml_name}", "") for field, xml_name in _FIELDS.items()}
        return Manifest(**values, signature=desc.get(f"{{{NS}}}Signature", ""))
    return None


_XMP_BLOCK = re.compile(rb"<x:xmpmeta\b.*?</x:xmpmeta>", re.DOTALL)


def read_manifest(image_bytes: bytes) -> Manifest | None:
    """Find our manifest in any uncompressed XMP packet, whatever the container format."""
    for match in _XMP_BLOCK.finditer(image_bytes):
        manifest = _parse_xmp(match.group(0))
        if manifest is not None:
            return manifest
    return None


# --------------------------------------------------------------------------- containers

def _embed_jpeg(data: bytes, xmp: bytes) -> bytes:
    """Replace any XMP APP1 segment with ours; entropy-coded image data is copied verbatim."""
    segment_body = XMP_JPEG_HEADER + xmp
    if len(segment_body) + 2 > 0xFFFF:
        raise ValueError("XMP packet too large for a JPEG APP1 segment")
    ours = b"\xff\xe1" + struct.pack(">H", len(segment_body) + 2) + segment_body

    out = bytearray(data[:2])
    pos = 2
    inserted = False
    while pos + 4 <= len(data) and data[pos] == 0xFF:
        marker = data[pos + 1]
        if marker == 0xDA:  # start of scan: the rest is image data
            break
        length = struct.unpack(">H", data[pos + 2 : pos + 4])[0]
        segment = data[pos : pos + 2 + length]
        pos += 2 + length
        if marker == 0xE1 and segment[4:].startswith(XMP_JPEG_HEADER):
            continue
        if not inserted and marker != 0xE0:  # JFIF APP0 must stay first
            out += ours
            inserted = True
        out += segment
    if not inserted:
        out += ours
    out += data[pos:]
    return bytes(out)


def _png_chunk(kind: bytes, body: bytes) -> bytes:
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)


def _embed_png(data: bytes, xmp: bytes) -> bytes:
    """Replace any XMP iTXt chunk with ours, placed before the first IDAT."""
    itxt = XMP_PNG_KEYWORD + b"\x00" + b"\x00\x00" + b"\x00" + b"\x00" + xmp
    ours = _png_chunk(b"iTXt", itxt)

    out = bytearray(PNG_SIGNATURE)
    pos = len(PNG_SIGNATURE)
    inserted = False
    while pos + 8 <= len(data):
        length = struct.unpack(">I", data[pos : pos + 4])[0]
        kind = data[pos + 4 : pos + 8]
        chunk = data[pos : pos + 12 + length]
        pos += 12 + length
        if kind == b"iTXt" and chunk[8:].startswith(XMP_PNG_KEYWORD + b"\x00"):
            continue
        if kind == b"IDAT" and not inserted:
            out += ours
            inserted = True
        out += chunk
    return bytes(out)


def _to_png(image_bytes: bytes) -> bytes:
    with Image.open(io.BytesIO(image_bytes)) as img:
        buf = io.BytesIO()
        img.convert("RGBA" if img.mode in ("RGBA", "LA", "P", "PA") else "RGB").save(buf, format="PNG")
    return buf.getvalue()


def embed(image_bytes: bytes, manifest: Manifest) -> tuple[bytes, str]:
    """Return (labelled image bytes, mime type). JPEG and PNG keep their pixels bit-for-bit;
    other formats are converted losslessly to PNG first."""
    xmp = to_xmp(manifest)
    if image_bytes[:3] == b"\xff\xd8\xff":
        return _embed_jpeg(image_bytes, xmp), "image/jpeg"
    if image_bytes[:8] == PNG_SIGNATURE:
        return _embed_png(image_bytes, xmp), "image/png"
    return _embed_png(_to_png(image_bytes), xmp), "image/png"


# --------------------------------------------------------------------------- verification

def verify(image_bytes: bytes) -> dict | None:
    """Read back an embedded manifest and check it against the image. None if unlabelled.

    status: VERIFIED (signature valid, pixels unchanged), CONTENT_MODIFIED (signature valid,
    pixels edited since labelling) or INVALID_SIGNATURE (manifest forged or edited).
    """
    manifest = read_manifest(image_bytes)
    if manifest is None:
        return None
    signature_valid = manifest.signature_valid()
    try:
        content_match = content_hash(image_bytes) == manifest.content_hash
    except Exception:  # noqa: BLE001 - byte-edited files may no longer decode
        content_match = False
    if not signature_valid:
        status = "INVALID_SIGNATURE"
    elif not content_match:
        status = "CONTENT_MODIFIED"
    else:
        status = "VERIFIED"
    body: dict = {k: v for k, v in asdict(manifest).items() if k != "signature"}
    for score in ("forgery_score", "explicit_score"):
        try:
            body[score] = float(body[score])
        except ValueError:
            body[score] = None
    body.update(signature_valid=signature_valid, content_match=content_match, status=status)
    return body
