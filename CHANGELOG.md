# Changelog

Notable Re-Etched changes are documented here. Re-Etched uses independent
versioning beginning with version 4.0.0 and is based on Etched 3.0.4.

## [4.0.0] - 2026-09-16

### Changed

- Moved potentially blocking radio network and audio resource disposal to
  dedicated daemon cleanup workers.
- Stopped attaching the upstream JLayer source archive to release assets;
  source provenance and checksums remain documented in
  `THIRD_PARTY_NOTICES.md`.

### Fixed

- Ensured stopping a radio cancels its upstream network stream after audio has
  been handed off to the SoundEngine, including streams blocked on reads.
- Ensured HTTP response bodies are still closed when disconnecting the
  underlying connection fails.
- Prevented stale track preparation from taking ownership after cancellation.
- Disabled Play while a radio is starting or active, and disabled Stop while
  it is idle or already stopping.
- Kept Play and Stop controls synchronized with the actual radio state without
  allowing stale updates to override pending actions.
- Preserved the last known radio URL when an Etched 3.0.4 server represents a
  stopped radio with an empty `Url`.

## [4.0.0-beta.1] - 2026-09-12

### Added

- Added a client-owned radio session runtime with explicit resolving,
  connecting, buffering, playing, reconnecting, stopped, and failed states.
- Added direct MP3 and Ogg/Vorbis radio playback.
- Added M3U and PLS parsing with relative URLs, nested playlists, and ordered
  fallback stations.
- Added SoundCloud progressive-MP3 track and playlist resolution.
- Added Bandcamp `mp3-128` track and album resolution.
- Added ICY metadata handling and `StreamTitle` playback overlays.
- Added bounded automatic reconnect behavior for recoverable failures.
- Added Play, Stop, and Close controls, URL validation, and localized status
  and error messages.
- Added offline JUnit coverage for protocol compatibility, source resolution,
  HTTP policy, decoders, cancellation, reconnects, and resource ownership.

### Changed

- Renamed the fork and artifact to Re-Etched while retaining the
  compatibility-sensitive `etched` mod ID and namespace.
- Updated the build and recommended Forge baseline to 47.4.10.
- Fixed compilation output to Java 17.
- Replaced the radio's shared legacy download path with independent,
  cancellable per-radio streams and bounded worker queues.
- Preserved the Etched 3.0.4 `etched:play` channel, protocol version `3`,
  packet IDs `0` through `6`, and legacy URL encoding.
- Added `StoredUrl` while retaining `Url` as the active legacy NBT key.

### Fixed

- Prevented concurrent radios from sharing and closing the same opened stream.
- Prevented stale asynchronous results from an old URL or playback generation
  from taking ownership after replacement.
- Closed or cancelled radio work on URL changes, redstone stop, block removal,
  chunk or world unload, logout, and game shutdown.
- Avoided separate inspection and playback requests for a directly resolved
  stream.
- Kept note particles and nearby-record effects aligned with actual playback.
- Preserved a manually stopped station across reloads.
- Made repeated Play on the same enabled URL a no-op.
- Hardened SoundEngine handoff and cleanup when playback is stopped or
  rejected.

### Security

- Added server-side radio-menu and HTTP(S) URL revalidation.
- Added client-side DNS and destination checks for initial URLs, redirects,
  playlists, and provider-generated media URLs.
- Blocked private and loopback destinations by default and always blocked
  selected special-purpose ranges and mixed public/private DNS answers.
- Added bounded DNS, connection, and read timeouts, redirects, playlist and
  provider response bodies, playlist depth and entries, streaming buffers,
  worker queues, and reconnect delays.
- Added cancellation-aware network ownership and Minecraft proxy support.
- Removed deliberate transmission of Minecraft usernames and UUIDs from the
  new radio request path.

### Known Limitations

- This is a beta release; back up worlds before replacing Etched.
- AAC, AAC+, and HLS are unsupported.
- Radio inputs are limited to absolute HTTP(S) URLs.
- SoundCloud and Bandcamp support depends on external service formats.
- Provider track lists are finite and do not repeat automatically.
- A residual DNS-rebinding window remains between validation and connection.
- Only Minecraft 1.20.1, Forge, and Java 17 are supported.
- Etched and Re-Etched cannot be installed together because both use the
  `etched` mod ID.
