# Re-Etched

Bring online music into Minecraft with custom discs, a portable boombox,
an album jukebox, and an internet radio.

Re-Etched is an unofficial fork of Etched 3.0.4 for Minecraft 1.20.1 Forge.
The 4.x line retains the original content and compatibility identifiers while
improving buffering, cancellation, and online playback.

## Features

- Independent playback: devices can play the same track without sharing a read cursor.
- SoundCloud tracks and playlists, including AAC-LC playback introduced in 4.3.0.
- Bandcamp tracks and albums exposing MP3 audio.
- Radio history: 20 recent stations per world or multiplayer server. Left-click
  an entry to select it; right-click to remove it.
- Stopped radios remember their station. Stopping or replacing a disc cancels
  pending loading.

## Supported audio

- Radio: direct MP3 and Ogg/Vorbis, M3U and PLS playlists, and ICY metadata.
- SoundCloud: progressive MP3 or clear, finite AAC-LC HLS/fMP4 media playlists.
  MP3 is preferred where available; otherwise AAC 160k is selected, or AAC 96k
  when 160k is absent. This works for radios, boomboxes, and album jukeboxes.
- Bandcamp: sources providing `mp3-128` audio.
- Online discs: MP3, Ogg/Vorbis, PCM WAV, and the SoundCloud AAC-LC path above.

The AAC decoder is bundled pure Java JAAD. No separate codec mod or native
library is required.

AAC/HLS support is specific to SoundCloud's finite AAC-LC mono/stereo fMP4
media playlists. General direct AAC/HLS URLs, HE-AAC/AAC+, live or encrypted
HLS, master playlists, byte ranges, and discontinuities are unsupported.
SoundCloud HLS tracks are limited to 6 hours, 4096 segments, and 512 MiB;
segments are limited to 4 MiB and 60 seconds. Provider availability can change.

## Installation

Use Minecraft 1.20.1, Forge 47.x, and Java 17. The build targets Forge 47.4.10.
Install the same Re-Etched version on the client and server for the complete
experience; etching-table SoundCloud metadata lookup runs on the server.

Back up your world before upgrading. Remove the original Etched and any older
Re-Etched JAR before installing the new version. Etched and Re-Etched both use
the `etched` mod ID and cannot be installed together.

The 4.x line preserves Etched 3.0.4 registry identifiers, disc data, and network
protocol. Downgrading may lose Re-Etched's stopped-radio state.

## Credits and links

Based on [Etched](https://github.com/jacksonhardaway/etched) by Moonflower Studio,
with original credits to Ocelot, Jackson, Farcr, and AstraZoey.
Re-Etched is maintained by oogabooga-dev and is not affiliated with or endorsed
by Moonflower Studio.

[4.x source and README](https://github.com/oogabooga-dev/re-etched/tree/4.x) ·
[Issue tracker](https://github.com/oogabooga-dev/re-etched/issues) ·
[Changelog](https://github.com/oogabooga-dev/re-etched/blob/4.x/CHANGELOG.md)
