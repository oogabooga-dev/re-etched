package gg.moonflower.etched.client.radio.net;

import gg.moonflower.etched.client.radio.AudioCancellation;
import gg.moonflower.etched.client.radio.RadioFailure;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Rejects URLs that can access local or special-purpose network resources.
 *
 * <p>The checked DNS result cannot be pinned through {@link java.net.HttpURLConnection},
 * so a DNS-rebinding window remains between this check and connection setup. A proxy that
 * resolves hostnames remotely must enforce an equivalent destination policy itself. System
 * DNS may ignore interruption, so lookups run in a small bounded daemon pool while callers
 * remain cancellation- and timeout-bounded.</p>
 */
public final class DefaultRadioNetworkPolicy implements AudioNetworkPolicy {

    public static final Duration DEFAULT_DNS_TIMEOUT = Duration.ofSeconds(5);
    private static final long CANCELLATION_POLL_NANOS = Duration.ofMillis(50).toNanos();
    private static final ThreadPoolExecutor DNS_EXECUTOR = new ThreadPoolExecutor(
            2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), runnable -> {
        Thread thread = new Thread(runnable, "Etched Radio DNS");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private final BooleanSupplier allowPrivateNetworks;
    private final AddressResolver resolver;
    private final long dnsTimeoutNanos;

    public DefaultRadioNetworkPolicy(BooleanSupplier allowPrivateNetworks) {
        this(allowPrivateNetworks, InetAddress::getAllByName, DEFAULT_DNS_TIMEOUT);
    }

    DefaultRadioNetworkPolicy(BooleanSupplier allowPrivateNetworks, AddressResolver resolver) {
        this(allowPrivateNetworks, resolver, DEFAULT_DNS_TIMEOUT);
    }

    DefaultRadioNetworkPolicy(BooleanSupplier allowPrivateNetworks, AddressResolver resolver,
                              Duration dnsTimeout) {
        this.allowPrivateNetworks = Objects.requireNonNull(allowPrivateNetworks, "allowPrivateNetworks");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(dnsTimeout, "dnsTimeout");
        if (dnsTimeout.isZero() || dnsTimeout.isNegative()) {
            throw new IllegalArgumentException("dnsTimeout must be positive");
        }
        try {
            this.dnsTimeoutNanos = dnsTimeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("dnsTimeout is too large", exception);
        }
    }

    @Override
    public void check(URI uri) throws RadioTransportException {
        String host = validateUri(uri);
        this.checkAddresses(resolve(host), host);
    }

    @Override
    public void check(URI uri, AudioCancellation cancellation) throws RadioTransportException {
        Objects.requireNonNull(cancellation, "cancellation");
        String host = validateUri(uri);
        cancellation.throwIfCancelled();
        this.checkAddresses(this.resolveBounded(host, cancellation), host);
        cancellation.throwIfCancelled();
    }

    private static String validateUri(URI uri) throws RadioTransportException {
        Objects.requireNonNull(uri, "uri");
        String scheme = uri.getScheme();
        String host = uri.getHost();
        String normalizedScheme = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute()
                || !normalizedScheme.equals("http") && !normalizedScheme.equals("https")
                || host == null || host.isBlank()
                || uri.getRawUserInfo() != null
                || uri.getPort() == 0 || uri.getPort() > 65535
                || containsControlCharacter(uri.toString())
                || host.contains("%")) {
            throw failure(RadioFailure.Code.INVALID_URL, false,
                    "Radio URL must be an absolute HTTP(S) URL without credentials", null);
        }
        return stripIpv6Brackets(host);
    }

    private InetAddress[] resolve(String host) throws RadioTransportException {
        try {
            return this.resolver.resolve(host);
        } catch (UnknownHostException exception) {
            throw failure(RadioFailure.Code.UNKNOWN, true, "Could not resolve the radio host", exception);
        }
    }

    private InetAddress[] resolveBounded(String host, AudioCancellation cancellation)
            throws RadioTransportException {
        Future<InetAddress[]> lookup;
        try {
            lookup = DNS_EXECUTOR.submit(() -> this.resolver.resolve(host));
        } catch (RejectedExecutionException exception) {
            cancellation.throwIfCancelled();
            throw failure(RadioFailure.Code.RESOURCE_LIMIT, true,
                    "Radio DNS lookup capacity is exhausted", exception);
        }

        long deadline = System.nanoTime() + this.dnsTimeoutNanos;
        try {
            while (true) {
                cancellation.throwIfCancelled();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    cancellation.throwIfCancelled();
                    throw failure(RadioFailure.Code.CONNECT_TIMEOUT, true,
                            "Timed out while resolving the radio host", null);
                }
                try {
                    return lookup.get(Math.min(remaining, CANCELLATION_POLL_NANOS), TimeUnit.NANOSECONDS);
                } catch (TimeoutException ignored) {
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Radio DNS lookup was interrupted");
                } catch (ExecutionException exception) {
                    cancellation.throwIfCancelled();
                    Throwable cause = exception.getCause();
                    if (cause instanceof UnknownHostException unknownHost) {
                        throw failure(RadioFailure.Code.UNKNOWN, true,
                                "Could not resolve the radio host", unknownHost);
                    }
                    throw failure(RadioFailure.Code.UNKNOWN, true,
                            "Could not resolve the radio host", cause);
                }
            }
        } finally {
            lookup.cancel(true);
            if (lookup instanceof Runnable task) {
                DNS_EXECUTOR.remove(task);
            }
        }
    }

    private void checkAddresses(InetAddress[] addresses, String host) throws RadioTransportException {
        if (addresses == null || addresses.length == 0) {
            throw failure(RadioFailure.Code.UNKNOWN, true,
                    "Could not resolve the radio host: " + host, null);
        }

        boolean hasPublic = false;
        boolean hasPrivate = false;
        for (InetAddress address : addresses) {
            if (address == null) {
                throw failure(RadioFailure.Code.BLOCKED_ADDRESS, false,
                        "The radio host resolved to an invalid address", null);
            }
            switch (RadioAddressClassifier.classify(address)) {
                case PUBLIC -> hasPublic = true;
                case PRIVATE -> hasPrivate = true;
                case FORBIDDEN -> throw failure(RadioFailure.Code.BLOCKED_ADDRESS, false,
                        "The radio host resolved to a blocked address", null);
            }
        }

        if (hasPublic && hasPrivate) {
            throw failure(RadioFailure.Code.BLOCKED_ADDRESS, false,
                    "The radio host resolved to mixed public and private addresses", null);
        }
        if (hasPrivate && !this.allowPrivateNetworks.getAsBoolean()) {
            throw failure(RadioFailure.Code.BLOCKED_ADDRESS, false,
                    "Private-network radio stations are disabled", null);
        }
    }

    private static String stripIpv6Brackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static RadioTransportException failure(RadioFailure.Code code, boolean recoverable,
                                                   String message, Throwable cause) {
        return new RadioTransportException(code, recoverable, message, cause);
    }

    @FunctionalInterface
    interface AddressResolver {

        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
