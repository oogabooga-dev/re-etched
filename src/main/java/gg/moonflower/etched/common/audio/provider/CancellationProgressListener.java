package gg.moonflower.etched.common.audio.provider;

import gg.moonflower.etched.api.util.DownloadProgressListener;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/** Suppresses late source/download callbacks for a cancelled operation, not a delivered stream. */
public final class CancellationProgressListener implements DownloadProgressListener {

    private final DownloadProgressListener delegate;
    private final BooleanSupplier cancelled;

    public CancellationProgressListener(DownloadProgressListener delegate, BooleanSupplier cancelled) {
        this.delegate = delegate;
        this.cancelled = cancelled;
    }

    @Override
    public void progressStartRequest(Component component) {
        if (!this.cancelled.getAsBoolean()) {
            this.delegate.progressStartRequest(component);
        }
    }

    @Override
    public void progressStartDownload(float size) {
        if (!this.cancelled.getAsBoolean()) {
            this.delegate.progressStartDownload(size);
        }
    }

    @Override
    public void progressStagePercentage(int percentage) {
        if (!this.cancelled.getAsBoolean()) {
            this.delegate.progressStagePercentage(percentage);
        }
    }

    @Override
    public void progressStage(float percentage) {
        if (!this.cancelled.getAsBoolean()) {
            this.delegate.progressStage(percentage);
        }
    }

    @Override
    public void progressStartLoading() {
        if (!this.cancelled.getAsBoolean()) {
            this.delegate.progressStartLoading();
        }
    }

    @Override
    public void onSuccess() {
        if (!this.cancelled.getAsBoolean()) {
            this.delegate.onSuccess();
        }
    }

    @Override
    public void onFail() {
        if (!this.cancelled.getAsBoolean()) {
            this.delegate.onFail();
        }
    }
}
