# Re-Etched 4.3.1 for Forge 1.20.1

Re-Etched is an unofficial, community-maintained fork of
[Etched 3.0.4](https://github.com/jacksonhardaway/etched). It is not affiliated
with or endorsed by Moonflower Studio.

## Highlights

- Fixes whole-client freezes caused by stalled or slow radio streams when
  Minecraft starts another sound, including menu clicks.
- Decodes online audio in the background and feeds the sound engine from a
  bounded PCM buffer. Temporary underruns produce short silence; playback
  resumes when data arrives. Stopping playback does not wait for the network.
- Applies to the shared radio and online-disc pipeline. Retains SoundCloud
  AAC-LC support and the existing 4.x network/NBT compatibility contract.

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
any older Re-Etched JAR before adding `re-etched-4.3.1.jar` to the client and
server `mods` directories. Etched and Re-Etched cannot be installed together
because both use the `etched` mod ID.

Playback and history changes take effect on updated clients. SoundCloud
metadata lookup at the etching table runs on the server and requires updating
the server too.

## Known Limitations

- AAC/HLS is supported only for clear, finite SoundCloud AAC-LC fMP4 media
  playlists. Live streams, encryption, master playlists, byte ranges,
  discontinuities, HE-AAC/AAC+, and general direct AAC/HLS URLs are unsupported.
- Limits: 4096 segments, 6 hours, 512 MiB per track, 1 MiB initialization,
  4 MiB and 60 seconds per segment, at most 5 redirects per media request.
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

See the full [changelog](https://github.com/oogabooga-dev/re-etched/blob/v4.3.1/CHANGELOG.md)
and [release source](https://github.com/oogabooga-dev/re-etched/tree/v4.3.1).
