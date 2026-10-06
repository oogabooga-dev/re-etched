# Third-Party Notices

## Original Etched

Re-Etched is an unofficial fork of Etched 3.0.4.

- Original project: https://github.com/jacksonhardaway/etched
- Fork base: https://github.com/jacksonhardaway/etched/commit/b57a6286c6bf3109a26bbdbceed8b6a46b60d9fe

Original project copyright and license terms are reproduced in the repository
`LICENSE` file and as `META-INF/LICENSE_RE-ETCHED` in the built JAR.

The original Etched project identifies its resource directories as:

```text
All Rights Reserved
Copyright (c) 2021 Moonflower Studio
```

Those resources were later consolidated under `src/main/resources`, a path not
named by the historical directory list. Re-Etched conservatively treats the
inherited models, textures, sounds, translations, and data files as
reserved while their status is clarified. Re-Etched does not claim ownership
of them, and this notice does not grant additional rights to them.

Stage 9 independently replaces resources in bounded slices. The ordinary radio
icon and block atlas recorded in `assets-src/radio/artwork.json` were drawn from
scratch in Aseprite by the Re-Etched maintainer, as declared by the author. The
particle texture is an exact crop of the manual atlas. The maintainer explicitly
approved GPL-3.0-only distribution and modification on 2026-10-06. Editable
Aseprite originals have not yet been supplied in the repository.

The radio geometry was manually assembled in Blockbench by the maintainer, who
explicitly approved GPL-3.0-only distribution and modification of the model and
editable source on 2026-10-06. The unmodified `.bbmodel` and Java export are in
`assets-src/radio/manual/`, with reviewed hashes in `assets-src/radio/geometry.json`.
AI-assisted technical tooling resolves texture IDs, removes four fully covered
unresolved interior faces and creates rotation variants, without redesigning
authored coordinates or retained UVs. It never writes PNGs. The earlier AI-assisted
geometry is no longer the current radio model, but remains in Git history.
`assets-src/radio/manifest.json` pins the nine current image/JSON hashes. Source
notes and adaptation scripts are in `assets-src/radio/` and `scripts/`.
These new images and model outputs follow GPL-3.0-only.
This block/item exception does not cover historical files, radio data/translations
or other inherited resources. Radio GUI artwork is recorded separately below.
The replacement-resource audit and publication gate,
including applicable AI-code/tooling disclosures and fork review, remain open.

The three radio GUI images `radio_background.png`, `radio_url_active.png` and
`radio_url_inactive.png` under `assets/etched/textures/gui/` were drawn manually
by the Re-Etched maintainer, as explicitly declared by the author. On 2026-10-06
the maintainer approved GPL-3.0-only distribution and modification for all three.
Their exact hashes and dimensions are recorded in `assets-src/radio/gui-artwork.json`.
The PNGs are unchanged from the reviewed local 4.x branch; this notice records
permission, not a new redraw. Editable drawing originals have not yet been
supplied in this repository. The exception applies only to those recorded images,
not all resources used by the radio screen or historical versions of the files.

The Re-Etched logo and icon are original fork branding and are not inherited
from Etched. They were drawn manually in GIMP by the Re-Etched maintainer,
without generative AI. On 2026-10-06 the maintainer explicitly identified
`src/main/resources/re-etched.icon.png` and `src/main/resources/re-etched.logo.png`
and approved GPL-3.0-only distribution and modification for both. Their exact
hashes and dimensions are recorded in `assets-src/branding/manifest.json`.
Editable GIMP originals have not yet been supplied in this repository.

Original credits retained from Etched:

- Moonflower Studio, original project;
- Ocelot and Jackson, development;
- Farcr, art;
- AstraZoey, sound design.

Additional translation contributions identified in the project history
include Koha for French, Ryo TAGAMI for Japanese, DoltHHaven for Pirate
English, CerealConJugo for Mexican Spanish, SimGitHub5 for Italian, Draacoun
for Brazilian Portuguese, BardinTheDwarf for Russian, unroman for Ukrainian,
and Yizhouuu for Simplified Chinese.

## JLayer 1.0.1

Re-Etched includes JLayer 1.0.1, developed by JavaZOOM, for MP3 decoding.

- Project metadata: http://www.javazoom.net/javalayer/javalayer.html
- Maven artifact: https://repo1.maven.org/maven2/javazoom/jlayer/1.0.1/
- Original source archive: https://repo1.maven.org/maven2/javazoom/jlayer/1.0.1/jlayer-1.0.1-sources.jar

JLayer source files designate the GNU Library General Public License version 2
or, at the recipient's option, any later version. This distribution exercises
that option under version 2.1 of the GNU Lesser General Public License. A copy
of that license is provided at `META-INF/licenses/LGPL-2.1.txt`.

The Re-Etched build relocates the distributed JLayer bytecode from the
`javazoom` namespace to `gg.moonflower.etched.javazoom`. No manual changes are
made to upstream JLayer source files. The build verifies the JLayer binary
against SHA-256
`850508c837454a1b06017c32a36876fae516de1e89a829f725fee1e6dcc52000`.
The build script and exact Re-Etched source corresponding to a release are
available from that release's Git tag. The original JLayer source archive is
available from the Maven repository linked above with SHA-256
`ecde410fc8940ab5d8d5a1d5c585870a3a194f4001701e66a27b8dd8cb7b75ce`.

JLayer source preserves additional upstream notices, including:

```text
Copyright (C) 1993, 1994 Tobias Bading
Copyright (c) 1991 MPEG/audio software simulation group,
All Rights Reserved
```

The `huffcodetab.java` source also retains a historical statement that the 1991
simulation code was not for public distribution until verified and approved by
the MPEG/audio committee. That statement appears alongside JLayer's later GNU
Library General Public License notice. Re-Etched does not attempt to resolve
those upstream notices.

## Gradle Wrapper

The repository includes the Gradle Wrapper bootstrap JAR from Gradle 7.0.2.
Gradle is developed by Gradle, Inc. and contributors and is distributed under
the Apache License 2.0. A copy is provided at
`META-INF/licenses/Apache-2.0.txt`. The wrapper downloads Gradle 8.11.1 and
verifies its distribution SHA-256 before use.

- Project: https://github.com/gradle/gradle
- Wrapper JAR SHA-256: `e996d452d2645e70c01c11143ca2d3742734a28da2bf61f25c82bdc288c9e637`
