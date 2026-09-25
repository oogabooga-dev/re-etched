package gg.moonflower.etched.client.radio.source;

import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.net.AudioHttpTransport;
import gg.moonflower.etched.client.radio.net.AudioNetworkPolicy;
import gg.moonflower.etched.client.radio.net.DefaultRadioNetworkPolicy;
import gg.moonflower.etched.client.radio.net.RadioHttpTransportImpl;
import gg.moonflower.etched.core.Etched;
import net.minecraft.client.Minecraft;

import java.util.Objects;

/** Shared cancellation, transport, policy, limits, and budget for one resolution operation. */
public record AudioResolveContext(AudioHttpTransport transport, AudioNetworkPolicy networkPolicy,
                                   AudioCancellation cancellation, AudioResolveLimits limits,
                                   AudioResolutionBudget budget) {

    public AudioResolveContext(AudioHttpTransport transport, AudioNetworkPolicy networkPolicy,
                                AudioCancellation cancellation, AudioResolveLimits limits) {
        this(transport, networkPolicy, cancellation, limits, new AudioResolutionBudget(limits));
    }

    public AudioResolveContext {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(networkPolicy, "networkPolicy");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(budget, "budget");
    }

    public static AudioResolveContext createDefault(AudioCancellation cancellation) {
        AudioNetworkPolicy networkPolicy = new DefaultRadioNetworkPolicy(
                Etched.CLIENT_CONFIG.allowPrivateNetworkStations::get);
        AudioHttpTransport transport = new RadioHttpTransportImpl(
                Minecraft.getInstance().getProxy(), networkPolicy,
                RadioHttpTransportImpl.DEFAULT_CONNECT_TIMEOUT,
                RadioHttpTransportImpl.DEFAULT_READ_TIMEOUT,
                RadioHttpTransportImpl.DEFAULT_MAX_REDIRECTS);
        return new AudioResolveContext(transport, networkPolicy, cancellation, AudioResolveLimits.DEFAULT);
    }
}
