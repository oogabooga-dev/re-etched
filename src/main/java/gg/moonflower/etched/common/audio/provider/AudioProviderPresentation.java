package gg.moonflower.etched.common.audio.provider;

import gg.moonflower.etched.core.Etched;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.jetbrains.annotations.ApiStatus;

import java.net.URI;
import java.util.Optional;

/** Side-neutral built-in service branding. This never discovers providers or performs I/O. */
@ApiStatus.Internal
public final class AudioProviderPresentation {

    private AudioProviderPresentation() {
    }

    public static Optional<Component> brand(String source) {
        if (source == null) {
            return Optional.empty();
        }
        try {
            URI input = URI.create(source);
            if (BandcampPageReader.supports(input)) {
                return Optional.of(Component.translatable("sound_source." + Etched.MOD_ID + ".bandcamp")
                        .withStyle(style -> style.withColor(TextColor.fromRgb(0x477987))));
            }
            if (SoundCloudPageReader.supports(input)) {
                return Optional.of(Component.translatable("sound_source." + Etched.MOD_ID + ".sound_cloud")
                        .withStyle(style -> style.withColor(TextColor.fromRgb(0xFF5500))));
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid, local and direct-audio sources have no service brand.
        }
        return Optional.empty();
    }
}
