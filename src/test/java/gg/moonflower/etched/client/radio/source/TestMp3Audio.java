package gg.moonflower.etched.client.radio.source;

import java.nio.charset.StandardCharsets;

/** Small resolver fixtures: a structurally valid MPEG header, optionally after an ID3v2.4 tag. */
public final class TestMp3Audio {

    private static final byte[] FRAME = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64};

    private TestMp3Audio() {
    }

    public static byte[] frame(String marker) {
        byte[] text = marker.getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[FRAME.length + text.length];
        System.arraycopy(FRAME, 0, result, 0, FRAME.length);
        System.arraycopy(text, 0, result, FRAME.length, text.length);
        return result;
    }

    static byte[] tagged(String marker) {
        byte[] text = marker.getBytes(StandardCharsets.US_ASCII);
        if (text.length > 127) {
            throw new IllegalArgumentException("Test tag must fit in one synchsafe byte");
        }
        byte[] result = new byte[10 + text.length + FRAME.length];
        result[0] = 'I';
        result[1] = 'D';
        result[2] = '3';
        result[3] = 4;
        result[9] = (byte) text.length;
        System.arraycopy(text, 0, result, 10, text.length);
        System.arraycopy(FRAME, 0, result, 10 + text.length, FRAME.length);
        return result;
    }

    static byte[] taggedWithPadding(int size) {
        if (size < 0 || size > 1 << 20) {
            throw new IllegalArgumentException("Invalid test tag size");
        }
        byte[] result = new byte[10 + size + FRAME.length];
        result[0] = 'I';
        result[1] = 'D';
        result[2] = '3';
        result[3] = 4;
        for (int i = 9, remaining = size; i >= 6; i--, remaining >>>= 7) {
            result[i] = (byte) (remaining & 0x7F);
        }
        System.arraycopy(FRAME, 0, result, 10 + size, FRAME.length);
        return result;
    }
}
