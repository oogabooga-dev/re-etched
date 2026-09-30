package gg.moonflower.etched.common.audio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps potentially blocking audio resource disposal off Minecraft owner threads. */
public final class RadioResourceDisposer {

    private static final Logger LOGGER = LogManager.getLogger();
    // Blocking JDK close calls must not starve later cancellations behind a fixed worker pool.
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(
            daemonFactory("Etched radio cleanup"));

    private RadioResourceDisposer() {
    }

    public static void dispose(Runnable action) {
        Objects.requireNonNull(action, "action");
        EXECUTOR.execute(() -> {
            try {
                action.run();
            } catch (RuntimeException failure) {
                LOGGER.warn("Radio resource disposal failed", failure);
            }
        });
    }

    private static ThreadFactory daemonFactory(String name) {
        AtomicInteger number = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, name + " " + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
