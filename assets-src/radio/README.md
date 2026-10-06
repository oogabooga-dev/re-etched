# Radio visual source — Stage 9, slice 1

These Radio and Portal Radio **block/item** visuals are a new AI-assisted
procedural design for Re-Etched. They are not hand-drawn maintainer artwork.
The source is `scripts/generate-radio-assets.py`, using only Python's standard
library. No inherited image, mesh, texture UV map or third-party art is read by
the generation path. Numeric rectangles, a perforation grid, a tuner scale and
a circular knob produce the textures; seven newly specified cuboids produce
the geometry. This is not a claim of legal clearance for the whole repository.

The compact desk receiver has a raised control housing, two short feet, an
offset speaker panel and a separate tuner and knob. The finishes are slate with
copper accents and chalk with violet accents. Both use the same new geometry.
Item sprites are separately constructed transparent 16x16 silhouettes, not crops
of upstream item textures. The 32x32 block atlas uses four 16x16 tiles. Particle
textures use the newly generated shell tile.

## Inputs and stable technical constraints

- `RadioBlock`: rotation `0..15`, portal boolean, powered state and existing
  collision envelopes. The new visual envelope fits the existing shapes; Java
  geometry/gameplay is not changed.
- Existing resource paths/IDs and the Minecraft JSON model format. The old
  blockstate mapping was inspected to establish orientation, **not** used as
  model/art input. Rotation zero faces south, advancing clockwise by 22.5°.
- Vanilla `minecraft:block/block` and `minecraft:item/generated` are runtime
  parents; no vanilla image data is copied or distributed in these outputs.

All faces are explicitly UV-mapped to the new atlas. All cuboids rotate about
`[8,0,8]`; blockstate rotations are multiples of 90°. The retained `_675` path
now uses a legal -22.5° element rotation with the appropriate blockstate rotation,
not a 67.5° element rotation. Powered is deliberately omitted from the variant
keys because it changes playback eligibility, not appearance.

## Reproduction and audit

```sh
python3 scripts/generate-radio-assets.py
python3 scripts/generate-radio-assets.py --check
```

Generation overwrites **only** the 17 declared radio block/item resources and
`assets-src/radio/manifest.json`. Check mode never writes. The manifest lists exact
resource paths and SHA-256 hashes. PNGs have deterministic stored DEFLATE blocks,
no metadata, external tools, random values or version-dependent compression.
`RadioAssetsTest` checks hashes, all 64 block states, model parsing and references,
legal rotations, bounds, UVs and image dimensions/transparency. Reproduction
checking is an explicit Python command, not a dependency of Gradle/JUnit.
`RadioAssetSmoke` additionally checks the actual transformed Forge baked models
for all 64 states, seven six-faced cuboids, particle/surface atlas sprites and
both generated item meshes. This verifies baking, not subjective visual quality.

The generator and these newly authored outputs follow GPL-3.0-only, like the
repository's code. Historical `LICENSE` terms and upstream credit remain intact.
The manifest applies only to its listed current hashes; editing/replacing files
requires renewed provenance review. It does not grant rights to earlier assets.

**Not replaced/cleared by this slice:** radio GUI textures, translations, recipes,
advancements, loot, other feature art, sound or metadata. The Stage 9 resource
audit and release publication gate remain open. Structural tests/builds do not
establish in-game visual quality or a completed client/server release matrix.
