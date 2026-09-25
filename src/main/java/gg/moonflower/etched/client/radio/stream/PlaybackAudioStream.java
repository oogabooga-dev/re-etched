package gg.moonflower.etched.client.radio.stream;

import net.minecraft.client.sounds.AudioStream;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** AudioStream with a terminal signal that remains visible outside SoundEngine. */
public interface PlaybackAudioStream extends AudioStream {

    CompletionStage<Termination> termination();

    default boolean isTerminated() {
        return this.termination().toCompletableFuture().isDone();
    }

    enum TerminalState {
        EOF,
        FAILED,
        CANCELLED,
        CLOSED
    }

    record Termination(TerminalState state, @Nullable IOException failure) {

        public Termination {
            Objects.requireNonNull(state, "state");
            if ((state == TerminalState.FAILED) != (failure != null)) {
                throw new IllegalArgumentException("Only failed termination carries an exception");
            }
        }
    }
}
