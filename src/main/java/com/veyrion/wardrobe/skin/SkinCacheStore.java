package com.veyrion.wardrobe.skin;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Where processed skins are kept between restarts. Each method may run on another thread. */
public interface SkinCacheStore {

    record Row(SkinKey key, String ref, long createdAt, long lastUsedAt) {}

    CompletableFuture<List<Row>> loadAll();

    /** Adds the row, or replaces the one with the same key. */
    CompletableFuture<Void> upsert(Row row);

    CompletableFuture<Void> touch(SkinKey key, long lastUsedAt);

    CompletableFuture<Void> delete(SkinKey key);

    /** Removes every row last used before {@code cutoff} (epoch milliseconds). */
    CompletableFuture<Void> deleteUsedBefore(long cutoff);
}
