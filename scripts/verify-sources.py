#!/usr/bin/env python3
"""Verify vendored grammar and official corpus bytes against their recorded SHA-256."""
import hashlib
import json
from pathlib import Path
root = Path(__file__).resolve().parents[1]
items = [(root / item["path"], item["sha256"]) for item in json.loads((root / "grammar-sources.json").read_text())]
corpus = root / "src/test/resources/corpus"
items += [(corpus / item["resource"], item["sha256"]) for item in json.loads((corpus / "sources.json").read_text())]
for path, expected in items:
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f"Source hash mismatch: {path}")
print(f"Verified {len(items)} pinned source files")
