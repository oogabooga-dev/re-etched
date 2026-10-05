package gg.moonflower.etched.common.audio.provider;

import gg.moonflower.etched.api.util.DownloadProgressListener;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CancellationProgressListenerTest {

    @Test
    void allCallbacksKeepTheirExactArgumentsAndAreSuppressedAfterCancellation() {
        var delegate = new Progress();
        var cancelled = new AtomicBoolean();
        var listener = new CancellationProgressListener(delegate, cancelled::get);
        Component request = Component.literal("fixture request");
        callbacks(listener, request);
        assertEquals(7, delegate.calls.get());
        assertSame(request, delegate.request);
        assertEquals(2.5F, delegate.size);
        assertEquals(37, delegate.percentage);
        assertEquals(0.25F, delegate.fraction);
        cancelled.set(true);
        callbacks(listener, Component.literal("late request"));
        assertEquals(7, delegate.calls.get());
    }

    @Test
    void activeListenerExceptionsArePreservedButCancelledListenersAreNotInvoked() {
        var cancelled = new AtomicBoolean();
        IllegalStateException failure = new IllegalStateException("fixture callback failure");
        var listener = new CancellationProgressListener(new Progress() {
            @Override public void onFail() { throw failure; }
        }, cancelled::get);
        assertSame(failure, assertThrows(IllegalStateException.class, listener::onFail));
        cancelled.set(true);
        assertDoesNotThrow(listener::onFail);
    }

    private static void callbacks(DownloadProgressListener listener, Component request) {
        listener.progressStartRequest(request);
        listener.progressStartDownload(2.5F);
        listener.progressStagePercentage(37);
        listener.progressStage(0.25F);
        listener.progressStartLoading();
        listener.onSuccess();
        listener.onFail();
    }

    private static class Progress implements DownloadProgressListener {
        private final AtomicInteger calls = new AtomicInteger();
        private Component request;
        private float size;
        private int percentage;
        private float fraction;
        @Override public void progressStartRequest(Component component) { this.calls.incrementAndGet(); this.request = component; }
        @Override public void progressStartDownload(float size) { this.calls.incrementAndGet(); this.size = size; }
        @Override public void progressStagePercentage(int percentage) { this.calls.incrementAndGet(); this.percentage = percentage; }
        @Override public void progressStage(float percentage) { this.calls.incrementAndGet(); this.fraction = percentage; }
        @Override public void progressStartLoading() { this.calls.incrementAndGet(); }
        @Override public void onSuccess() { this.calls.incrementAndGet(); }
        @Override public void onFail() { this.calls.incrementAndGet(); }
    }
}
