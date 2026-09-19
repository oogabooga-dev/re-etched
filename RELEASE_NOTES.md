# Re-Etched 4.1.0 for Forge 1.20.1

Re-Etched is an unofficial, community-maintained fork of
[Etched 3.0.4](https://github.com/jacksonhardaway/etched). It is not affiliated
with or endorsed by Moonflower Studio.

## Highlights

- Added a client-local history of the 20 most recently accepted stations for
  each singleplayer world or multiplayer server.
- Added a five-row radio history list with mouse-wheel and scrollbar navigation,
  keyboard selection, URL tooltips, and per-context clearing.
- Added bounded, versioned JSON persistence with validation, atomic replacement,
  backup recovery, and hashed context identifiers.
- Records a station only after a matching block update confirms server
  acceptance. Selecting history fills the editor without starting playback.
- Preserved the Etched 3.0.4 mod ID, network protocol, registry namespace, and
  radio NBT contract for mixed client/server compatibility.

## Requirements

- Minecraft 1.20.1
- Forge 47.x; Forge 47.4.10 is the tested and recommended version
- Java 17
- Recommended deployment: the same Re-Etched version on the client and server

Mixed Re-Etched and original Etched 3.0.4 client/server deployments are a
compatibility target, but using the same Re-Etched version on both sides is the
recommended configuration.

## Installation

Back up the world before replacing Etched. Remove the original Etched JAR and
any older Re-Etched JAR before adding `re-etched-4.1.0.jar` to the client and
server `mods` directories. Etched and Re-Etched cannot be installed together
because both use the `etched` mod ID.

## Known Limitations

- AAC, AAC+, and HLS streams are not supported.
- SoundCloud and Bandcamp support depends on external service formats.
- Radio inputs must be absolute HTTP or HTTPS URLs.
- Downgrading to original Etched may discard Re-Etched's stopped-radio state.
- Recent station URLs are stored in plaintext in the client's local history
  file because they must remain reusable.

See the full [changelog](https://github.com/oogabooga-dev/re-etched/blob/v4.1.0/CHANGELOG.md)
and [release source](https://github.com/oogabooga-dev/re-etched/tree/v4.1.0).
