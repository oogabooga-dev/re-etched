package gg.moonflower.etched.clientsmoke;

import com.sun.net.httpserver.HttpServer;
import gg.moonflower.etched.client.radio.*;
import gg.moonflower.etched.client.radio.net.*;
import gg.moonflower.etched.client.radio.source.*;
import gg.moonflower.etched.client.radio.sound.RadioSoundInstance;
import gg.moonflower.etched.client.radio.stream.*;
import gg.moonflower.etched.core.Etched;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real Forge/OpenAL client, local HTTP stall, UI click and measured client ticks. No user worlds. */
@Mod.EventBusSubscriber(modid = Etched.MOD_ID, value = Dist.CLIENT)
public final class RadioStallSmoke {
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(3);
    private static final ExecutorService PRODUCERS = Executors.newFixedThreadPool(2);
    private static final ExecutorService DECODERS = Executors.newFixedThreadPool(2);
    private static final RadioCancellation CANCEL = new RadioCancellation();
    private static final AtomicBoolean STALLED = new AtomicBoolean();
    private static final boolean BASELINE = "1".equals(System.getenv("ETCHED_STALL_BASELINE"));
    private static HttpServer server;
    private static CompletableFuture<RadioStreamPipeline.Preparation> preparing;
    private static RadioSoundInstance radio;
    private static long started;
    private static long lastTick;
    private static long maxGap;
    private static long maxClick;
    private static int ticks;
    private static int clicks;
    private static volatile boolean done;

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) return;
        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
        try {
            if (preparing == null) {
                start();
                return;
            }
            if (radio == null) {
                if (!preparing.isDone()) return;
                RadioStreamPipeline.Preparation prep = preparing.join();
                if (!prep.stream().toCompletableFuture().isDone()) return;
                RadioAudioStream audio = prep.stream().toCompletableFuture().join();
                radio = new RadioSoundInstance(new RadioKey(Level.OVERWORLD, BlockPos.ZERO), 1,
                        audio, CANCEL, 1, 16, () -> {
                    if (!prep.transfer(audio)) throw new AssertionError("Stream handoff failed");
                    prep.close();
                });
                started = lastTick = System.nanoTime();
                client.getSoundManager().play(radio);
                return;
            }
            long now = System.nanoTime();
            maxGap = Math.max(maxGap, now - lastTick);
            lastTick = now;
            ticks++;
            if (ticks % 4 == 0 && STALLED.get()) {
                long clickStart = System.nanoTime();
                client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
                maxClick = Math.max(maxClick, System.nanoTime() - clickStart);
                clicks++;
            }
            // Stop while the HTTP response is still stalled (server waits 8 s).
            if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(3) && !BASELINE) {
                if (!STALLED.get() || clicks < 5 || ticks < 40 || !radio.streamTransferred()) {
                    throw new AssertionError("Did not exercise live radio stall and client ticks");
                }
                long stop = System.nanoTime();
                CANCEL.cancel();
                client.getSoundManager().stop(radio);
                client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1));
                long stopNanos = System.nanoTime() - stop;
                if (maxGap > TimeUnit.SECONDS.toNanos(1) || maxClick > TimeUnit.SECONDS.toNanos(1)
                        || stopNanos > TimeUnit.SECONDS.toNanos(1)) {
                    throw new AssertionError("Client blocked: " + result(stopNanos));
                }
                finish(client, "PASS " + result(stopNanos));
            } else if (BASELINE && maxClick > TimeUnit.SECONDS.toNanos(2)) {
                finish(client, "PASS baseline reproduced " + result(0));
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(client, "FAIL " + failure);
        }
    }

    private static String result(long stop) {
        return "ticks=" + ticks + " clicks=" + clicks + " maxTickMs=" + maxGap / 1_000_000
                + " maxClickMs=" + maxClick / 1_000_000 + " stopMs=" + stop / 1_000_000;
    }

    private static void start() throws Exception {
        byte[] mp3;
        try (var input = RadioStallSmoke.class.getResourceAsStream("/stall-test.mp3")) {
            mp3 = input.readAllBytes();
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(WORKERS);
        server.createContext("/radio.mp3", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(200, 0);
            try (exchange; var output = exchange.getResponseBody()) {
                output.write(mp3, 0, 65_536);
                output.flush();
                STALLED.set(true);
                try { Thread.sleep(8_000); } catch (InterruptedException ignored) { }
                STALLED.set(false);
                output.write(mp3, 65_536, mp3.length - 65_536);
            }
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/radio.mp3");
        preparing = CompletableFuture.supplyAsync(() -> {
            try {
                RadioNetworkPolicy localFixtureOnly = address -> {
                    if (!address.getHost().equals("127.0.0.1")) throw new AssertionError("Unexpected destination");
                };
                var transport = new RadioHttpTransportImpl(Proxy.NO_PROXY, localFixtureOnly,
                        Duration.ofSeconds(2), Duration.ofSeconds(10), 0);
                var source = new DirectRadioSourceResolver().resolve(uri,
                        new RadioResolveContext(transport, localFixtureOnly, CANCEL, RadioResolveLimits.DEFAULT));
                return RadioStreamPipeline.prepare(source, CANCEL, PRODUCERS, DECODERS, false);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        }, WORKERS);
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(30_000); } catch (InterruptedException ignored) { }
            if (!done) {
                try { Files.writeString(Path.of("radio-stall-result.txt"), "FAIL watchdog expired"); }
                catch (Exception ignored) { }
                System.exit(2);
            }
        }, "Radio smoke watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void finish(Minecraft client, String result) {
        done = true;
        try { Files.writeString(Path.of("radio-stall-result.txt"), result + "\n"); }
        catch (Exception exception) { throw new RuntimeException(exception); }
        CANCEL.cancel();
        if (server != null) server.stop(0);
        WORKERS.shutdownNow();
        PRODUCERS.shutdownNow();
        DECODERS.shutdownNow();
        client.stop();
    }
}
