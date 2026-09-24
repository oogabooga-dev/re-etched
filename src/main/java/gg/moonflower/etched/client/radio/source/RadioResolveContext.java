package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.net.AudioHttpTransport;
import gg.moonflower.etched.client.radio.net.DefaultRadioNetworkPolicy;
import gg.moonflower.etched.client.radio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.client.radio.net.RadioNetworkPolicy;
import gg.moonflower.etched.core.Etched;
import net.minecraft.client.Minecraft;

import java.util.Objects;

public record RadioResolveContext(AudioHttpTransport transport, RadioNetworkPolicy networkPolicy,
                                  AudioCancellation cancellation, RadioResolveLimits limits,
                                  RadioResolutionBudget budget) {

    public RadioResolveContext(AudioHttpTransport transport, RadioNetworkPolicy networkPolicy,
                               AudioCancellation cancellation, RadioResolveLimits limits) {
        this(transport, networkPolicy, cancellation, limits, new RadioResolutionBudget(limits));
    }

    public RadioResolveContext {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(networkPolicy, "networkPolicy");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(budget, "budget");
    }

    public static RadioResolveContext createDefault(AudioCancellation cancellation) {
        RadioNetworkPolicy networkPolicy = new DefaultRadioNetworkPolicy(
                Etched.CLIENT_CONFIG.allowPrivateNetworkStations::get);
        AudioHttpTransport transport = new RadioHttpTransportImpl(
                Minecraft.getInstance().getProxy(), networkPolicy,
                RadioHttpTransportImpl.DEFAULT_CONNECT_TIMEOUT,
                RadioHttpTransportImpl.DEFAULT_READ_TIMEOUT,
                RadioHttpTransportImpl.DEFAULT_MAX_REDIRECTS);
        return new RadioResolveContext(transport, networkPolicy, cancellation, RadioResolveLimits.DEFAULT);
    }
}
