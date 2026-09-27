"""Phase 4: NSFW classifier is independent of forgery scoring."""

from __future__ import annotations

import ast
import io
from pathlib import Path

import numpy as np
from PIL import Image

from nsfw_classifier import NSFWClassifier, score_explicit


def _solid_jpeg(color: tuple[int, int, int], size: int = 128) -> bytes:
    img = Image.new("RGB", (size, size), color)
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return buf.getvalue()


def test_nsfw_module_does_not_import_forgery():
    source = Path(__file__).with_name("nsfw_classifier.py").read_text(encoding="utf-8")
    tree = ast.parse(source)
    imported = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            imported.extend(a.name.split(".")[0] for a in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module:
            imported.append(node.module.split(".")[0])
    forbidden = {"inference", "forgery_runtime", "forgery_model", "torch", "torchvision"}
    assert forbidden.isdisjoint(set(imported)), f"NSFW module imports forgery stack: {imported}"


def test_score_explicit_returns_float_independent():
    clf = NSFWClassifier(backend="heuristic")
    out = score_explicit(_solid_jpeg((200, 160, 140)), classifier=clf)
    assert set(out) == {"explicit_score"}
    assert isinstance(out["explicit_score"], float)
    assert 0.0 <= out["explicit_score"] <= 1.0


def test_explicit_and_forgery_are_separate_callables():
    # Importing inference would pull torch; we only check API shape via nsfw
    a = score_explicit(_solid_jpeg((10, 10, 10)), classifier=NSFWClassifier(backend="heuristic"))
    b = score_explicit(_solid_jpeg((220, 180, 160)), classifier=NSFWClassifier(backend="heuristic"))
    assert isinstance(a["explicit_score"], float)
    assert isinstance(b["explicit_score"], float)
