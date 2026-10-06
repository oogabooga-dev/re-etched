#!/usr/bin/env python3
"""Adapt/check the maintainer's ordinary radio model and provenance, never pixels.

The hand-drawn PNGs are read only and checked against reviewed artwork provenance.
Artistic geometry comes from the reviewed Blockbench export, not this tooling.
"""

import argparse
import hashlib
import json
from pathlib import Path

from radio_model_source import load_model


ROOT = Path(__file__).resolve().parent.parent
ASSETS = "src/main/resources/assets/etched/"
MANIFEST = "assets-src/radio/manifest.json"
ARTWORK = "assets-src/radio/artwork.json"
GEOMETRY = "assets-src/radio/geometry.json"


def json_bytes(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def model(angle):
    result = load_model(ROOT)
    if angle:
        for element in result["elements"]:
            element["rotation"] = {"origin": [8, 0, 8], "axis": "y", "angle": angle,
                                   "rescale": False}
    return result


def outputs():
    geometry = json.loads((ROOT / GEOMETRY).read_text(encoding="utf-8"))
    records = geometry["files"]
    expected = {"assets-src/radio/manual/radio.bbmodel", "assets-src/radio/manual/radio.json"}
    if len(records) != 2 or {record["path"] for record in records} != expected:
        raise ValueError("Geometry provenance must list the reviewed editable project and Java export")
    for record in records:
        actual = hashlib.sha256((ROOT / record["path"]).read_bytes()).hexdigest()
        if actual != record["sha256"]:
            raise ValueError(f"Model source changed: {record['path']}; review geometry.json before adapting JSON")
    result = {}
    for suffix, angle in (("", 0), ("_225", 22.5), ("_45", 45), ("_675", -22.5)):
        result[ASSETS + f"models/block/radio{suffix}.json"] = json_bytes(model(angle))
    result[ASSETS + "models/item/radio.json"] = json_bytes({
        "parent": "minecraft:item/generated", "textures": {"layer0": "etched:item/radio"}})
    variants = {}
    for rotation in range(16):
        quarter, step = divmod(rotation, 4)
        suffix = ("", "_675", "_45", "_225")[step]
        y = (180 + quarter * 90 + (90 if step >= 2 else 0)) % 360
        variants[f"rotation={rotation}"] = {"model": f"etched:block/radio{suffix}", "y": y}
    result[ASSETS + "blockstates/radio.json"] = json_bytes({"variants": variants})
    files = {path: hashlib.sha256(data).hexdigest() for path, data in result.items()}
    artwork = json.loads((ROOT / ARTWORK).read_text(encoding="utf-8"))
    image_paths = {ASSETS + f"textures/{texture}.png"
                   for texture in ("block/radio", "block/radio_particle", "item/radio")}
    records = artwork["files"]
    if len(records) != 3 or {record["path"] for record in records} != image_paths:
        raise ValueError("Artwork provenance must list exactly the three ordinary radio PNGs")
    for record in records:
        path = record["path"]
        actual = hashlib.sha256((ROOT / path).read_bytes()).hexdigest()
        if actual != record["sha256"]:
            raise ValueError(f"Artwork changed: {path}; review and update artwork.json before regenerating JSON")
        files[path] = actual
    result[MANIFEST] = json_bytes({
        "schema": 1,
        "generator": "scripts/generate-radio-models.py",
        "artwork_provenance": ARTWORK,
        "geometry_provenance": GEOMETRY,
        "authorship": "Images and Blockbench geometry by Re-Etched maintainer; AI-assisted technical adaptation/tooling",
        "license": "GPL-3.0-only",
        "scope": "Ordinary radio block/item visuals only; other resources and overall publication eligibility remain unaudited",
        "files": [{"path": path, "sha256": digest} for path, digest in sorted(files.items())]})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="verify without writing any files")
    mode.add_argument("--write-models", action="store_true", help="write six JSON resources and manifest; never write PNGs")
    args = parser.parse_args()
    stale = []
    for path, data in outputs().items():
        target = ROOT / path
        if args.check:
            if not target.is_file() or target.read_bytes() != data:
                stale.append(path)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    if stale:
        parser.exit(1, "Stale/missing radio JSON/provenance:\n" + "\n".join(stale) + "\n")
    print("Radio JSON/provenance match (nine resources; PNGs read-only)" if args.check
          else "Wrote six radio JSON resources + manifest; no PNGs written")


if __name__ == "__main__":
    main()
