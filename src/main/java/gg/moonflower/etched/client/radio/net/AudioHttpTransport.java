package gg.moonflower.etched.client.radio.net;

import gg.moonflower.etched.client.radio.AudioCancellation;

/**
 * Executes bounded HTTP requests. A successful call transfers response ownership to
 * the caller; cancellation can still close the active exchange at any time.
 */
public interface AudioHttpTransport {

    AudioHttpResponse execute(AudioHttpRequest request, AudioCancellation cancellation)
            throws RadioTransportException;
}
