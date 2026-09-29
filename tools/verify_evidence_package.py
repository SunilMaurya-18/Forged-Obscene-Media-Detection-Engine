#!/usr/bin/env python3
"""Check a Forged Media Detection evidence package offline.

Phase 28. Needs only Python 3.9+ (no packages) and the published signing keys:

    curl -o keys.json https://<platform>/api/verify-report/keys
    python3 verify_evidence_package.py PKG-XXXX-XXXX.zip --keys keys.json

It checks, without contacting the platform:
  1. PACKAGE-SIGNATURE.txt: the Ed25519 signature over SHA-256(PACKAGE-MANIFEST.txt) and the seal over
     the signature block, with a published key (never one taken from the package unless you say so)
  2. every file listed in the manifest is present with the SHA-256 and size it lists, and nothing else is
  3. every link of the custody chain in custody/log.json, up to the head the manifest names
  4. the preservation inside: its signature, and that the chain still reaches the head it signed
  5. the RFC 3161 token (if any) was issued for this signature; the TSA's own signature needs its
     certificate, so check that with openssl (see --extract-timestamp)

Exit status: 0 VALID, 1 TAMPERED or not a package, 2 usage error.
"""

import argparse
import base64
import hashlib
import json
import os
import sys
import zipfile
import zlib

MANIFEST = "PACKAGE-MANIFEST.txt"
SIGNATURE = "PACKAGE-SIGNATURE.txt"
VERSION = "fmd-evidence-package/v1"
SIGNATURE_VERSION = "fmd-evidence-package-signature/v1"
CUSTODY_VERSION = "fmd-custody-event/v1"
GENESIS = "0" * 64
MAX_ENTRIES = 200
MAX_UNCOMPRESSED = 64 * 1024 * 1024
ED25519_SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")
SIGNATURE_KEYS = ["algorithm", "signing_key_id", "manifest_sha256", "signature",
                  "timestamp_token", "timestamp_time", "timestamp_authority"]


class Tampered(Exception):
    pass


class Usage(Exception):
    pass


# ---------------------------------------------------------------- Ed25519 (RFC 8032, verification only)

_P = 2 ** 255 - 19
_L = 2 ** 252 + 27742317777372353535851937790883648493
_D = -121665 * pow(121666, _P - 2, _P) % _P
_I = pow(2, (_P - 1) // 4, _P)


def _recover_x(y, sign):
    if y >= _P:
        return None
    x2 = (y * y - 1) * pow(_D * y * y + 1, _P - 2, _P)
    if x2 % _P == 0:
        return None if sign else 0
    x = pow(x2, (_P + 3) // 8, _P)
    if (x * x - x2) % _P != 0:
        x = x * _I % _P
    if (x * x - x2) % _P != 0:
        return None
    if (x & 1) != sign:
        x = _P - x
    return x


_GY = 4 * pow(5, _P - 2, _P) % _P
_GX = _recover_x(_GY, 0)
_G = (_GX, _GY, 1, _GX * _GY % _P)


def _add(p, q):
    a = (p[1] - p[0]) * (q[1] - q[0]) % _P
    b = (p[1] + p[0]) * (q[1] + q[0]) % _P
    c = 2 * p[3] * q[3] * _D % _P
    d = 2 * p[2] * q[2] % _P
    e, f, g, h = b - a, d - c, d + c, b + a
    return (e * f % _P, g * h % _P, f * g % _P, e * h % _P)


def _mul(s, p):
    q = (0, 1, 1, 0)
    while s > 0:
        if s & 1:
            q = _add(q, p)
        p = _add(p, p)
        s >>= 1
    return q


def _equal(p, q):
    return (p[0] * q[2] - q[0] * p[2]) % _P == 0 and (p[1] * q[2] - q[1] * p[2]) % _P == 0


def _decompress(s):
    if len(s) != 32:
        return None
    y = int.from_bytes(s, "little")
    sign = y >> 255
    y &= (1 << 255) - 1
    x = _recover_x(y, sign)
    return None if x is None else (x, y, 1, x * y % _P)


def ed25519_verify(public_key, message, signature):
    """True if signature is a valid Ed25519 signature of message under the 32-byte public key."""
    if len(public_key) != 32 or len(signature) != 64:
        return False
    a = _decompress(public_key)
    r = _decompress(signature[:32])
    if a is None or r is None:
        return False
    s = int.from_bytes(signature[32:], "little")
    if s >= _L:
        return False
    h = int.from_bytes(hashlib.sha512(signature[:32] + public_key + message).digest(), "little") % _L
    return _equal(_mul(s, _G), _add(r, _mul(h, a)))


# ---------------------------------------------------------------- keys

def key_from_pem(pem):
    """(key_id, raw 32-byte key) from an Ed25519 SubjectPublicKeyInfo PEM."""
    body = "".join(line for line in pem.strip().splitlines() if not line.startswith("-----"))
    der = base64.b64decode(body)
    if len(der) != 44 or not der.startswith(ED25519_SPKI_PREFIX):
        raise ValueError("not an Ed25519 public key")
    return hashlib.sha256(der).hexdigest()[:16], der[12:]


def load_keys(args, embedded):
    keys = {}
    if args.keys:
        with open(args.keys, encoding="utf-8") as fh:
            doc = json.load(fh)
        for k in doc.get("keys", []):
            key_id, raw = key_from_pem(k["publicKey"])
            if k.get("keyId") not in (None, key_id):
                raise ValueError(f"keys file lists {k.get('keyId')} but that PEM hashes to {key_id}")
            keys[key_id] = raw
    for path in args.public_key or []:
        with open(path, encoding="utf-8") as fh:
            key_id, raw = key_from_pem(fh.read())
        keys[key_id] = raw
    if args.trust_embedded_keys:
        for name, data in embedded.items():
            if name.startswith("keys/") and name.endswith(".pem"):
                key_id, raw = key_from_pem(data.decode("utf-8"))
                keys.setdefault(key_id, raw)
    return keys


# ---------------------------------------------------------------- package format

def escape(v):
    return "" if v is None else str(v).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")


def unescape(v):
    out, i = [], 0
    while i < len(v):
        c = v[i]
        if c == "\\" and i + 1 < len(v):
            i += 1
            n = v[i]
            out.append("\n" if n == "n" else "\r" if n == "r" else n)
        else:
            out.append(c)
        i += 1
    return "".join(out)


def parse(raw, version):
    """key=value lines after a version line, strictly (no repeats, final newline required)."""
    if not raw.startswith(version + "\n") or not raw.endswith("\n"):
        raise Tampered(f"expected a {version} document")
    out = {}
    for n, line in enumerate(raw[len(version) + 1:].split("\n")[:-1], start=2):
        key, sep, value = line.partition("=")
        if not sep or not key or key in out:
            raise Tampered(f"line {n} of the {version} document is malformed or repeated")
        out[key] = unescape(value)
    return out


def signature_body(f):
    return (SIGNATURE_VERSION + "\n" + "algorithm=Ed25519\n"
            + "".join(f"{k}={escape(f[k])}\n" for k in SIGNATURE_KEYS[1:]))


def read_zip(path):
    files = {}
    total = 0
    with zipfile.ZipFile(path) as z:
        infos = [i for i in z.infolist() if not i.is_dir()]
        if len(infos) > MAX_ENTRIES:
            raise Tampered(f"more than {MAX_ENTRIES} files")
        for info in infos:
            name = info.filename
            if not name or name.startswith("/") or "\\" in name or ".." in name or ":" in name:
                raise Tampered(f"unsafe file name {name!r}")
            if name in files:
                raise Tampered(f"{name} appears twice")
            total += info.file_size
            if total > MAX_UNCOMPRESSED:
                raise Tampered("the package expands to more than 64 MB")
            files[name] = z.read(info)
    return files


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


# ---------------------------------------------------------------- custody chain

def canonical(case_number, e):
    def num(v):
        return "" if v is None else str(v)
    return (CUSTODY_VERSION + "\n"
            + f"case={escape(case_number)}\n"
            + f"seq={num(e.get('seq'))}\n"
            + f"occurred_at={escape(e.get('occurredAt'))}\n"
            + f"actor={escape(e.get('actor'))}\n"
            + f"type={escape(e.get('type'))}\n"
            + f"analysis_id={num(e.get('analysisId'))}\n"
            + f"sighting_id={num(e.get('sightingId'))}\n"
            + f"detail={escape(e.get('detail'))}\n")


def link(prev, canon):
    return sha256_hex((prev + "\n" + canon).encode("utf-8"))


def verify_chain(case_number, events, length, head):
    """Hash at every position; raises Tampered at the first bad link."""
    prev, hashes = GENESIS, {0: GENESIS}
    for expected, e in enumerate(events, start=1):
        if e.get("seq") != expected:
            raise Tampered(f"custody event {expected} is missing or out of place (found seq {e.get('seq')})")
        if e.get("prevHash") != prev:
            raise Tampered(f"custody event {expected} no longer points to the event before it")
        computed = link(prev, canonical(case_number, e))
        if computed != e.get("hash"):
            raise Tampered(f"custody event {expected} ({e.get('type')}) was changed after it was recorded")
        hashes[expected] = prev = computed
    if len(events) != length:
        raise Tampered(f"the custody log has {len(events)} events but the manifest says {length}")
    if prev != head:
        raise Tampered("the newest custody event does not match the head in the manifest")
    return hashes


# ---------------------------------------------------------------- RFC 3161 token (imprint only)

def _ber(buf, i):
    tag = buf[i]
    i += 1
    if tag & 0x1F == 0x1F:
        while buf[i] & 0x80:
            i += 1
        i += 1
    first = buf[i]
    i += 1
    if first == 0x80:
        end = i
        while buf[end:end + 2] != b"\x00\x00":
            end = _ber(buf, end)[2]
        return tag, buf[i:end], end + 2
    if first & 0x80:
        n = first & 0x7F
        length = int.from_bytes(buf[i:i + n], "big")
        i += n
    else:
        length = first
    return tag, buf[i:i + length], i + length


def _children(content):
    out, i = [], 0
    while i < len(content):
        tag, value, i = _ber(content, i)
        out.append((tag, value))
    return out


def _octets(tag, value):
    return value if tag == 0x04 else b"".join(_octets(t, v) for t, v in _children(value))


TST_INFO_OID = bytes.fromhex("2a864886f70d0109100104")
SHA256_OID = bytes.fromhex("608648016503040201")


def timestamp_imprint(token):
    """(hash algorithm OID bytes, hashed message, genTime) from a timestamp token (CMS SignedData)."""
    _, content_info, _ = _ber(token, 0)
    signed_data = _children(_children(content_info)[1][1])[0][1]
    for tag, value in _children(signed_data):
        kids = _children(value) if tag == 0x30 else []
        if kids and kids[0] == (0x06, TST_INFO_OID):
            wrapper = _children(kids[1][1])[0]
            tst = _children(_ber(_octets(*wrapper), 0)[1])
            imprint = _children(tst[2][1])
            algorithm = _children(imprint[0][1])[0][1]
            gen_time = next(v for t, v in tst if t == 0x18).decode("ascii")
            return algorithm, imprint[1][1], gen_time
    raise ValueError("no TSTInfo in the token")


# ---------------------------------------------------------------- checks

def verify(path, args):
    results = []

    def ok(name, detail):
        results.append((True, name, detail))

    with open(path, "rb") as fh:
        zip_sha = sha256_hex(fh.read())
    files = read_zip(path)
    if MANIFEST not in files or SIGNATURE not in files:
        raise Tampered(f"not an evidence package: no {MANIFEST} and {SIGNATURE}")
    keys = load_keys(args, files)
    if not keys:
        raise Usage("give the published keys with --keys keys.json (GET /api/verify-report/keys) "
                    "or --public-key key.pem; --trust-embedded-keys uses the package's own copies")
    if args.sha256 and args.sha256.lower() != zip_sha:
        raise Tampered(f"the ZIP's SHA-256 is {zip_sha}, not the {args.sha256.lower()} you expected")

    manifest_bytes = files[MANIFEST]
    manifest = parse(manifest_bytes.decode("utf-8"), VERSION)
    sig_raw = files[SIGNATURE].decode("utf-8")
    sig = parse(sig_raw, SIGNATURE_VERSION)
    for k in SIGNATURE_KEYS + ["seal"]:
        if k not in sig:
            raise Tampered(f"{SIGNATURE} has no {k} line")
    if sig["algorithm"] != "Ed25519":
        raise Tampered(f"{SIGNATURE} names algorithm {sig['algorithm']}")
    if signature_body(sig) + f"seal={escape(sig['seal'])}\n" != sig_raw:
        raise Tampered(f"{SIGNATURE} is not in the expected form")
    ok("structure", f"{len(files)} files, manifest {VERSION}")

    key = keys.get(sig["signing_key_id"])
    if key is None:
        raise Tampered(f"signed with key {sig['signing_key_id']}, which is not among the keys you gave")
    digest = hashlib.sha256(manifest_bytes).digest()
    signature = base64.b64decode(sig["signature"])
    if sig["manifest_sha256"] != digest.hex() or not ed25519_verify(key, digest, signature):
        raise Tampered(f"the Ed25519 signature does not match {MANIFEST}")
    seal_msg = hashlib.sha256(signature_body(sig).encode("utf-8")).digest()
    if not ed25519_verify(key, seal_msg, base64.b64decode(sig["seal"])):
        raise Tampered(f"the seal over {SIGNATURE} does not verify: its timestamp or signature lines were changed")
    if manifest.get("signing_key_id") != sig["signing_key_id"]:
        raise Tampered("the manifest names a different signing key")
    ok("signature", f"Ed25519 signature and seal verified with key {sig['signing_key_id']}")

    count = int(manifest.get("file_count", "-1"))
    listed = set()
    for n in range(1, count + 1):
        name = manifest.get(f"file.{n}.path")
        if name is None or name in listed:
            raise Tampered(f"entry {n} of the manifest is missing or repeated")
        listed.add(name)
        data = files.get(name)
        if data is None:
            raise Tampered(f"{name} is listed in the manifest but missing from the package")
        if sha256_hex(data) != manifest.get(f"file.{n}.sha256") or str(len(data)) != manifest.get(f"file.{n}.size"):
            raise Tampered(f"{name} was changed: its SHA-256 or size no longer matches the manifest")
    extra = set(files) - listed - {MANIFEST, SIGNATURE}
    if extra:
        raise Tampered(f"{sorted(extra)[0]} was added; the manifest does not list it")
    ok("files", f"all {count} listed files match their SHA-256 and size")

    case_number = manifest["case_number"]
    pid = manifest["preservation_id"]
    log = json.loads(files["custody/log.json"])
    if log.get("caseNumber") != case_number:
        raise Tampered("the custody log is for a different case")
    hashes = verify_chain(case_number, log.get("events", []), int(manifest["custody_length"]), manifest["custody_head"])
    ok("custody", f"all {len(hashes) - 1} custody events chain correctly to the head in the manifest")

    p_meta = json.loads(files[f"preservation/{pid}.json"])
    p_manifest = files[f"preservation/{pid}.manifest.txt"]
    p_digest = hashlib.sha256(p_manifest).digest()
    p_key = keys.get(p_meta.get("signingKeyId"))
    if p_key is None:
        raise Tampered(f"preservation {pid} was signed with key {p_meta.get('signingKeyId')}, which is not among the keys you gave")
    if (p_meta.get("manifestSha256") != p_digest.hex() or manifest.get("preservation_manifest_sha256") != p_digest.hex()
            or not ed25519_verify(p_key, p_digest, base64.b64decode(p_meta.get("signature") or ""))):
        raise Tampered(f"the signature of preservation {pid} does not match its manifest")
    preserved = {}
    for line in p_manifest.decode("utf-8").split("\n")[1:]:
        k, sep, v = line.partition("=")
        if sep:
            preserved[k] = v
    if preserved.get("preservation_id") != pid or preserved.get("case_number") != case_number:
        raise Tampered("the preserved manifest is for a different preservation or case")
    anchor = int(preserved.get("custody_length", "-1"))
    if hashes.get(anchor) != preserved.get("custody_head"):
        raise Tampered(f"preservation {pid} signed the custody log at event {anchor}, and the log no longer matches it")
    ok("preservation", f"preservation {pid} verified; the custody log still matches the head it signed at event {anchor}")

    if sig["timestamp_token"]:
        token = base64.b64decode(sig["timestamp_token"])
        try:
            algorithm, hashed, gen_time = timestamp_imprint(token)
        except (ValueError, IndexError, StopIteration) as ex:
            raise Tampered(f"the RFC 3161 token can't be read: {ex}")
        if algorithm != SHA256_OID or hashed != hashlib.sha256(signature).digest():
            raise Tampered("the RFC 3161 token was issued for a different signature")
        ok("timestamp", f"RFC 3161 token issued for this signature at {gen_time} by {sig['timestamp_authority'] or 'the TSA'} "
                        "(its TSA signature is not checked here: use --extract-timestamp and openssl)")
        if args.extract_timestamp:
            os.makedirs(args.extract_timestamp, exist_ok=True)
            with open(os.path.join(args.extract_timestamp, "token.tsr"), "wb") as fh:
                fh.write(token)
            with open(os.path.join(args.extract_timestamp, "signature.bin"), "wb") as fh:
                fh.write(signature)
    else:
        ok("timestamp", "no RFC 3161 timestamp (the platform had no TSA configured)")
    return manifest, zip_sha, results


def main(argv=None):
    ap = argparse.ArgumentParser(description="Verify a Forged Media Detection evidence package offline.")
    ap.add_argument("package", help="the package ZIP")
    ap.add_argument("--keys", help="the published keys: save GET /api/verify-report/keys as a file")
    ap.add_argument("--public-key", action="append", help="an Ed25519 public key PEM (repeatable)")
    ap.add_argument("--trust-embedded-keys", action="store_true",
                    help="also accept the keys inside the package (only proves the package is self-consistent)")
    ap.add_argument("--sha256", help="the ZIP's expected SHA-256 (the X-Package-SHA256 of the download)")
    ap.add_argument("--extract-timestamp", metavar="DIR",
                    help="write token.tsr and signature.bin for: openssl ts -verify -token_in -in token.tsr -data signature.bin -CAfile tsa.pem")
    args = ap.parse_args(argv)
    try:
        manifest, zip_sha, results = verify(args.package, args)
    except (Tampered, zipfile.BadZipFile, zlib.error, EOFError, NotImplementedError, KeyError, ValueError,
            TypeError, AttributeError) as ex:
        detail = f"missing {ex}" if isinstance(ex, KeyError) else str(ex)
        print(f"TAMPERED: {detail}")
        return 1
    except (OSError, Usage) as ex:
        print(f"error: {ex}", file=sys.stderr)
        return 2
    for passed, name, detail in results:
        print(f"[{' OK ' if passed else 'FAIL'}] {name}: {detail}")
    print(f"VALID: package {manifest['package_id']} for case {manifest['case_number']}, legal request "
          f"{manifest['legal_request']} ({manifest['authority']}), issued {manifest['issued_at']} to {manifest['investigator']}.")
    print(f"ZIP SHA-256 {zip_sha}")
    if args.trust_embedded_keys and not (args.keys or args.public_key):
        print("Note: checked with the package's own keys. Compare their key IDs with the published ones.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
