package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.RadioFailure;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompositeRadioSourceResolverTest {

    private static final URI INPUT = URI.create("https://provider.example/item");

    @Test
    void dispatchesToTheFirstSupportingProviderDeterministically() throws Exception {
        List<String> calls = new ArrayList<>();
        RadioSourceProgram providerProgram = program("provider");
        RadioSourceProgram fallbackProgram = program("direct");
        TrackingResolver provider = new TrackingResolver("provider", true, providerProgram, calls);
        TrackingResolver direct = new TrackingResolver("direct", true, fallbackProgram, calls);
        CompositeRadioSourceResolver composite = new CompositeRadioSourceResolver(
                List.of(provider, direct));

        RadioSourceProgram resolved = composite.resolveProgram(INPUT, nullContext());

        assertSame(providerProgram, resolved);
        assertEquals(List.of("provider:supports", "provider:resolve"), calls);
    }

    @Test
    void reachesDirectFallbackOnlyAfterEarlierProvidersDecline() throws Exception {
        List<String> calls = new ArrayList<>();
        RadioSourceProgram fallbackProgram = program("direct");
        CompositeRadioSourceResolver composite = new CompositeRadioSourceResolver(List.of(
                new TrackingResolver("provider", false, program("provider"), calls),
                new TrackingResolver("direct", true, fallbackProgram, calls)));

        RadioSourceProgram resolved = composite.resolveProgram(INPUT, nullContext());

        assertSame(fallbackProgram, resolved);
        assertEquals(List.of("provider:supports", "direct:supports", "direct:resolve"), calls);
    }

    @Test
    void reportsAnInvalidUrlWhenNoResolverSupportsTheInput() {
        CompositeRadioSourceResolver composite = new CompositeRadioSourceResolver(List.of(
                new TrackingResolver("provider", false, program("provider"), new ArrayList<>())));

        RadioSourceException failure = assertThrows(RadioSourceException.class,
                () -> composite.resolveProgram(INPUT, nullContext()));

        assertEquals(RadioFailure.Code.INVALID_URL, failure.code());
    }

    private static RadioSourceProgram program(String name) {
        URI uri = URI.create("https://" + name + ".example/item");
        return new RadioSourceProgram(RadioSourceProgram.Kind.STATION, uri, List.of(
                new RadioSourceProgram.Track(uri, name, context -> {
                    throw new AssertionError("Track should not be opened by this test");
                })));
    }

    private static AudioResolveContext nullContext() {
        return new AudioResolveContext(
                (request, cancellation) -> {
                    throw new AssertionError("Transport should not be used by this test");
                }, uri -> {
                }, new gg.moonflower.etched.client.radio.AudioCancellation(),
                new AudioResolveLimits(4, 64, 2, 32, 1, 4));
    }

    private static final class TrackingResolver implements AudioSourceResolver {

        private final String name;
        private final boolean supported;
        private final RadioSourceProgram program;
        private final List<String> calls;

        private TrackingResolver(String name, boolean supported, RadioSourceProgram program,
                                 List<String> calls) {
            this.name = name;
            this.supported = supported;
            this.program = program;
            this.calls = calls;
        }

        @Override
        public boolean supports(URI input) {
            this.calls.add(this.name + ":supports");
            return this.supported;
        }

        @Override
        public RadioSourceProgram resolveProgram(URI input, AudioResolveContext context) {
            this.calls.add(this.name + ":resolve");
            return this.program;
        }
    }
}
