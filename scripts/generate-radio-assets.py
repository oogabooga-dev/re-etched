#!/usr/bin/env python3
"""Generate the first Stage 9 art slice from numeric primitives, not input art.

Python standard library only. Run with --check to detect stale/missing outputs.
The generator and its outputs follow the repository's GPL-3.0-only code policy.
"""

import argparse
import hashlib
import json
from pathlib import Path
import struct
import zlib


ROOT = Path(__file__).resolve().parent.parent
ASSETS = "src/main/resources/assets/etched/"
MANIFEST = "assets-src/radio/manifest.json"


def json_bytes(value):
    return (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def png(pixels):
    """Encode deterministic RGBA8 PNGs, with no timestamp/profile/text chunks."""
    height, width = len(pixels), len(pixels[0])

    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff))

    # Stored DEFLATE blocks avoid output depending on a zlib compressor version.
    raw = b"".join(b"\0" + bytes(channel for pixel in row for channel in pixel)
                   for row in pixels)
    blocks = []
    for start in range(0, len(raw), 65535):
        block = raw[start:start + 65535]
        final = start + len(block) == len(raw)
        blocks.append(bytes([int(final)]) + struct.pack("<HH", len(block), len(block) ^ 0xffff) + block)
    compressed = b"\x78\x01" + b"".join(blocks) + struct.pack(">I", zlib.adler32(raw) & 0xffffffff)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", compressed) + chunk(b"IEND", b""))


def rgba(color):
    return (*color, 255)


def rect(pixels, x0, y0, x1, y1, color):
    for y in range(y0, y1):
        for x in range(x0, x1):
            pixels[y][x] = rgba(color)


def palette(portal):
    # Two independently specified finishes: slate/copper and chalk/violet.
    if portal:
        return (181, 197, 207), (219, 230, 232), (85, 102, 123), (153, 110, 233)
    return (44, 66, 78), (77, 111, 124), (23, 36, 45), (238, 164, 78)


def atlas(portal):
    body, edge, dark, accent = palette(portal)
    pixels = [[rgba(body) for _ in range(32)] for _ in range(32)]
    # Four 16px tiles: metal shell, perforated speaker, tuner, knob.
    for y in range(16):
        for x in range(16):
            if x == 0 or y == 0:
                pixels[y][x] = rgba(edge)
            elif x == 15 or y == 15:
                pixels[y][x] = rgba(dark)
    rect(pixels, 16, 0, 32, 16, dark)
    for y in range(2, 15, 3):
        for x in range(18, 31, 3):
            rect(pixels, x, y, x + 1, y + 1, edge)
    rect(pixels, 0, 16, 16, 32, dark)
    rect(pixels, 2, 19, 14, 20, edge)
    for x in range(3, 14, 2):
        rect(pixels, x, 20, x + 1, 23, edge)
    rect(pixels, 6 if portal else 10, 18, 7 if portal else 11, 27, accent)
    rect(pixels, 2, 28, 14, 30, accent)
    rect(pixels, 16, 16, 32, 32, body)
    for y in range(16, 32):
        for x in range(16, 32):
            distance = (x - 23.5) ** 2 + (y - 23.5) ** 2
            pixels[y][x] = rgba(dark if distance > 43 else edge if distance > 25 else accent)
    rect(pixels, 23, 18, 25, 22, dark)
    return pixels


def icon(portal):
    body, edge, dark, accent = palette(portal)
    pixels = [[(0, 0, 0, 0) for _ in range(16)] for _ in range(16)]
    rect(pixels, 3, 13, 5, 15, dark)
    rect(pixels, 11, 13, 13, 15, dark)
    rect(pixels, 4, 2, 12, 4, edge)
    rect(pixels, 2, 4, 14, 14, dark)
    rect(pixels, 3, 5, 13, 13, body)
    rect(pixels, 3, 5, 13, 6, edge)
    rect(pixels, 4, 7, 8, 12, dark)
    for x, y in ((4, 7), (6, 7), (5, 9), (7, 9), (4, 11), (6, 11)):
        pixels[y][x] = rgba(edge)
    rect(pixels, 9, 7, 12, 9, dark)
    rect(pixels, 10, 7, 11, 9, accent)
    rect(pixels, 10, 10, 12, 12, accent)
    return pixels


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


def model(name, angle):
    # New compact desk receiver; dimensions come from RadioBlock's shape envelope.
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
            "textures": {"particle": f"etched:block/{name}_particle",
                         "surface": f"etched:block/{name}"},
            "elements": [element(start, end, tile, angle, front) for start, end, tile, front in parts]}


def outputs():
    result = {}
    variants = {}
    for portal in (False, True):
        name = "portal_radio" if portal else "radio"
        pixels = atlas(portal)
        result[ASSETS + f"textures/block/{name}.png"] = png(pixels)
        result[ASSETS + f"textures/block/{name}_particle.png"] = png([row[:16] for row in pixels[:16]])
        result[ASSETS + f"textures/item/{name}.png"] = png(icon(portal))
        result[ASSETS + f"models/item/{name}.json"] = json_bytes({
            "parent": "minecraft:item/generated", "textures": {"layer0": f"etched:item/{name}"}})
        # _675 keeps a technical resource ID, not an inherited 67.5-degree mesh.
        # Element rotations are legal vanilla angles; blockstate Y is clockwise.
        for suffix, angle in (("", 0), ("_225", 22.5), ("_45", 45), ("_675", -22.5)):
            result[ASSETS + f"models/block/{name}{suffix}.json"] = json_bytes(model(name, angle))
        for rotation in range(16):
            quarter, step = divmod(rotation, 4)
            suffix = ("", "_675", "_45", "_225")[step]
            y = (180 + quarter * 90 + (90 if step >= 2 else 0)) % 360
            variants[f"portal={str(portal).lower()},rotation={rotation}"] = {
                "model": f"etched:block/{name}{suffix}", "y": y}
    result[ASSETS + "blockstates/radio.json"] = json_bytes({"variants": variants})
    result[MANIFEST] = json_bytes({
        "schema": 1,
        "generator": "scripts/generate-radio-assets.py",
        "authorship": "AI-assisted procedural design for Re-Etched; no input artwork or model geometry",
        "license": "GPL-3.0-only",
        "scope": "Radio/Portal Radio block and item visuals only; GUI, data, language and other resources remain unaudited",
        "files": [{"path": path, "sha256": hashlib.sha256(data).hexdigest()}
                  for path, data in sorted(result.items())]})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify without writing any files")
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
        parser.exit(1, "Stale/missing radio assets:\n" + "\n".join(stale) + "\n")
    print("Radio assets match generator (17 resources + manifest)" if args.check
          else "Generated 17 radio resources + provenance manifest")


if __name__ == "__main__":
    main()
