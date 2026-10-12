# Re-Etched 4.2.0 for Forge 1.20.1

Re-Etched is an unofficial, community-maintained fork of
[Etched 3.0.4](https://github.com/jacksonhardaway/etched). It is not affiliated
with or endorsed by Moonflower Studio.

## Highlights

- Improved online-disc playback in boomboxes and album jukeboxes by reusing the
  radio's buffering, independent audio streams, and corrected MP3/Ogg decoders.
- Connected disc playback to the radio's SoundCloud progressive-MP3 resolver,
  including refreshed client IDs and expired media URLs. Updated SoundCloud
  metadata discovery and playlist entries when recording new discs.
- Fixed cancellation and cleanup when stopping or replacing discs, removing
  their owners, and leaving the world. Failed tracks no longer loop endlessly.
- Added right-click deletion of individual radio-history entries, saved to both
  the history file and its backup. Left-click still fills the URL editor.
- Retained PCM WAV support, vanilla discs, boombox inventory playback, and
  existing album-jukebox controls.
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
any older Re-Etched JAR before adding `re-etched-4.2.0.jar` to the client and
server `mods` directories. Etched and Re-Etched cannot be installed together
because both use the `etched` mod ID.

Playback and history changes take effect on updated clients. SoundCloud
metadata lookup at the etching table runs on the server and requires updating
the server too.

## Known Limitations

- AAC, AAC+, and HLS streams are not supported.
- SoundCloud tracks must expose progressive MP3; HLS-only tracks cannot play.
- SoundCloud and Bandcamp support depends on external service formats.
- Up to eight online disc streams can be active per client, separately from
  radio sessions. Failed online tracks stop; toggle playback or select a track
  again to retry.
- Online discs now follow radio's private-network setting and destination
  checks. Local sound events and third-party download adapters use their
  existing loading paths.
- Radio inputs must be absolute HTTP or HTTPS URLs.
- Downgrading to original Etched may discard Re-Etched's stopped-radio state.
- Recent station URLs are stored in plaintext in the client's local history
  file because they must remain reusable.

See the full [changelog](https://github.com/oogabooga-dev/re-etched/blob/v4.2.0/CHANGELOG.md)
and [release source](https://github.com/oogabooga-dev/re-etched/tree/v4.2.0).
