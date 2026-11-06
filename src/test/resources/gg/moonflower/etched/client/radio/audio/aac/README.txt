Synthetic AAC-LC stereo fixture, generated from a 440 Hz sine (no third-party music).
Command (from repository root, using FFmpeg's native AAC encoder):
ffmpeg -f lavfi -i sine=frequency=440:sample_rate=44100:duration=2 -ac 2 -c:a aac -b:a 160k -f hls -hls_time 1 -hls_playlist_type vod -hls_segment_type fmp4 -hls_fmp4_init_filename init.mp4 -hls_segment_filename src/test/resources/gg/moonflower/etched/client/radio/audio/aac/segment%d.m4s src/test/resources/gg/moonflower/etched/client/radio/audio/aac/playlist.m3u8
88 AAC frames including encoder priming/padding. Test PCM length: 88 * 1024 samples/channel.
Fixtures are test-only and are not packaged in the mod JAR.
