# Maintainer radio model — original editable source

`radio.bbmodel` is the maintainer-supplied editable Blockbench project.
`radio.json` is its unmodified Java-model export. Neither file is rewritten by
the assistant's preview tooling. The embedded PNG matches the integrated manual
block atlas exactly. Model authorship is based on the maintainer's statement,
not established by file inspection or similarity metrics.

The maintainer explicitly approved GPL-3.0-only distribution and modification
of the model and editable source on 2026-10-06. `../geometry.json` records this
permission and the unmodified source hashes. The production adapter refuses
changed sources until their provenance is reviewed again.

`scripts/radio_model_source.py` performs shared read-only technical preparation.
`scripts/prepare-radio-model-preview.py` makes a separate smoke-only model under
`src/clientSmoke/resources/assets/etched/models/block/manual_radio_preview.json`.
Its technical changes are limited to:

- Resolving texture `0` to `etched:block/radio` and particle to
  `etched:block/radio_particle`.
- Adding the vanilla block parent and removing the export format-version field.
- Omitting the unresolved interior faces `panel/south`, `panel/west`,
  `sound/east`, and `tune/east`. Each complete face is covered by an adjacent
  cuboid; the preparer rejects an unresolved face if this is no longer true.

Every authored element coordinate, rotation, retained face and UV is preserved,
including mirrored UV rectangles. No new design, UV layout or image pixels are
generated. The scripts are AI-assisted technical tooling, not the source of the
maintainer's artistic geometry. The production adapter
`scripts/generate-radio-models.py` additionally applies the existing legal element
rotation variants about the block center, preserving coordinates and retained UVs.

```sh
python3 scripts/prepare-radio-model-preview.py --write-preview
python3 scripts/prepare-radio-model-preview.py --check
```

With `ETCHED_CLIENT_SMOKE_RADIO_MANUAL_PREVIEW=1`, `runClientSmoke` separately bakes
and checks the four authored cuboids/20 retained faces, then renders the actual
Minecraft model from two headings alongside the existing manual inventory icon.
Screenshot: `/tmp/opencode/etched-maintainer-radio-model-preview.png`.
This isolated preview mode does not replace production models, regenerate the
nine-resource manifest or alter collision/selection shapes. Production integration
uses the separate adapter and common block envelopes; both have regression tests.
Neither GUI preview mode performs a placed-block/playback test.
