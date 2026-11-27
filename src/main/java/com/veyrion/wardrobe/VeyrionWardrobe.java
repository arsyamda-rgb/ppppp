package com.veyrion.wardrobe;

import com.veyrion.wardrobe.config.WardrobeSettings;
import com.veyrion.wardrobe.integration.DependencyGuard;
import com.veyrion.wardrobe.outfit.OutfitManager;
import com.veyrion.wardrobe.skin.HttpSkinImageProbe;
import com.veyrion.wardrobe.skin.SkinCache;
import com.veyrion.wardrobe.skin.SkinManager;
import com.veyrion.wardrobe.skin.SkinPreviews;
import com.veyrion.wardrobe.skin.SkinsRestorerIntegration;
import com.veyrion.wardrobe.storage.StorageException;
import com.veyrion.wardrobe.storage.WardrobeStorage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * VeyrionWardrobe: outfits and the Aether Mirror. This class only wires the parts together; it holds no rules.
 * Stage 1: dependency checks, settings, database, outfit management.
 * Stage 2: skins (SkinManager on top of SkinsRestorer), the skin cache and the preview refresh. (Mirror, GUI later.)
 */
public final class VeyrionWardrobe extends JavaPlugin implements Listener {

    private WardrobeSettings settings;
    private WardrobeStorage storage;
    private OutfitManager outfits;
    private SkinManager skins;
    private SkinPreviews previews;
    private ThreadPoolExecutor skinWorkers;

    @Override
    public void onEnable() {
        List<String> problems = DependencyGuard.check(getServer().getPluginManager());
        if (!problems.isEmpty()) {
            getLogger().severe("VeyrionWardrobe cannot start:");
            problems.forEach(problem -> getLogger().severe("  - " + problem));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        settings = WardrobeSettings.from(getConfig());

        try {
            storage = WardrobeStorage.open(getLogger(), Path.of(getDataFolder().getPath(), "wardrobe.db"));
        } catch (StorageException e) {
            getLogger().log(Level.SEVERE, e.getMessage(), e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Executor mainThread = task -> {
            if (isEnabled()) {
                Bukkit.getScheduler().runTask(this, task);
            }
        };
        outfits = new OutfitManager(getLogger(), storage, mainThread, () -> settings);

        // Stage 2. Worker threads: the image check and SkinsRestorer's own (blocking) calls. A full queue answers "busy".
        int workers = Math.max(2, settings.skinMaxConcurrent() * 2);
        skinWorkers = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), runnable -> {
            Thread thread = new Thread(runnable, "VeyrionWardrobe-Skins");
            thread.setDaemon(true);
            return thread;
        });
        SkinCache cache = new SkinCache(getLogger(), storage, () -> settings, System::currentTimeMillis);
        cache.load(Duration.ofSeconds(10));
        previews = new SkinPreviews();
        skins = new SkinManager(getLogger(), outfits, new SkinsRestorerIntegration(),
                new HttpSkinImageProbe(settings.skinMaxImageKb() * 1024, Duration.ofSeconds(Math.min(15, settings.skinTimeoutSeconds()))),
                cache, mainThread, skinWorkers, () -> settings, System::currentTimeMillis, this::isOnline, this::refreshPreviewLater);

        getServer().getPluginManager().registerEvents(this, this);
        for (Player online : getServer().getOnlinePlayers()) { // after /reload or a late enable
            outfits.load(online.getUniqueId());
        }
        getLogger().info("VeyrionWardrobe enabled (outfit limit " + settings.maxOutfits() + ", skin cache " + cache.size() + ").");
    }

    @Override
    public void onDisable() {
        if (skins != null) {
            skins.shutdown();
            skins = null;
        }
        if (previews != null) {
            previews.removeAll();
            previews = null;
        }
        if (skinWorkers != null) {
            skinWorkers.shutdownNow();
            skinWorkers = null;
        }
        if (outfits != null) {
            outfits.unloadAll();
        }
        if (storage != null) {
            storage.close(); // finishes the queued writes first
            storage = null;
        }
    }

    public OutfitManager outfits() {
        return outfits;
    }

    public SkinManager skins() {
        return skins;
    }

    /** Where the mirror (Stage 4) hands over the CharacterPreview it made, so a new skin is redrawn on it. */
    public SkinPreviews previews() {
        return previews;
    }

    public WardrobeSettings settings() {
        return settings;
    }

    private boolean isOnline(UUID id) {
        Player player = Bukkit.getPlayer(id);
        return player != null && player.isOnline();
    }

    /** A skin was applied: wait a few ticks so the server's profile of the player has the new skin, then redraw. */
    private void refreshPreviewLater(UUID id) {
        int delay = settings.previewRefreshDelayTicks();
        if (delay <= 0) {
            previews.refresh(id);
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (previews != null) {
                previews.refresh(id);
            }
        }, delay);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        outfits.load(id).thenRun(() -> {
            if (settings.skinReapplyOnJoin() && skins != null && isOnline(id)) {
                skins.reapplyActive(id);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        skins.onQuit(id);
        previews.onQuit(id);
        outfits.unload(id);
    }
}
