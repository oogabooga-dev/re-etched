package gg.moonflower.etched.client.radio.stream;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

abstract class AbstractPlaybackAudioStream implements PlaybackAudioStream {

    private final CompletableFuture<Termination> termination = new CompletableFuture<>();

    @Override
    public final CompletionStage<Termination> termination() {
        return this.termination.minimalCompletionStage();
    }

    protected final void complete(TerminalState state) {
        this.termination.complete(new Termination(state, null));
    }

    protected final void fail(IOException exception) {
        this.termination.complete(new Termination(TerminalState.FAILED, exception));
    }

    @Override
    public final boolean isTerminated() {
        return this.termination.isDone();
    }
}
