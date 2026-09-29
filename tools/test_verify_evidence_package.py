"""Tests for the offline evidence-package verifier (Phase 28).

The package checks use the fixture that backend/.../HandoverIntegrationTest writes to
backend/target/phase28 (a real package, its signing key and tampered copies); they are skipped
when it hasn't run. CI runs `mvn test` first, then:

    python3 -m unittest discover -s tools -p "test_*.py"
"""

import contextlib
import io
import os
import unittest

import verify_evidence_package as v

FIXTURE = os.environ.get(
    "FMD_PACKAGE_FIXTURE",
    os.path.join(os.path.dirname(__file__), "..", "backend", "target", "phase28"))


class Ed25519Test(unittest.TestCase):
    # RFC 8032 section 7.1, tests 1 and 2
    VECTORS = [
        ("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
         "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
        ("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
         "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
    ]

    def test_rfc8032_vectors_verify(self):
        for pub, msg, sig in self.VECTORS:
            self.assertTrue(v.ed25519_verify(bytes.fromhex(pub), bytes.fromhex(msg), bytes.fromhex(sig)))

    def test_any_changed_bit_fails(self):
        pub, msg, sig = (bytes.fromhex(x) for x in self.VECTORS[1])
        self.assertFalse(v.ed25519_verify(pub, msg + b"\x00", sig))
        self.assertFalse(v.ed25519_verify(pub, msg, sig[:10] + bytes([sig[10] ^ 1]) + sig[11:]))
        self.assertFalse(v.ed25519_verify(pub[:5] + bytes([pub[5] ^ 1]) + pub[6:], msg, sig))


class FormatTest(unittest.TestCase):

    def test_escape_round_trips(self):
        for s in ["plain", "back\\slash", "two\nlines", "cr\rhere", "\\n literal"]:
            self.assertEqual(v.unescape(v.escape(s)), s)

    def test_canonical_matches_the_java_form(self):
        e = {"seq": 2, "occurredAt": "2026-09-29T08:00:00.123456Z", "actor": "moderator", "type": "NOTE_ADDED",
             "analysisId": None, "sightingId": 7, "detail": "line one\nline two"}
        self.assertEqual(v.canonical("INV-ABCD-EFGH", e),
                         "fmd-custody-event/v1\ncase=INV-ABCD-EFGH\nseq=2\noccurred_at=2026-09-29T08:00:00.123456Z\n"
                         "actor=moderator\ntype=NOTE_ADDED\nanalysis_id=\nsighting_id=7\ndetail=line one\\nline two\n")

    def test_parse_refuses_repeated_keys(self):
        with self.assertRaises(v.Tampered):
            v.parse("fmd-evidence-package/v1\na=1\na=2\n", v.VERSION)


@unittest.skipUnless(os.path.isfile(os.path.join(FIXTURE, "package.zip")), "run the backend tests first")
class PackageTest(unittest.TestCase):

    def run_verifier(self, *args):
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            code = v.main(list(args))
        return code, out.getvalue()

    def test_the_issued_package_verifies_with_the_published_key(self):
        code, out = self.run_verifier(os.path.join(FIXTURE, "package.zip"), "--public-key", os.path.join(FIXTURE, "public-key.pem"))
        self.assertEqual(code, 0, out)
        self.assertIn("VALID", out)
        self.assertIn("[ OK ] custody", out)
        self.assertIn("[ OK ] preservation", out)
        self.assertIn("[ OK ] timestamp: RFC 3161 token issued for this signature", out)

    def test_keys_json_and_expected_zip_hash(self):
        with open(os.path.join(FIXTURE, "package.sha256"), encoding="ascii") as fh:
            expected = fh.read().strip()
        code, out = self.run_verifier(os.path.join(FIXTURE, "package.zip"), "--keys", os.path.join(FIXTURE, "keys.json"),
                                      "--sha256", expected)
        self.assertEqual(code, 0, out)
        code, out = self.run_verifier(os.path.join(FIXTURE, "package.zip"), "--keys", os.path.join(FIXTURE, "keys.json"),
                                      "--sha256", "0" * 64)
        self.assertEqual(code, 1, out)

    def test_every_tampered_copy_fails(self):
        tampered = sorted(f for f in os.listdir(FIXTURE) if f.startswith("tampered-") and f.endswith(".zip"))
        self.assertGreater(len(tampered), 5)
        for name in tampered:
            code, out = self.run_verifier(os.path.join(FIXTURE, name), "--public-key", os.path.join(FIXTURE, "public-key.pem"))
            self.assertEqual(code, 1, f"{name} was accepted:\n{out}")
            self.assertIn("TAMPERED", out, name)

    def test_an_unrelated_key_is_refused(self):
        code, out = self.run_verifier(os.path.join(FIXTURE, "package.zip"), "--public-key", os.path.join(FIXTURE, "other-key.pem"))
        self.assertEqual(code, 1, out)
        self.assertIn("not among the keys you gave", out)

    def test_no_key_is_a_usage_error(self):
        code, _ = self.run_verifier(os.path.join(FIXTURE, "package.zip"))
        self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
