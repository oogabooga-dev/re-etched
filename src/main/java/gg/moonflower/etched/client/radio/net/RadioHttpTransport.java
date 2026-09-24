package gg.moonflower.etched.client.radio.net;

import gg.moonflower.etched.client.radio.AudioCancellation;

public interface RadioHttpTransport {

    RadioHttpResponse execute(RadioHttpRequest request, AudioCancellation cancellation)
            throws RadioTransportException;
}
