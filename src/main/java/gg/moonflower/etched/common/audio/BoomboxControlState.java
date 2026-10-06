package gg.moonflower.etched.common.audio;

import gg.moonflower.etched.common.item.RecordContentResolver;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/** Server-side boombox intent; cosmetic stack changes do not restart an unchanged program. */
public final class BoomboxControlState {

    private ItemStack observed = ItemStack.EMPTY;
    private AudioProgram program;

    public Optional<PlaybackState> observe(ItemStack record, LongSupplier revisions) {
        if (ItemStack.matches(this.observed, record)) {
            return Optional.empty();
        }
        this.observed = record.copy();
        AudioProgram updated = RecordContentResolver.resolve(record).map(RecordContent::program).orElse(null);
        if (Objects.equals(this.program, updated)) {
            return Optional.empty();
        }
        this.program = updated;
        return Optional.of(this.snapshot(revisions));
    }

    public boolean enabled() {
        return this.program != null;
    }

    /** A tracking snapshot gets a fresh server publication revision, without restarting existing recipients. */
    public PlaybackState snapshot(LongSupplier revisions) {
        return new PlaybackState(revisions.getAsLong(), Optional.ofNullable(this.program), this.enabled());
    }
}
