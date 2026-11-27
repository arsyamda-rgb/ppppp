package com.veyrion.wardrobe.config;

import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/** The values of config.yml, already clamped to safe ranges. Immutable. */
public record WardrobeSettings(int maxOutfits, int nameMaxLength, int skinCooldownSeconds, int skinTimeoutSeconds,
                               List<String> allowedHosts, double mirrorMaxUseDistance, boolean previewEnabled,
                               int skinMaxConcurrent, int skinCacheMaxEntries, int skinCacheExpireDays,
                               boolean skinProbeEnabled, int skinMaxImageKb, boolean skinReapplyOnJoin,
                               int previewRefreshDelayTicks) {

    /** The Stage 1 shape: every Stage 2 value takes its default. */
    public WardrobeSettings(int maxOutfits, int nameMaxLength, int skinCooldownSeconds, int skinTimeoutSeconds,
                            List<String> allowedHosts, double mirrorMaxUseDistance, boolean previewEnabled) {
        this(maxOutfits, nameMaxLength, skinCooldownSeconds, skinTimeoutSeconds, allowedHosts, mirrorMaxUseDistance,
                previewEnabled, 2, 2000, 90, true, 1024, false, 2);
    }

    public static WardrobeSettings defaults() {
        return new WardrobeSettings(10, 24, 20, 40, List.of(), 6.0, true);
    }

    public static WardrobeSettings from(ConfigurationSection config) {
        WardrobeSettings d = defaults();
        return new WardrobeSettings(
                clamp(config.getInt("outfits.max-per-player", d.maxOutfits), 1, 100),
                clamp(config.getInt("outfits.name-max-length", d.nameMaxLength), 1, 32),
                clamp(config.getInt("skins.cooldown-seconds", d.skinCooldownSeconds), 0, 3600),
                clamp(config.getInt("skins.timeout-seconds", d.skinTimeoutSeconds), 5, 120),
                List.copyOf(config.getStringList("skins.allowed-hosts")),
                Math.max(2.0, Math.min(32.0, config.getDouble("mirror.max-use-distance", d.mirrorMaxUseDistance))),
                config.getBoolean("preview.enabled", d.previewEnabled),
                clamp(config.getInt("skins.max-concurrent", d.skinMaxConcurrent), 1, 8),
                clamp(config.getInt("skins.cache.max-entries", d.skinCacheMaxEntries), 50, 100_000),
                clamp(config.getInt("skins.cache.expire-days", d.skinCacheExpireDays), 0, 3650),
                config.getBoolean("skins.probe-image", d.skinProbeEnabled),
                clamp(config.getInt("skins.max-image-kb", d.skinMaxImageKb), 16, 4096),
                config.getBoolean("skins.reapply-active-on-join", d.skinReapplyOnJoin),
                clamp(config.getInt("preview.refresh-delay-ticks", d.previewRefreshDelayTicks), 0, 40));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
