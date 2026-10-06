# Ordinary radio visual source — Stage 9

The ordinary radio icon and block atlas were drawn from scratch in Aseprite by
the Re-Etched maintainer, as declared by the author. The maintainer explicitly
approved GPL-3.0-only distribution and modification on 2026-10-06. The particle
image is a mechanical crop of the manual atlas, not separately generated art.
Exact image hashes, declared authorship, license permission and crop coordinates
are recorded in `assets-src/radio/artwork.json`. Editable Aseprite originals have
not been supplied in this repository; that limitation is recorded, not concealed.

The **JSON geometry remains AI-assisted**, separately from the manual images.
Its source is `scripts/generate-radio-models.py`, using only Python's standard
library. Seven cuboids define the geometry, without inherited mesh or UV-map
inputs. The earlier procedural images and their pixel-generation source remain
in Git history only. This replacement does not establish publication eligibility
for the whole repository or remove applicable AI-code/model disclosures.

The compact desk receiver has a raised control housing, two short feet, an
offset speaker panel and a separate tuner and knob. The manual brown finish
matches the transparent 16x16 inventory icon. The 32x32 block atlas uses four
16x16 tiles: shell (upper left), speaker (upper right), tuner (lower left), knob
(lower right). Every block-atlas pixel is opaque. The 16x16 particle image copies
the shell tile at x=0..15,y=0..15 exactly, without resampling or palette changes.

## Inputs and stable technical constraints

- `RadioBlock`: rotation `0..15`, powered state and existing collision envelopes.
  The visual envelope fits the existing shapes. The removed easter egg adds no
  state property, registered item, alternate appearance, loot or conversion path.
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
python3 scripts/generate-radio-models.py --check
python3 scripts/generate-radio-models.py --write-models
```

Write mode overwrites **only** six JSON resources and `assets-src/radio/manifest.json`.
Neither mode writes image files. Check mode never writes any files. The manifest
lists nine retained resource paths/hashes: six JSONs and three existing PNGs. It
checks PNG hashes against the independently reviewed `artwork.json` before any
JSON writes. Changed images require renewed provenance review, not automatic
relabeling by the generator. Hashing does not itself establish human authorship.
`RadioAssetsTest` checks hashes, all 32 block states, model parsing and references,
legal rotations, bounds, UVs, declared image provenance and exact particle crop.
Image tests constrain dimensions/alpha, not the maintainer's color count. Reproduction
checking is an explicit Python command, not a dependency of Gradle/JUnit.
`RadioAssetSmoke` additionally checks the actual transformed Forge baked models
for all 32 states, seven six-faced cuboids, particle/surface atlas sprites and
the item mesh. It also compares all 1536 native sprite pixels with independently
decoded manual PNGs, without closing engine-owned sprites. This verifies actual
client loading/baking, not by itself subjective in-world visual quality.
With `ETCHED_CLIENT_SMOKE_RADIO_PREVIEW=1`, the default startup smoke also renders
the actual block model at rotations 0 and 8 plus its inventory icon, then captures
`/tmp/opencode/etched-manual-radio-client-preview.png`. This is a real-engine GUI
model preview, not placement in a world or a new full playback/reopen scenario.

The manual artwork (with explicit author permission), generator and newly authored
JSONs follow GPL-3.0-only. Historical `LICENSE` terms and upstream credit remain intact.
The manifest applies only to its listed current hashes; editing/replacing files
requires renewed provenance review. It does not grant rights to earlier assets.

**Not replaced/cleared by this slice:** radio GUI textures, translations, recipes,
advancements, loot, other feature art, sound or metadata. The Stage 9 resource
audit and release publication gate remain open. Structural tests/builds do not
establish in-game visual quality or a completed client/server release matrix.
