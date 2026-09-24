package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.RadioFailure;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/** Deterministic provider-first dispatch with direct HTTP(S) fallback. */
public final class CompositeRadioSourceResolver implements AudioSourceResolver {

    private final List<AudioSourceResolver> resolvers;

    public CompositeRadioSourceResolver(List<AudioSourceResolver> resolvers) {
        this.resolvers = List.copyOf(resolvers);
        if (this.resolvers.isEmpty()) {
            throw new IllegalArgumentException("At least one radio source resolver is required");
        }
    }

    @Override
    public boolean supports(URI input) {
        return this.resolvers.stream().anyMatch(resolver -> resolver.supports(input));
    }

    @Override
    public RadioSourceProgram resolveProgram(URI input, AudioResolveContext context)
            throws RadioSourceException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        for (AudioSourceResolver resolver : this.resolvers) {
            if (resolver.supports(input)) {
                return resolver.resolveProgram(input, context);
            }
        }
        throw new RadioSourceException(RadioFailure.Code.INVALID_URL, false,
                "No radio source resolver supports this URL", null);
    }
}
