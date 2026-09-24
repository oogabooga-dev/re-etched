package gg.moonflower.etched.client.radio.net;

import gg.moonflower.etched.client.radio.AudioCancellation;

import java.net.URI;

@FunctionalInterface
public interface RadioNetworkPolicy {

    void check(URI uri) throws RadioTransportException;

    /**
     * Blocking implementations should override this method to bound their own work.
     */
    default void check(URI uri, AudioCancellation cancellation) throws RadioTransportException {
        cancellation.throwIfCancelled();
        this.check(uri);
        cancellation.throwIfCancelled();
    }
}
