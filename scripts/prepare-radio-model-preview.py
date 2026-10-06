#!/usr/bin/env python3
"""Prepare a smoke-only copy of the maintainer's model, without designing geometry.

Never writes the original JSON, BBModel, PNGs or production resources.
Drops only unresolved faces proved to be fully covered by another cuboid.
"""

import argparse
import json
from pathlib import Path

from radio_model_source import load_model


ROOT = Path(__file__).resolve().parent.parent
OUTPUT = "src/clientSmoke/resources/assets/etched/models/block/manual_radio_preview.json"


def output():
    model = load_model(ROOT)
    return (json.dumps(model, indent=2, ensure_ascii=False) + "\n").encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write-preview", action="store_true")
    args = parser.parse_args()
    data = output()
    target = ROOT / OUTPUT
    if args.check:
        if not target.is_file() or target.read_bytes() != data:
            parser.exit(1, "Stale/missing manual radio preview; run --write-preview\n")
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    print("Manual model preview matches source" if args.check else "Wrote smoke-only manual model preview")


if __name__ == "__main__":
    main()
