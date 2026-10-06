#!/usr/bin/env python3
"""Generate/check ordinary radio JSON models and provenance, never image pixels.

The three existing PNGs are read only for hashes. They await hand-drawn replacement.
This AI-assisted geometry is not a claim of Modrinth publication eligibility.
"""

import argparse
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
ASSETS = "src/main/resources/assets/etched/"
MANIFEST = "assets-src/radio/manifest.json"


def json_bytes(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def element(start, end, tile, angle, front_tile=None):
    tiles = {"shell": [0, 0, 8, 8], "speaker": [8, 0, 16, 8],
             "tuner": [0, 8, 8, 16], "knob": [8, 8, 16, 16]}
    faces = {face: {"uv": tiles[front_tile if face == "north" and front_tile else tile],
                    "texture": "#surface"}
             for face in ("down", "up", "north", "south", "west", "east")}
    result = {"from": start, "to": end, "faces": faces}
    if angle:
        result["rotation"] = {"origin": [8, 0, 8], "axis": "y", "angle": angle,
                              "rescale": False}
    return result


def model(angle):
    # Unchanged ordinary receiver; dimensions come from RadioBlock's shape envelope.
    parts = [
        ([3.5, 1, 6.25], [12.5, 7, 9.75], "shell", None),
        ([4.5, 7, 6.75], [11.5, 8, 9.25], "shell", None),
        ([4.25, 0, 6.75], [5.75, 1, 9.25], "shell", None),
        ([10.25, 0, 6.75], [11.75, 1, 9.25], "shell", None),
        ([4, 2, 6], [8, 6, 6.25], "shell", "speaker"),
        ([9, 4.5, 6], [12, 6, 6.25], "shell", "tuner"),
        ([10, 2, 6], [11.5, 3.5, 6.25], "shell", "knob"),
    ]
    return {"parent": "minecraft:block/block", "ambientocclusion": True,
            "textures": {"particle": "etched:block/radio_particle",
                         "surface": "etched:block/radio"},
            "elements": [element(start, end, tile, angle, front) for start, end, tile, front in parts]}


def outputs():
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
    for texture in ("block/radio", "block/radio_particle", "item/radio"):
        path = ASSETS + f"textures/{texture}.png"
        files[path] = hashlib.sha256((ROOT / path).read_bytes()).hexdigest()
    result[MANIFEST] = json_bytes({
        "schema": 1,
        "generator": "scripts/generate-radio-models.py",
        "authorship": "AI-assisted geometry and existing procedural PNGs; hand-drawn images pending",
        "license": "GPL-3.0-only",
        "scope": "Ordinary radio block/item visuals only; existing PNGs not accepted for release; GUI and other resources remain unaudited",
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
