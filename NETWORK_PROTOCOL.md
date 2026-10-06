# Re-Etched Protocol 5

Status: frozen on 2026-10-06 after authoritative jukebox/boombox lifecycle,
persisted-world reopen and actual Forge channel registration verification.
Target: Minecraft 1.20.1, Forge 47.4.10, Java 17.

## Compatibility policy

- Channel: `etched:play`; protocol epoch: the exact string `"5"`.
- Both channel negotiation and Forge display checks require that exact epoch.
  Etched 3.x, Re-Etched 4.x, vanilla/absent peers, semantic version strings and
  future epochs are rejected. The artifact version is not the protocol epoch.
- Pre-freeze development builds that also advertised `"5"` are not compatible
  targets. Negotiation cannot distinguish them; clients and server must use
  matched post-freeze builds/releases.
- IDs, directions, field order, discriminators and accepted limits below are
  frozen. Incompatible changes require a new protocol epoch, not silent changes
  under `"5"`. ID `1` remains reserved and unregistered throughout this epoch.
- Old worlds/NBT layouts and the removed Java playback/provider API are not
  migrated or restored by this contract. This is not a release-readiness claim.

## Channel frames

Forge's indexed channel encoding starts with one unsigned-byte message ID,
followed by the fields listed in order below. This describes the inner channel
frame, not Minecraft's outer custom-payload framing, compression or encryption.

`VarInt` is Minecraft's signed 32-bit variable-length integer encoding. Limits
below restrict decoded values; no additional canonical-VarInt rule is imposed.
`Long` is a signed big-endian 64-bit value. `BlockPos` is Minecraft's packed
64-bit position. `UUID` is two big-endian 64-bit values (most significant first).
`Byte` below is interpreted unsigned. `String(N)` is a VarInt UTF-8 byte length
followed by bytes, bounded by `N` Java UTF-16 code units and `3*N` encoded bytes.

| ID | Packet | Direction | Ordered payload fields |
| --- | --- | --- | --- |
| 0 | `ClientboundEtchingUrlErrorPacket` | Server → client | container VarInt, message String(1024) |
| 1 | Retired entity sound packet | None | Reserved; no registration or compatibility tombstone |
| 2 | `ClientboundPlayMusicPacket` | Server → client | dimension String(256), BlockPos, item ID VarInt, revision Long, program-presence Byte, optional AudioProgram |
| 3 | `ClientboundRadioMenuInitPacket` | Server → client | container VarInt, URL String(8192) |
| 4 | `ServerboundSetEtchingUrlPacket` | Client → server | container VarInt, URL String(8192) |
| 5 | `ServerboundEditMusicLabelPacket` | Client → server | inventory slot VarInt, author String(128), title String(128) |
| 6 | `ServerboundSetRadioUrlPacket` | Client → server | container VarInt, URL String(8192) |
| 7 | `ClientboundBoomboxStatePacket` | Server → client | dimension String(256), entity ID VarInt, owner UUID, PlaybackState |

Container IDs are `0..100`. Label slots are `0..8` or offhand `40`; other inventory
slots are rejected. Menu strings use vanilla bounded FriendlyByteBuf UTF encoding.
Empty radio URL means stop; an empty etching URL invalidates its current request.
The server checks the current menu/container (or the label item at the requested
allowed inventory slot for ID 5) before applying commands; a valid wire payload
alone is not permission to mutate state.
Radio submissions are also revalidated server-side.

### Jukebox payload (ID 2)

- Dimension must parse as a resource location with a nonempty path.
- Item discriminator must be nonnegative. `0` means stop and forbids a program.
  Other values identify the connection's synchronized item registry, not a
  stable cross-installation numeric registry ID.
- Presence is exactly `0` or `1`; all other byte values are rejected.
- A present program must be finite. A nonzero item with no program is an
  unsupported replacement that retires the adapter's previous session.
- No ItemStack, inventory NBT, album metadata, cover URL or seek position is sent.

### Boombox payload (ID 7)

- Dimension has the same resource-location validation; entity ID is nonnegative.
- The UUID identifies the owner independently of the runtime entity ID.
- Only enabled finite content or an empty disabled stop is accepted. With the
  state encoding below, the only legal flag bytes for ID 7 are `0` and `3`.
- No ItemStack/NBT, legacy entity action, provider registration or seek position
  is sent. Held/dropped source eligibility remains a client-side admission guard.

## Shared bounded codecs

Dimensions and AudioProgram strings use strict UTF-8 decoding/encoding; malformed
Unicode is rejected. Menu strings retain vanilla FriendlyByteBuf behavior.

### AudioProgram

1. Kind Byte: `0` finite, `1` live; other values rejected.
2. Track count VarInt: `1..100`.
3. For each track in order:
   - source type Byte: `0` sound event, `1` remote; other values rejected;
   - source String(256) for a sound event, String(8192) for remote;
   - artist String(128);
   - title String(128).

Aggregate source/artist/title text is at most 65,536 UTF-16 code units, after
source normalization. Sound events are parsed/canonicalized resource locations.
Remote sources must be nonempty absolute HTTP(S) URLs with a host, no userinfo,
and a valid optional port. Network destination safety is checked again during
resolution; successful decoding does not authorize private destinations.

A live program contains exactly one remote track. The shared codec supports live
content, but IDs 2 and 7 explicitly reject it. Program kind/source IDs are explicit
wire constants, not Java enum ordinals. No NBT schema version prefixes this codec.

### PlaybackState

1. Revision Long.
2. Flags Byte: bit `0` means program present; bit `1` means enabled.
3. AudioProgram if bit `0` is set.

Unknown bits are rejected. Enabled without a program (`2`) is invalid. The shared
codec accepts empty disabled (`0`), retained disabled program (`1`), and enabled
program (`3`); ID 7 additionally rejects `1` and live content.

Radio block-entity synchronization uses its versioned NBT state through Minecraft
block/entity updates, not an additional `etched:play` message ID. The shared codec
does not imply a registered radio playback-state packet.

## Authoritative lifecycle and admission

Jukebox/boombox revisions come from the persisted overworld
`etched_playback_clock` SavedData allocator shared across dimensions. Ordering is
wrap-safe: a candidate is newer when it differs and signed `candidate-current`
is positive. Comparisons assume revisions are less than half the 64-bit range
apart. Stops do not allocate client revisions.

Jukebox first-party starts require an ordered vanilla `1010` event ticket, current
`HAS_RECORD`, matching dimension/connection/world and a newer revision. Tracking
snapshots use the same gate. Invalidated tickets retain send-order slots until
their corresponding packets arrive. Native third-party discs retain native
playback; managed retirement closes only its exact accepted adapter session.

Boombox state requires matching dimension/connection and entity UUID/incarnation,
with bounded waiting for spawn/equipment data. Source observations do not create
playback intent. Directed tracking snapshots get fresh server revisions without
restarting server jukebox duration or other listeners. Finite playback starts
from the beginning; time-position synchronization is not part of protocol 5.

Adapters retain at most 256 active/pending revision watermarks and compress retired
owners into a wrap-safe scalar floor, without TTL/LRU stale resurrection. This may
conservatively reject an unseen delayed publication; recovery requires a fresh
server snapshot. Radio's independent per-owner revisions do not use these floors.

## Contract verification

- `EtchedProtocolTest`: literal channel/epoch/IDs/directions and negotiation checks.
- `EtchedWireContractTest`: ten independent literal channel frames, fixed limits,
  truncated/oversized/legacy layouts, flags and finite-owner constraints.
- `PlaybackStatePacketCodecTest` and owner packet tests: shared codec literals,
  malformed Unicode, model invariants and aggregate limits.
- `NetworkRegistrationGameTests`: the actual initialized Forge channel's complete
  ID/type set, directions and registered encoders/decoders, not a substitute channel.
- Client smoke: owner tracking/dimension lifecycle and real save/close/reopen,
  persisted clock, restored content and old-packet challenges.

The freeze follows 647-test JUnit/build verification and the preceding 40/40
GameTest, two complete client-smoke and dedicated startup/shutdown runs. No wire or
registration behavior changed in the freeze slice. Two-client dedicated playback,
live providers, exhaustive reload/SoundEngine restart and independent resource
replacement remain separate release checks/work; they are not implied by freezing
the inner channel contract.
