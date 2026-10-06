# Ordinary radio visual source — Stage 9

The ordinary radio icon and block atlas were drawn from scratch in Aseprite by
the Re-Etched maintainer, as declared by the author. The maintainer explicitly
approved GPL-3.0-only distribution and modification on 2026-10-06. The particle
image is a mechanical crop of the manual atlas, not separately generated art.
Exact image hashes, declared authorship, license permission and crop coordinates
are recorded in `assets-src/radio/artwork.json`. Editable Aseprite originals have
not been supplied in this repository; that limitation is recorded, not concealed.

The **geometry is now the maintainer's manually assembled Blockbench model**.
Its unmodified editable `.bbmodel` and Java export are in `assets-src/radio/manual/`.
`geometry.json` records exact source hashes, declared authorship and explicit
GPL-3.0-only author permission on 2026-10-06. AI-assisted scripts perform only
technical preparation and rotation adaptation, not the artistic design.
The earlier procedural images and AI-assisted geometry remain in Git history only.
This does not establish publication eligibility for the whole repository or remove
applicable AI-code/tooling disclosures.

The four authored cuboids form a rectangular body, front speaker and panel,
and a side-mounted knob. The manual brown finish
matches the transparent 16x16 inventory icon. The 32x32 block atlas uses four
16x16 tiles: shell (upper left), speaker (upper right), tuner (lower left), knob
(lower right). Every block-atlas pixel is opaque. The 16x16 particle image copies
the shell tile at x=0..15,y=0..15 exactly, without resampling or palette changes.

## Inputs and stable technical constraints

- `RadioBlock`: rotation `0..15` and powered state. Common, side-safe selection and
  collision envelopes follow the authored cuboids' rotated bounds for all sixteen
  headings, including the side knob, rather than shrinking it to the old envelope.
  The removed easter egg adds no state property, registered item, alternate
  appearance, loot or conversion path.
- Existing resource paths/IDs and the Minecraft JSON model format. The old
  blockstate mapping was inspected to establish orientation, **not** used as
  model/art input. Rotation zero faces south, advancing clockwise by 22.5°.
- Vanilla `minecraft:block/block` and `minecraft:item/generated` are runtime
  parents; no vanilla image data is copied or distributed in these outputs.

Twenty faces retain the author's exact UVs, including mirrored rectangles.
Four unresolved interior faces fully covered by adjacent cuboids are omitted:
`panel/south`, `panel/west`, `sound/east`, `tune/east`. The preparation script
proves coverage before removal and rejects unresolved visible faces.
Source zero-angle rotations are preserved in the base model. Rotated variants
rotate all cuboids about `[8,0,8]`; blockstate rotations are multiples of 90°.
The retained `_675` path uses a legal -22.5° element rotation with the appropriate blockstate rotation,
not a 67.5° element rotation. Powered is deliberately omitted from the variant
keys because it changes playback eligibility, not appearance.

## Reproduction and audit

```sh
python3 scripts/generate-radio-models.py --check
python3 scripts/generate-radio-models.py --write-models
```

Write mode overwrites **only** six JSON resources and `assets-src/radio/manifest.json`.
Neither mode writes image files. Check mode never writes any files. The manifest
lists nine retained resource paths/hashes: six JSONs and three existing PNGs.
Before any writes it checks image hashes against reviewed `artwork.json` and
both original model source hashes against `geometry.json`. Changed sources require
renewed provenance review, not automatic relabeling by the adapter. Neither mode
writes source models or images. Hashing does not itself establish human authorship.
`RadioAssetsTest` checks hashes, all 32 block states, model parsing and references,
legal rotations, UVs, declared image provenance and exact particle crop.
`ManualRadioPreviewTest` checks exact preservation of authored coordinates/retained
UVs in all four production models, fully covered removed faces, editable-source
consistency, embedded atlas bytes and all sixteen cached shape envelopes. The
transformed GameTest exercises selection/collision and both power states at all
sixteen headings without constructing blocks after JUnit registry freeze.
Image tests constrain dimensions/alpha, not the maintainer's color count. Reproduction
checking is an explicit Python command, not a dependency of Gradle/JUnit.
`RadioAssetSmoke` additionally checks the actual transformed Forge baked models
for all 32 states, four cuboids/20 retained faces, particle/surface atlas sprites and
the item mesh. It also compares all 1536 native sprite pixels with independently
decoded manual PNGs, without closing engine-owned sprites, and checks every baked
quad vertex fits its runtime selection envelope. This verifies actual
client loading/baking, not by itself subjective in-world visual quality.
With `ETCHED_CLIENT_SMOKE_RADIO_PREVIEW=1`, the default startup smoke also renders
the actual block model at rotations 0 and 8 plus its inventory icon, then captures
`/tmp/opencode/etched-maintainer-radio-production-preview.png`. The separate
`ETCHED_CLIENT_SMOKE_RADIO_MANUAL_PREVIEW=1` option renders the isolated technical
preview; see `manual/README.md`. This is a real-engine GUI
model preview, not placement in a world or a new full playback/reopen scenario.

The manual artwork (with explicit author permission), generator and newly authored
JSONs follow GPL-3.0-only. Historical `LICENSE` terms and upstream credit remain intact.
The manifest applies only to its listed current hashes; editing/replacing files
requires renewed provenance review. It does not grant rights to earlier assets.

**Not replaced/cleared by this slice:** radio GUI textures, translations, recipes,
advancements, loot, other feature art, sound or metadata. The Stage 9 resource
audit and release publication gate remain open. Structural tests/builds do not
establish in-game visual quality or a completed client/server release matrix.
