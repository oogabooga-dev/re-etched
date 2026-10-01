package gg.moonflower.etched.common.audio;

import java.io.IOException;

/** Shared content fixtures for prefix inspection and server-side etching tests. */
public final class TestAudioContent {

    private TestAudioContent() {
    }

    public static byte[] mpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64};
    }

    public static byte[] taggedMpeg(int size) {
        byte[] result = new byte[10 + size + 4];
        result[0] = 'I';
        result[1] = 'D';
        result[2] = '3';
        result[3] = 4;
        for (int i = 9, remaining = size; i >= 6; i--, remaining >>>= 7) {
            result[i] = (byte) (remaining & 0x7F);
        }
        System.arraycopy(mpeg(), 0, result, 10 + size, 4);
        return result;
    }

    public static byte[] fixture(String name) throws IOException {
        try (var stream = TestAudioContent.class.getResourceAsStream(
                "/gg/moonflower/etched/client/radio/audio/" + name)) {
            if (stream == null) {
                throw new IOException("Missing audio fixture: " + name);
            }
            return stream.readAllBytes();
        }
    }
}
