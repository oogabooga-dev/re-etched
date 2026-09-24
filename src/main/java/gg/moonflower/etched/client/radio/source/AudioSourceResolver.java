package gg.moonflower.etched.client.radio.source;

import java.net.URI;

/**
 * Resolves a remote station or ordered service album. Each track opens its own
 * stream through the supplied context when requested for playback.
 */
public interface AudioSourceResolver {

    boolean supports(URI input);

    RadioSourceProgram resolveProgram(URI input, RadioResolveContext context) throws RadioSourceException;
}
