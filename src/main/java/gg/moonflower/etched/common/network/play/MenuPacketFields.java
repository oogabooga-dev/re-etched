package gg.moonflower.etched.common.network.play;

import gg.moonflower.etched.common.network.EtchedProtocol;

import java.util.Objects;

final class MenuPacketFields {

    private MenuPacketFields() {
    }

    static int requireContainerId(int containerId) {
        if (containerId < 0 || containerId > EtchedProtocol.MAX_MENU_CONTAINER_ID) {
            throw new IllegalArgumentException("Invalid menu container ID: " + containerId);
        }
        return containerId;
    }

    static String requireBounded(String value, int maxLength, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        }
        return value;
    }
}
