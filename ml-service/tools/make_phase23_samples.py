"""Writes the Phase 23 demo samples into frontend/public (synthetic content only).

- c2pa-sample.jpg: a generated test pattern whose C2PA manifest declares an AI generator plus an edit,
  signed with a long-lived in-memory development certificate (readers report "valid, untrusted signer").
- sample-clip.mp4: a 4-second moving-bar test clip for the video path.

Run inside the ml-service image:  python tools/make_phase23_samples.py /out
"""

from __future__ import annotations

import io
import sys
from pathlib import Path

import av
import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import content_credentials as cc  # noqa: E402

AI_SOURCE = "http://cv.iptc.org/newscodes/digitalsourcetype/trainedAlgorithmicMedia"
SAMPLE_CERT_DAYS = 20 * 365


def pattern(size: int = 384) -> bytes:
    img = Image.new("RGB", (size, size), (18, 22, 40))
    draw = ImageDraw.Draw(img)
    for i in range(0, size, 24):
        draw.line([(0, i), (size, size - i)], fill=(60 + i // 3, 90, 200 - i // 3), width=3)
    draw.ellipse([size * 0.3, size * 0.25, size * 0.7, size * 0.75], outline=(250, 200, 90), width=6)
    draw.text((16, size - 28), "SYNTHETIC TEST PATTERN - C2PA SAMPLE", fill=(230, 230, 230))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=90)
    return buf.getvalue()


def c2pa_sample() -> bytes:
    manifest = {
        "claim_generator_info": [{"name": "SyntheticStudio", "version": "2.1"}],
        "title": "c2pa-sample.jpg",
        "assertions": [{
            "label": "c2pa.actions",
            "data": {"actions": [
                {"action": "c2pa.created", "digitalSourceType": AI_SOURCE,
                 "softwareAgent": {"name": "SyntheticStudio", "version": "2.1"}},
                {"action": "c2pa.color_adjustments", "softwareAgent": {"name": "PhotoFixer", "version": "5"}},
            ]},
        }],
    }
    # The committed sample has no TSA timestamp, so its leaf must outlive the repo, not the default 90 days
    chain, key = cc._dev_chain(leaf_days=SAMPLE_CERT_DAYS, ca_days=SAMPLE_CERT_DAYS)
    cc._credentials = (chain, key, "Forged Media Detection (dev)", True)
    return cc.sign_manifest(pattern(), manifest)


def clip(seconds: int = 4, fps: int = 12) -> bytes:
    buf = io.BytesIO()
    with av.open(buf, mode="w", format="mp4") as out:
        stream = out.add_stream("mpeg4", rate=fps)
        stream.width, stream.height, stream.pix_fmt = 320, 240, "yuv420p"
        stream.bit_rate = 400_000
        for i in range(seconds * fps):
            img = np.full((240, 320, 3), 24, np.uint8)
            img[:, :, 2] = 60 + (i * 3) % 120
            x = (i * 6) % 280
            img[60:180, x:x + 40] = (240, 190, 70)
            for packet in stream.encode(av.VideoFrame.from_ndarray(img, format="rgb24")):
                out.mux(packet)
        for packet in stream.encode():
            out.mux(packet)
    return buf.getvalue()


def main() -> None:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[2] / "frontend" / "public")
    out.mkdir(parents=True, exist_ok=True)
    (out / "c2pa-sample.jpg").write_bytes(c2pa_sample())
    (out / "sample-clip.mp4").write_bytes(clip())
    print("wrote", out / "c2pa-sample.jpg", "and", out / "sample-clip.mp4")


if __name__ == "__main__":
    main()
