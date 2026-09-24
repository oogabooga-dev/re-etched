package gg.moonflower.etched.client.radio.net;

import gg.moonflower.etched.client.radio.RadioFailure;
import gg.moonflower.etched.client.radio.PlaybackSession;
import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultRadioNetworkPolicyTest {

    private static final URI RADIO_URI = URI.create("https://radio.example/live");

    @Test
    void acceptsPublicIpv4AndIpv6Addresses() throws Exception {
        for (String address : List.of("8.8.8.8", "2606:4700:4700::1111")) {
            DefaultRadioNetworkPolicy policy = policy(false, literal(address));
            assertDoesNotThrow(() -> policy.check(RADIO_URI));
        }
    }

    @Test
    void blocksPrivateAddressesWithoutOptIn() throws Exception {
        for (String address : List.of(
                "10.0.0.1", "100.64.0.1", "127.0.0.1", "172.16.0.1", "192.168.1.1",
                "::1", "fd00::1")) {
            RadioTransportException exception = assertThrows(RadioTransportException.class,
                    () -> policy(false, literal(address)).check(RADIO_URI));
            assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, exception.code());
            assertFalse(exception.recoverable());
        }
    }

    @Test
    void privateNetworkOptInAllowsPrivateButNotSpecialAddresses() throws Exception {
        for (String address : List.of("10.0.0.1", "100.64.0.1", "127.0.0.1", "192.168.1.1", "::1", "fd00::1")) {
            assertDoesNotThrow(() -> policy(true, literal(address)).check(RADIO_URI));
        }
        for (String address : List.of(
                "0.0.0.0", "100.100.100.200", "169.254.169.254", "192.0.2.1", "198.18.0.1",
                "224.0.0.1", "::", "fe80::1", "ff02::1", "2001:db8::1", "fd00:ec2::254")) {
            RadioTransportException exception = assertThrows(RadioTransportException.class,
                    () -> policy(true, literal(address)).check(RADIO_URI));
            assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, exception.code());
        }
    }

    @Test
    void classifiesIpv4MappedIpv6UsingEmbeddedAddress() throws Exception {
        byte[] mappedPrivate = new byte[16];
        mappedPrivate[10] = (byte) 0xFF;
        mappedPrivate[11] = (byte) 0xFF;
        mappedPrivate[12] = 10;
        mappedPrivate[15] = 1;
        InetAddress address = Inet6Address.getByAddress(null, mappedPrivate, -1);

        RadioTransportException exception = assertThrows(RadioTransportException.class,
                () -> policy(false, address).check(RADIO_URI));

        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, exception.code());
        assertDoesNotThrow(() -> policy(true, address).check(RADIO_URI));
    }

    @Test
    void validatesIpLiteralsThroughTheProductionResolver() {
        DefaultRadioNetworkPolicy policy = new DefaultRadioNetworkPolicy(() -> false);

        for (URI uri : List.of(
                URI.create("http://127.0.0.1/live"),
                URI.create("http://[::1]/live"),
                URI.create("http://[::ffff:127.0.0.1]/live"))) {
            RadioTransportException exception = assertThrows(RadioTransportException.class,
                    () -> policy.check(uri));
            assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, exception.code());
        }
    }

    @Test
    void rejectsMixedPublicAndPrivateDnsAnswersEvenWithOptIn() throws Exception {
        DefaultRadioNetworkPolicy policy = policy(true,
                literal("8.8.8.8"), literal("192.168.1.1"));

        RadioTransportException exception = assertThrows(RadioTransportException.class,
                () -> policy.check(RADIO_URI));

        assertEquals(RadioFailure.Code.BLOCKED_ADDRESS, exception.code());
    }

    @Test
    void rejectsMalformedOrUnsafeUrisBeforeDns() {
        DefaultRadioNetworkPolicy policy = new DefaultRadioNetworkPolicy(
                () -> true, host -> {
                    throw new AssertionError("DNS should not run for invalid URI: " + host);
                });

        for (URI uri : List.of(
                URI.create("radio.example/live"),
                URI.create("ftp://radio.example/live"),
                URI.create("http:/live"),
                URI.create("http://user:password@radio.example/live"),
                URI.create("http://radio.example:0/live"),
                URI.create("http://radio.example:65536/live"),
                URI.create("http://[fe80::1%25eth0]/live"))) {
            RadioTransportException exception = assertThrows(RadioTransportException.class,
                    () -> policy.check(uri));
            assertEquals(RadioFailure.Code.INVALID_URL, exception.code());
            assertFalse(exception.recoverable());
        }
    }

    @Test
    void reportsDnsFailureAsRecoverable() {
        DefaultRadioNetworkPolicy policy = new DefaultRadioNetworkPolicy(
                () -> false, host -> {
                    throw new UnknownHostException(host);
                });

        RadioTransportException exception = assertThrows(RadioTransportException.class,
                () -> policy.check(RADIO_URI));

        assertEquals(RadioFailure.Code.UNKNOWN, exception.code());
        assertTrue(exception.recoverable());
    }

    @Test
    void cancellationReleasesACallerBlockedOnDns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DefaultRadioNetworkPolicy policy = new DefaultRadioNetworkPolicy(
                () -> false, host -> {
                    started.countDown();
                    awaitDnsIgnoringInterrupt(release);
                    return new InetAddress[]{literal("8.8.8.8")};
                }, Duration.ofSeconds(5));
        PlaybackSession session = new PlaybackSession();
        PlaybackSession.Attempt attempt = session.start(RADIO_URI.toString());
        CompletableFuture<Void> check = CompletableFuture.runAsync(() -> {
            try {
                policy.check(RADIO_URI, attempt.cancellation());
            } catch (RadioTransportException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        });

        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            session.stop();
            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> check.get(1, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, exception.getCause());
        } finally {
            release.countDown();
        }
    }

    @Test
    void reportsBoundedDnsTimeoutAsRecoverableConnectTimeout() {
        CountDownLatch release = new CountDownLatch(1);
        DefaultRadioNetworkPolicy policy = new DefaultRadioNetworkPolicy(
                () -> false, host -> {
                    awaitDns(release);
                    return new InetAddress[]{literal("8.8.8.8")};
                }, Duration.ofMillis(50));
        PlaybackSession.Attempt attempt = new PlaybackSession().start(RADIO_URI.toString());

        try {
            RadioTransportException exception = assertThrows(RadioTransportException.class,
                    () -> policy.check(RADIO_URI, attempt.cancellation()));

            assertEquals(RadioFailure.Code.CONNECT_TIMEOUT, exception.code());
            assertTrue(exception.recoverable());
        } finally {
            release.countDown();
        }
    }

    private static DefaultRadioNetworkPolicy policy(boolean allowPrivate, InetAddress... addresses) {
        return new DefaultRadioNetworkPolicy(() -> allowPrivate, host -> addresses);
    }

    private static InetAddress literal(String address) throws UnknownHostException {
        return InetAddress.getByName(address);
    }

    private static void awaitDns(CountDownLatch latch) throws UnknownHostException {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            UnknownHostException failure = new UnknownHostException("DNS lookup interrupted");
            failure.initCause(exception);
            throw failure;
        }
    }

    private static void awaitDnsIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() > 0) {
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
