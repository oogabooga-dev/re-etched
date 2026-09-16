# Re-Etched

> Re-Etched is an unofficial, community-maintained fork of
> [Etched](https://github.com/jacksonhardaway/etched). It is not affiliated
> with or endorsed by Moonflower Studio.

Re-Etched is based on Etched 3.0.4 for Minecraft 1.20.1. It retains Etched's
game content and compatibility identifiers while replacing the client-side
internet radio path with a bounded, cancellable, and security-hardened
implementation.

Re-Etched has independent versioning beginning with `4.0.0`. The current stable
release is `4.0.0`.

## Requirements

| Component | Requirement |
| --- | --- |
| Minecraft | 1.20.1 only |
| Loader | Forge 47.x |
| Recommended Forge | 47.4.10, the build and release target |
| Java | 17 |
| Environment | Client and dedicated server |

Forge releases other than the tested baseline should be considered unverified.
Fabric and NeoForge are not supported. The production JAR embeds its MP3
decoder, so no separate library mod is required.

## Installation And Migration

1. Back up the world before replacing Etched.
2. Stop Minecraft and the dedicated server.
3. Remove the original Etched JAR and any older Re-Etched JAR.
4. Put `re-etched-4.0.0.jar` in the client and server `mods` directories.
5. Start Minecraft with Java 17 and Forge 47.4.10.

Do not install Etched and Re-Etched in the same Minecraft instance. Both use
the mod ID `etched`, so Forge treats them as duplicate implementations of the
same mod.

Re-Etched preserves the Etched 3.0.4 `etched:play` network channel, protocol
version `3`, packet IDs `0` through `6`, registry namespace, and legacy active
radio `Url` state. A Re-Etched client with an Etched 3.0.4 server, and the
reverse combination, are compatibility targets. No other original Etched
version is claimed compatible.

Existing `Url` values are read by Re-Etched. Re-Etched additionally uses
`StoredUrl` to retain a manually stopped station. Back up before migration and
do not assume that downgrading to the original mod will preserve this new
stopped-state information.

## Radio Sources

The Re-Etched radio accepts absolute HTTP or HTTPS URLs for:

- direct MP3 streams;
- direct Ogg/Vorbis streams;
- M3U and PLS playlists, including relative entries, nested playlists, and
  ordered fallback stations;
- SoundCloud tracks and playlists when SoundCloud exposes progressive MP3;
- Bandcamp tracks and albums containing `mp3-128` audio;
- ICY streams, including bounded `StreamTitle` metadata when supplied.

SoundCloud and Bandcamp integration depends on external service formats and is
best-effort. Service-side changes may break resolution without a Re-Etched
update. Finite provider track lists play in order once and then stop.

The radio screen provides separate Play, Stop, and Close controls. Stopping
retains the entered station, and replaying an already enabled, unchanged URL
does not restart its session.

## Network Security And Privacy

Radio audio is fetched by each client. Joining a server with configured radios
can therefore cause the client to make outbound requests to third-party hosts,
either directly or through Minecraft's configured proxy.

Re-Etched applies the following controls:

- only syntactically valid absolute HTTP(S) URLs without embedded credentials
  are accepted;
- radio submissions are revalidated by the server after confirming that the
  player still has a valid radio menu open;
- DNS results, redirects, playlist entries, and provider media URLs are
  checked before use;
- private and loopback destinations are disabled by default;
- mixed public/private DNS answers and selected special-purpose destinations are
  rejected;
- the client-only `Allow Private Network Stations` option can enable private
  and loopback destinations, but should only be used with trusted servers;
- requests use Minecraft's configured proxy, explicit timeouts, and bounded
  redirects;
- playlist and provider response bodies, resolution work, streaming buffers,
  worker queues, and reconnect delays are bounded;
- active work is cancelled when playback stops or the world unloads.

Default limits include a 5-second DNS timeout, 10-second connection timeout,
15-second read timeout, five redirects, 256 KiB playlist or provider bodies,
100 aggregate playlist entries, three levels of playlist nesting, and 128
resolution steps.

These controls reduce request-forgery risk but cannot eliminate it. The checked
DNS address cannot be pinned through `HttpURLConnection`, leaving a DNS
rebinding window between validation and connection. A proxy that resolves
hostnames remotely must enforce an equivalent destination policy itself.

## Known Limitations

- AAC, AAC+, and HLS streams are not supported.
- Radio inputs are limited to absolute HTTP(S) URLs.
- SoundCloud, Bandcamp, and station availability are outside the project's
  control.
- World migration and downgrade behavior still require caution; keep backups.
- Only Minecraft 1.20.1, Forge, and Java 17 are supported.

Report reproducible problems at the
[Re-Etched issue tracker](https://github.com/oogabooga-dev/re-etched/issues).

## Building

Install JDK 17 and use the checked-in Gradle wrapper:

```bash
./gradlew test
./gradlew build
```

The distributable is:

```text
build/libs/re-etched-4.0.0.jar
```

Do not distribute the `-dev.jar` or `-dev-shadow.jar` intermediates.

## Attribution And Licensing

Etched was originally developed by Moonflower Studio. Original credits
retained by the project are:

- Ocelot;
- Jackson;
- Farcr, art;
- AstraZoey, sound design.

Inherited translation contributors identified in project history include
Koha, Ryo TAGAMI, DoltHHaven, CerealConJugo, SimGitHub5, Draacoun,
BardinTheDwarf, unroman, and Yizhouuu.

Re-Etched modifications are maintained by
[oogabooga-dev](https://github.com/oogabooga-dev).

Except where separately identified, the code is distributed under
GPL-3.0-only. The upstream license names Etched's former module resource
directories as reserved; those resources were later consolidated under
`src/main/resources`. Re-Etched conservatively treats inherited resources as
reserved while their status is clarified and does not claim ownership of them.
See [LICENSE](LICENSE) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
