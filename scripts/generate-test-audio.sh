#!/usr/bin/env bash
set -euo pipefail

# These fixtures contain generated sine waves only; no recorded audio is used.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${ROOT_DIR}/src/test/resources/gg/moonflower/etched/client/radio/audio"
FFMPEG="${FFMPEG:-ffmpeg}"

mkdir -p "${OUTPUT_DIR}"

"${FFMPEG}" -hide_banner -loglevel error -y \
  -f lavfi -i "sine=frequency=440:duration=0.15:sample_rate=22050" \
  -map_metadata -1 -ac 1 -c:a libmp3lame -b:a 64k \
  "${OUTPUT_DIR}/mono.mp3"

"${FFMPEG}" -hide_banner -loglevel error -y \
  -f lavfi -i "sine=frequency=660:duration=0.25:sample_rate=44100" \
  -map_metadata -1 -metadata title="Re-Etched synthetic VBR test" \
  -id3v2_version 4 -ac 2 -c:a libmp3lame -q:a 5 \
  "${OUTPUT_DIR}/stereo-vbr-id3.mp3"

"${FFMPEG}" -hide_banner -loglevel error -y \
  -f lavfi -i "sine=frequency=330:duration=5.5:sample_rate=44100" \
  -map_metadata -1 -ac 2 -c:a libmp3lame -b:a 128k \
  "${OUTPUT_DIR}/stereo-long.mp3"

"${FFMPEG}" -hide_banner -loglevel error -y \
  -f lavfi -i "sine=frequency=550:duration=0.2:sample_rate=32000" \
  -map_metadata -1 -ac 2 -c:a libvorbis -q:a 4 \
  "${OUTPUT_DIR}/stereo.ogg"

sha256sum \
  "${OUTPUT_DIR}/mono.mp3" \
  "${OUTPUT_DIR}/stereo-vbr-id3.mp3" \
  "${OUTPUT_DIR}/stereo-long.mp3" \
  "${OUTPUT_DIR}/stereo.ogg"

# Native SoundEngine stall regression: 64 KiB is only ~2 s at 256 kbit/s.
mkdir -p "${ROOT_DIR}/src/clientSmoke/resources"
"${FFMPEG}" -hide_banner -loglevel error -y \
  -f lavfi -i "sine=frequency=440:duration=8:sample_rate=44100" \
  -map_metadata -1 -ac 2 -c:a libmp3lame -b:a 256k \
  "${ROOT_DIR}/src/clientSmoke/resources/stall-test.mp3"
