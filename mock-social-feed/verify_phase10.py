"""Phase 10 DoD: post manipulated sample via mock-feed proxy → expect REVIEW in queue."""

from __future__ import annotations

import json
import urllib.request
from io import BytesIO

from PIL import Image

ANALYZE = "http://host.docker.internal:3001/api/analyze"
QUEUE = "http://host.docker.internal:3001/api/queue"
AUTH = ("moderator", "moderator")


def manipulated_jpeg() -> bytes:
    img = Image.new("RGB", (224, 224))
    px = img.load()
    for y in range(224):
        for x in range(224):
            n = (x * 13 + y * 7) % 255
            px[x, y] = (n, (n * 3) % 255, 255 - n)
    buf = BytesIO()
    img.save(buf, format="JPEG", quality=92)
    return buf.getvalue()


def multipart(field: str, filename: str, data: bytes, content_type: str) -> tuple[bytes, str]:
    boundary = "----Phase10Boundary"
    parts = [
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'.encode(),
        f"Content-Type: {content_type}\r\n\r\n".encode(),
        data,
        f"\r\n--{boundary}--\r\n".encode(),
    ]
    body = b"".join(parts)
    return body, f"multipart/form-data; boundary={boundary}"


def main() -> None:
    body, ctype = multipart("file", "manipulated-sample.jpg", manipulated_jpeg(), "image/jpeg")
    req = urllib.request.Request(ANALYZE, data=body, headers={"Content-Type": ctype}, method="POST")
    with urllib.request.urlopen(req, timeout=120) as resp:
        analysis = json.loads(resp.read().decode())
    print("analyze:", json.dumps({k: analysis[k] for k in analysis if k != "heatmap"}, indent=2))

    import base64

    token = base64.b64encode(f"{AUTH[0]}:{AUTH[1]}".encode()).decode()
    qreq = urllib.request.Request(QUEUE, headers={"Authorization": f"Basic {token}"})
    with urllib.request.urlopen(qreq, timeout=30) as resp:
        queue = json.loads(resp.read().decode())
    ids = [item["id"] for item in queue]
    print("queue_ids:", ids)
    assert analysis["decision"] == "REVIEW", analysis["decision"]
    assert analysis["id"] in ids, "analysis id missing from moderation queue"
    print("PHASE10_OK")


if __name__ == "__main__":
    main()
