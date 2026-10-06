"""Read and technically sanitize the maintainer's Java model; never design it."""

import copy
import json
import math


SOURCE = "assets-src/radio/manual/radio.json"
DIRECTIONS = {"west": (0, -1), "east": (0, 1), "down": (1, -1),
              "up": (1, 1), "north": (2, -1), "south": (2, 1)}


def covered(element, direction, elements):
    axis, sign = DIRECTIONS[direction]
    plane = element["from" if sign < 0 else "to"][axis]
    for other in elements:
        if other is element:
            continue
        start, end = other["from"][axis], other["to"][axis]
        touches = start < plane <= end if sign < 0 else start <= plane < end
        if touches and all(other["from"][a] <= element["from"][a]
                           and other["to"][a] >= element["to"][a]
                           for a in range(3) if a != axis):
            return True
    return False


def load_model(root):
    source = json.loads((root / SOURCE).read_text(encoding="utf-8"))
    elements = source["elements"]
    if source.get("textures", {}).get("0") != "radio":
        raise ValueError("Review changed source texture mapping before adapting the model")
    for element in elements:
        if element.get("rotation", {}).get("angle", 0) != 0:
            raise ValueError("Coverage check supports only unrotated source cuboids")
        for a in range(3):
            if not 0 <= element["from"][a] < element["to"][a] <= 16:
                raise ValueError("Source cuboid must have positive dimensions within the block")
    model = copy.deepcopy(source)
    model.pop("format_version", None)
    model["parent"] = "minecraft:block/block"
    model["textures"] = {"0": "etched:block/radio", "particle": "etched:block/radio_particle"}
    for original, element in zip(elements, model["elements"]):
        for direction, face in list(element["faces"].items()):
            if face["texture"] == "#missing":
                if not covered(original, direction, elements):
                    raise ValueError(f"Untextured visible face: {element['name']}/{direction}")
                del element["faces"][direction]
                continue
            uv = face.get("uv", [])
            if face["texture"] != "#0" or len(uv) != 4 or not all(
                    isinstance(v, (int, float)) and math.isfinite(v) and 0 <= v <= 16 for v in uv):
                raise ValueError(f"Invalid visible face: {element['name']}/{direction}")
    return model
