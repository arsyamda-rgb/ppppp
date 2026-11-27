package com.veyrion.wardrobe.skin;

import com.veyrion.wardrobe.config.WardrobeSettings;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The skins SkinsRestorer already processed, by (URL, model). Memory is a copy of the database table, so a restart
 * loses nothing. Only successes are ever stored: a failed request leaves no trace here.
 * <p>
 * Cleaning: at most {@code skins.cache.max-entries} entries (the least recently used goes first) and an entry
 * nobody used for {@code skins.cache.expire-days} days goes too. Both are checked on every read and write and once
 * at load. Main thread only; the store runs on its own thread.
 */
public final class SkinCache {

    public record Entry(SkinKey key, String ref, long createdAt, long lastUsedAt) {}

    /** A hit refreshes "last used" (memory and disk) at most this often, to spare the database. */
    static final long TOUCH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(10);

    private final Logger logger;
    private final SkinCacheStore store;
    private final Supplier<WardrobeSettings> settings;
    private final LongSupplier clock;
    /** Access order: the first entry is the least recently used. */
    private final Map<SkinKey, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);

    public SkinCache(Logger logger, SkinCacheStore store, Supplier<WardrobeSettings> settings, LongSupplier clock) {
        this.logger = logger;
        this.store = store;
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Reads the saved skins. Blocks up to {@code wait}; call it once while the plugin enables. If the database
     * cannot be read the cache starts empty: skins are processed again, nothing else is lost.
     */
    public void load(Duration wait) {
        try {
            int days = settings.get().skinCacheExpireDays();
            if (days > 0) {
                store.deleteUsedBefore(clock.getAsLong() - TimeUnit.DAYS.toMillis(days)).get(wait.toMillis(), TimeUnit.MILLISECONDS);
            }
            List<SkinCacheStore.Row> rows = store.loadAll().get(wait.toMillis(), TimeUnit.MILLISECONDS);
            rows.stream().sorted((a, b) -> Long.compare(a.lastUsedAt(), b.lastUsedAt())) // oldest first = least recent
                    .forEach(r -> entries.put(r.key(), new Entry(r.key(), r.ref(), r.createdAt(), r.lastUsedAt())));
            trim();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.log(Level.WARNING, "Could not read the saved skins; starting with an empty skin cache.", e);
            entries.clear();
        }
    }

    public Optional<Entry> get(SkinKey key) {
        trim();
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        long now = clock.getAsLong();
        if (now - entry.lastUsedAt() > TOUCH_INTERVAL_MS) {
            entry = new Entry(entry.key(), entry.ref(), entry.createdAt(), now);
            entries.put(key, entry);
            report("refresh a saved skin", store.touch(key, now));
        }
        return Optional.of(entry);
    }

    /** Remembers a skin that was processed successfully. */
    public void put(SkinKey key, String ref) {
        long now = clock.getAsLong();
        Entry previous = entries.get(key);
        Entry entry = new Entry(key, ref, previous == null ? now : previous.createdAt(), now);
        entries.put(key, entry);
        report("save a skin", store.upsert(new SkinCacheStore.Row(key, ref, entry.createdAt(), now)));
        trim();
    }

    /** Forgets a skin (SkinsRestorer no longer has it). */
    public void invalidate(SkinKey key) {
        if (entries.remove(key) != null) {
            report("forget a skin", store.delete(key));
        }
    }

    public int size() {
        return entries.size();
    }

    private void trim() {
        int max = settings.get().skinCacheMaxEntries();
        int days = settings.get().skinCacheExpireDays();
        long cutoff = days > 0 ? clock.getAsLong() - TimeUnit.DAYS.toMillis(days) : Long.MIN_VALUE;
        Iterator<Entry> it = entries.values().iterator();
        while (it.hasNext()) {
            Entry eldest = it.next();
            if (entries.size() > max || eldest.lastUsedAt() < cutoff) {
                it.remove();
                report("remove an old skin", store.delete(eldest.key()));
            } else {
                break; // the rest were used more recently and the size is fine
            }
        }
    }

    private void report(String what, java.util.concurrent.CompletableFuture<Void> future) {
        future.whenComplete((ok, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Skin cache: could not " + what + " on disk (the skin still works).", error);
            }
        });
    }
}
