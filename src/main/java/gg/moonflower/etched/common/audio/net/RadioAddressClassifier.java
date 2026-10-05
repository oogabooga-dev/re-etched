package gg.moonflower.etched.common.audio.net;

import java.net.InetAddress;

final class RadioAddressClassifier {

    private RadioAddressClassifier() {
    }

    static Disposition classify(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return classifyIpv4(bytes);
        }
        if (bytes.length != 16) {
            return Disposition.FORBIDDEN;
        }

        if (isIpv4Mapped(bytes)) {
            return classifyIpv4(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
        }
        if (address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress()) {
            return Disposition.FORBIDDEN;
        }
        if (address.isLoopbackAddress()) {
            return Disposition.PRIVATE;
        }
        if (isIpv4Compatible(bytes) || isAwsIpv6MetadataAddress(bytes)) {
            return Disposition.FORBIDDEN;
        }
        if (address.isSiteLocalAddress()
                || (unsigned(bytes[0]) & 0xFE) == 0xFC) {
            return Disposition.PRIVATE;
        }
        if (unsigned(bytes[0]) == 0x20 && unsigned(bytes[1]) == 0x01
                && unsigned(bytes[2]) == 0x0D && unsigned(bytes[3]) == 0xB8) {
            return Disposition.FORBIDDEN;
        }
        return Disposition.PUBLIC;
    }

    private static Disposition classifyIpv4(byte[] bytes) {
        int first = unsigned(bytes[0]);
        int second = unsigned(bytes[1]);
        int third = unsigned(bytes[2]);

        if (first == 100 && second == 100 && third == 100 && unsigned(bytes[3]) == 200) {
            return Disposition.FORBIDDEN;
        }
        if (first == 10
                || first == 100 && second >= 64 && second <= 127
                || first == 127
                || first == 172 && second >= 16 && second <= 31
                || first == 192 && second == 168) {
            return Disposition.PRIVATE;
        }
        if (first == 0
                || first == 169 && second == 254
                || first == 192 && second == 0 && (third == 0 || third == 2)
                || first == 198 && (second == 18 || second == 19)
                || first == 198 && second == 51 && third == 100
                || first == 203 && second == 0 && third == 113
                || first >= 224) {
            return Disposition.FORBIDDEN;
        }
        return Disposition.PUBLIC;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }

    private static boolean isIpv4Compatible(byte[] bytes) {
        for (int i = 0; i < 12; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAwsIpv6MetadataAddress(byte[] bytes) {
        byte[] metadata = {
                (byte) 0xFD, 0x00, 0x0E, (byte) 0xC2, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0x02, 0x54
        };
        return java.util.Arrays.equals(bytes, metadata);
    }

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    enum Disposition {
        PUBLIC,
        PRIVATE,
        FORBIDDEN
    }
}
