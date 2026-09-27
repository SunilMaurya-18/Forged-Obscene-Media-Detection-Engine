"""Phase 14: provenance manifest embed / read-back / tamper detection."""

from __future__ import annotations

import base64
import io
import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from PIL import Image

import provenance


def _image_bytes(fmt: str, color=(90, 140, 200), size=64) -> bytes:
    img = Image.new("RGB", (size, size), color)
    for x in range(0, size, 4):
        img.putpixel((x, x), (255 - x, x, 128))
    buf = io.BytesIO()
    img.save(buf, format=fmt)
    return buf.getvalue()


def _label(raw: bytes, pid: str = "11111111-2222-3333-4444-555555555555", **overrides):
    fields = dict(
        provenance_id=pid,
        analyzed_at="2026-09-27T09:00:00Z",
        forgery_score=0.8731,
        explicit_score=0.02,
        decision="AUTO_FLAG",
    )
    fields.update(overrides)
    manifest = provenance.build_manifest(raw, **fields)
    return provenance.embed(raw, manifest)


@pytest.mark.parametrize("fmt, mime", [("JPEG", "image/jpeg"), ("PNG", "image/png"), ("WEBP", "image/png")])
def test_manifest_round_trips(fmt, mime):
    raw = _image_bytes(fmt)
    labelled, mime_type = _label(raw, derived_from="aaaaaaaa-0000-0000-0000-000000000000")

    assert mime_type == mime
    result = provenance.verify(labelled)
    assert result is not None
    assert result["status"] == "VERIFIED"
    assert result["signature_valid"] and result["content_match"]
    assert result["provenance_id"] == "11111111-2222-3333-4444-555555555555"
    assert result["decision"] == "AUTO_FLAG"
    assert result["forgery_score"] == pytest.approx(0.8731)
    assert result["analyzed_at"] == "2026-09-27T09:00:00Z"
    assert result["derived_from"] == "aaaaaaaa-0000-0000-0000-000000000000"
    assert result["content_hash"] == provenance.content_hash(raw)


@pytest.mark.parametrize("fmt", ["JPEG", "PNG"])
def test_labelling_keeps_pixels_bit_identical(fmt):
    raw = _image_bytes(fmt)
    labelled, _ = _label(raw)
    with Image.open(io.BytesIO(raw)) as a, Image.open(io.BytesIO(labelled)) as b:
        assert a.convert("RGB").tobytes() == b.convert("RGB").tobytes()


def test_relabelling_replaces_previous_manifest():
    raw = _image_bytes("JPEG")
    first, _ = _label(raw, pid="first")
    second, _ = _label(first, pid="second", derived_from="first")
    assert second.count(b"<x:xmpmeta") == 1
    result = provenance.verify(second)
    assert result["provenance_id"] == "second"
    assert result["derived_from"] == "first"


def test_unlabelled_image_has_no_manifest():
    assert provenance.verify(_image_bytes("JPEG")) is None
    assert provenance.verify(_image_bytes("PNG")) is None


def test_edited_manifest_fails_signature():
    labelled, _ = _label(_image_bytes("PNG"))
    tampered = labelled.replace(b'fmd:Decision="AUTO_FLAG"', b'fmd:Decision="ALLOW"    ')
    # The raw byte edit also breaks the chunk CRC, so the pixels no longer decode either.
    result = provenance.verify(tampered)
    assert result["decision"] == "ALLOW"
    assert result["signature_valid"] is False
    assert result["status"] == "INVALID_SIGNATURE"


def test_edited_pixels_are_detected():
    raw = _image_bytes("PNG")
    labelled, _ = _label(raw)
    manifest = provenance.read_manifest(labelled)

    edited = Image.open(io.BytesIO(raw)).convert("RGB")
    edited.putpixel((1, 2), (0, 0, 0))
    buf = io.BytesIO()
    edited.save(buf, format="PNG")
    relabelled_with_old_manifest = provenance.embed(buf.getvalue(), manifest)[0]

    result = provenance.verify(relabelled_with_old_manifest)
    assert result["signature_valid"] is True
    assert result["content_match"] is False
    assert result["status"] == "CONTENT_MODIFIED"


def test_signature_depends_on_key(monkeypatch):
    labelled, _ = _label(_image_bytes("JPEG"))
    monkeypatch.setenv("PROVENANCE_SIGNING_KEY", "some-other-key")
    assert provenance.verify(labelled)["status"] == "INVALID_SIGNATURE"


# --------------------------------------------------------------------------- HTTP

@pytest.fixture()
def api():
    import main

    return TestClient(main.app)  # no lifespan: these routes don't need the models


def test_embed_then_read_endpoints(api):
    raw = _image_bytes("JPEG")
    r = api.post(
        "/provenance/embed",
        files={"file": ("a.jpg", raw, "image/jpeg")},
        data={
            "provenance_id": "http-test-id",
            "analyzed_at": "2026-09-27T09:00:00Z",
            "forgery_score": "0.42",
            "explicit_score": "0.01",
            "decision": "REVIEW",
        },
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["mime_type"] == "image/jpeg"
    assert body["manifest"]["status"] == "VERIFIED"
    labelled = base64.b64decode(body["image"])

    r = api.post("/provenance/read", files={"file": ("a.jpg", labelled, "image/jpeg")})
    assert r.status_code == 200
    read = r.json()
    assert read["provenance"]["provenance_id"] == "http-test-id"
    assert read["provenance"]["decision"] == "REVIEW"
    assert read["content_hash"] == provenance.content_hash(raw)

    r = api.post("/provenance/read", files={"file": ("b.jpg", raw, "image/jpeg")})
    assert r.json()["provenance"] is None


def test_embed_rejects_out_of_range_score(api):
    r = api.post(
        "/provenance/embed",
        files={"file": ("a.jpg", _image_bytes("JPEG"), "image/jpeg")},
        data={"provenance_id": "x", "analyzed_at": "t", "forgery_score": "1.5", "explicit_score": "0", "decision": "ALLOW"},
    )
    assert r.status_code == 422


CHECKPOINT = Path(
    os.getenv("FORGERY_CHECKPOINT", Path(__file__).resolve().parent.parent / "ml" / "models" / "checkpoint.pt")
)


def test_infer_issues_id_and_reads_back_reuploaded_manifest():
    """DoD: a processed image's embedded manifest is read back correctly when re-uploaded."""
    if not CHECKPOINT.exists():
        pytest.skip(f"Missing checkpoint: {CHECKPOINT}")
    os.environ["NSFW_BACKEND"] = "heuristic"
    import inference as inf
    import main

    if inf._runtime is None:
        inf.init_runtime(checkpoint=CHECKPOINT, device="cpu")
    api = TestClient(main.app)

    raw = _image_bytes("JPEG", size=224)
    first = api.post("/infer", files={"file": ("a.jpg", raw, "image/jpeg")}).json()
    assert first["provenance"] is None
    assert len(first["provenance_id"]) == 36
    assert first["content_hash"].startswith("sha256:")

    labelled, _ = _label(raw, pid=first["provenance_id"], forgery_score=first["forgery_score"])
    again = api.post("/infer", files={"file": ("a.jpg", labelled, "image/jpeg")}).json()

    assert again["provenance_id"] != first["provenance_id"]
    assert again["provenance"]["provenance_id"] == first["provenance_id"]
    assert again["provenance"]["status"] == "VERIFIED"
    assert again["forgery_score"] == pytest.approx(first["forgery_score"])
    assert again["phash"] == first["phash"]
