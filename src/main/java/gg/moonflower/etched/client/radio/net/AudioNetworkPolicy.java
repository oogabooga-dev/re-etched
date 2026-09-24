package gg.moonflower.etched.client.radio.net;

import gg.moonflower.etched.client.radio.AudioCancellation;

import java.net.URI;

/** Checks each HTTP destination, including every redirect, before connecting. */
@FunctionalInterface
public interface AudioNetworkPolicy {

    void check(URI uri) throws RadioTransportException;

    /**
     * Blocking implementations should override this method to bound their own work
     * while preserving cancellation and destination validation.
     */
    default void check(URI uri, AudioCancellation cancellation) throws RadioTransportException {
        cancellation.throwIfCancelled();
        this.check(uri);
        cancellation.throwIfCancelled();
    }
}
