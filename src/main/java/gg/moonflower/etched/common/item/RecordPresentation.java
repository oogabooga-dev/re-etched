package gg.moonflower.etched.common.item;

import gg.moonflower.etched.common.audio.RecordContent;
import gg.moonflower.etched.common.audio.provider.AudioProviderPresentation;
import gg.moonflower.etched.core.Etched;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;

/** Side-neutral presentation of immutable record content; no provider I/O or mutable shared components. */
@ApiStatus.Internal
public final class RecordPresentation {

    private RecordPresentation() {
    }

    public static String source(RecordContent content) {
        return content.album().map(RecordContent.AlbumMetadata::source)
                .orElseGet(() -> content.program().tracks().get(0).source());
    }

    public static Component displayName(RecordContent content) {
        String artist = content.album().map(RecordContent.AlbumMetadata::artist)
                .orElseGet(() -> content.program().tracks().get(0).artist());
        String title = content.album().map(RecordContent.AlbumMetadata::title)
                .orElseGet(() -> content.program().tracks().get(0).title());
        return Component.translatable("sound_source." + Etched.MOD_ID + ".info", artist, Component.literal(title));
    }

    public static List<Component> tooltip(RecordContent content) {
        List<Component> lines = new ArrayList<>();
        lines.add(displayName(content).copy().withStyle(ChatFormatting.GRAY));
        boolean album = content.album().isPresent() || content.program().tracks().size() > 1;
        AudioProviderPresentation.brand(source(content))
                .map(component -> Component.literal("  ").append(component))
                .map(component -> album ? component.append(" ").append(albumLabel()) : component)
                .ifPresentOrElse(lines::add, () -> {
                    if (album) {
                        lines.add(albumLabel());
                    }
                });
        return lines;
    }

    private static Component albumLabel() {
        return Component.translatable("item." + Etched.MOD_ID + ".etched_music_disc.album").withStyle(ChatFormatting.DARK_GRAY);
    }
}
