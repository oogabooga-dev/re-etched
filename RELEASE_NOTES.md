# Re-Etched 4.0.0 for Forge 1.20.1

Re-Etched is an unofficial, community-maintained fork of
[Etched 3.0.4](https://github.com/jacksonhardaway/etched). It is not affiliated
with or endorsed by Moonflower Studio.

## Highlights

- Replaced the legacy radio download path with cancellable per-radio streams.
- Added direct MP3 and Ogg/Vorbis playback, M3U and PLS playlists, SoundCloud
  progressive MP3 resolution, Bandcamp `mp3-128` resolution, and ICY metadata.
- Added bounded resolution, buffering, reconnect behavior, and private-network
  destination controls.
- Preserved the Etched 3.0.4 mod ID, network protocol, registry namespace, and
  active-radio state for compatibility.
- Kept potentially blocking network and audio cleanup off the Minecraft client
  thread and ensured stopping playback releases its upstream connection.
- Synchronized Play and Stop controls with actual radio state and retained the
  last known station when connected to an Etched 3.0.4 server.

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
any older Re-Etched JAR before adding `re-etched-4.0.0.jar` to the client and
server `mods` directories. Etched and Re-Etched cannot be installed together
because both use the `etched` mod ID.

## Known Limitations

- AAC, AAC+, and HLS streams are not supported.
- SoundCloud and Bandcamp support depends on external service formats.
- Radio inputs must be absolute HTTP or HTTPS URLs.
- Downgrading to original Etched may discard Re-Etched's stopped-radio state.

See the full [changelog](https://github.com/oogabooga-dev/re-etched/blob/v4.0.0/CHANGELOG.md)
and [release source](https://github.com/oogabooga-dev/re-etched/tree/v4.0.0).
