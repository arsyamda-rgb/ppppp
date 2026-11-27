package com.veyrion.wardrobe.storage;

import com.veyrion.wardrobe.model.Outfit;
import com.veyrion.wardrobe.model.SkinModel;
import com.veyrion.wardrobe.outfit.OutfitStore;
import com.veyrion.wardrobe.skin.SkinCacheStore;
import com.veyrion.wardrobe.skin.SkinKey;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * SQLite storage (the driver is bundled with Paper; only java.sql is used). One connection, used by one thread
 * only, so every operation runs one after the other and "check then change" steps cannot interleave. Journal mode
 * is WAL with full syncing: a crash cannot corrupt the file, and a finished write is on disk.
 * Each SQL statement is a constant so dev-checks/sql_check.py can run the very same text against SQLite.
 */
public final class WardrobeStorage implements OutfitStore, SkinCacheStore, AutoCloseable {

    static final String SQL_LOAD_OUTFITS = """
            SELECT id, name, url, model, sr_identifier, created_at FROM outfits WHERE owner = ? ORDER BY id""";
    static final String SQL_LOAD_ACTIVE = """
            SELECT active_outfit_id FROM players WHERE uuid = ?""";
    static final String SQL_NAME_EXISTS = """
            SELECT 1 FROM outfits WHERE owner = ? AND name = ? COLLATE NOCASE""";
    static final String SQL_INSERT_OUTFIT = """
            INSERT INTO outfits (owner, name, url, model, sr_identifier, created_at)
            SELECT ?, ?, ?, ?, ?, ? WHERE (SELECT COUNT(*) FROM outfits WHERE owner = ?) < ?""";
    static final String SQL_LAST_ID = """
            SELECT last_insert_rowid()""";
    static final String SQL_RENAME = """
            UPDATE outfits SET name = ? WHERE id = ? AND owner = ?""";
    static final String SQL_DELETE = """
            DELETE FROM outfits WHERE id = ? AND owner = ?""";
    static final String SQL_CLEAR_ACTIVE = """
            INSERT INTO players (uuid, active_outfit_id) VALUES (?, NULL)
            ON CONFLICT (uuid) DO UPDATE SET active_outfit_id = NULL""";
    static final String SQL_SET_ACTIVE = """
            INSERT INTO players (uuid, active_outfit_id)
            SELECT ?, ? WHERE EXISTS (SELECT 1 FROM outfits WHERE id = ? AND owner = ?)
            ON CONFLICT (uuid) DO UPDATE SET active_outfit_id = excluded.active_outfit_id""";
    static final String SQL_SET_SKIN_REF = """
            UPDATE outfits SET sr_identifier = ? WHERE id = ? AND owner = ?""";

    static final String SQL_SKIN_LOAD_ALL = """
            SELECT cache_key, url, model, sr_identifier, created_at, last_used_at FROM skin_cache ORDER BY last_used_at""";
    static final String SQL_SKIN_UPSERT = """
            INSERT INTO skin_cache (cache_key, url, model, sr_identifier, created_at, last_used_at) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (cache_key) DO UPDATE SET sr_identifier = excluded.sr_identifier, last_used_at = excluded.last_used_at""";
    static final String SQL_SKIN_TOUCH = """
            UPDATE skin_cache SET last_used_at = ? WHERE cache_key = ?""";
    static final String SQL_SKIN_DELETE = """
            DELETE FROM skin_cache WHERE cache_key = ?""";
    static final String SQL_SKIN_DELETE_OLD = """
            DELETE FROM skin_cache WHERE last_used_at < ?""";

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private final Logger logger;
    private final String jdbcUrl;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "VeyrionWardrobe-Database");
        thread.setDaemon(true);
        return thread;
    });
    private Connection connection; // the worker thread only
    private volatile boolean closed;

    private WardrobeStorage(Logger logger, Path file) {
        this.logger = logger;
        this.jdbcUrl = "jdbc:sqlite:" + file.toAbsolutePath();
    }

    /** Opens (and if needed creates) the database. Blocks; call it while the plugin enables. */
    public static WardrobeStorage open(Logger logger, Path file) throws StorageException {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new StorageException("Could not create the data folder for " + file, e);
        }
        WardrobeStorage storage = new WardrobeStorage(logger, file);
        try {
            storage.worker.submit(storage::init).get();
        } catch (Exception e) {
            storage.worker.shutdownNow();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new StorageException("Could not open the wardrobe database " + file
                    + " (" + cause.getMessage() + "). The file is left untouched; fix or move it and restart.", cause);
        }
        return storage;
    }

    private Void init() throws SQLException, IOException, ClassNotFoundException {
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection(jdbcUrl);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = FULL");
            try (ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
                if (!rs.next() || !"ok".equals(rs.getString(1))) {
                    throw new SQLException("the database failed its integrity check");
                }
            }
        }
        connection.setAutoCommit(false);
        try (Statement st = connection.createStatement()) {
            for (String statement : schema()) {
                st.execute(statement);
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
        return null;
    }

    private static List<String> schema() throws IOException {
        try (InputStream in = WardrobeStorage.class.getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new IOException("schema.sql is missing from the plugin jar");
            }
            List<String> statements = new ArrayList<>();
            for (String part : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!part.isBlank()) {
                    statements.add(part.strip());
                }
            }
            return statements;
        }
    }

    private <T> CompletableFuture<T> run(Work<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed) {
            future.completeExceptionally(new IllegalStateException("the wardrobe database is closed"));
            return future;
        }
        try {
            worker.execute(() -> {
                try {
                    future.complete(work.run(connection));
                } catch (SQLException | RuntimeException e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) { // rejected: shutting down
            future.completeExceptionally(e);
        }
        return future;
    }

    // ------------------------------------------------------------------------------------------ OutfitStore

    @Override
    public CompletableFuture<Wardrobe> load(UUID owner) {
        return run(c -> {
            List<Outfit> outfits = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SQL_LOAD_OUTFITS)) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        outfits.add(readOutfit(owner, rs));
                    }
                }
            }
            Long active = null;
            try (PreparedStatement ps = c.prepareStatement(SQL_LOAD_ACTIVE)) {
                ps.setString(1, owner.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long id = rs.getLong(1);
                        active = rs.wasNull() ? null : id;
                    }
                }
            }
            return new Wardrobe(List.copyOf(outfits), active);
        });
    }

    private static Outfit readOutfit(UUID owner, ResultSet rs) throws SQLException {
        SkinModel model;
        try {
            model = SkinModel.valueOf(rs.getString("model"));
        } catch (IllegalArgumentException e) {
            throw new SQLException("outfit " + rs.getLong("id") + " has an unknown model", e);
        }
        return new Outfit(rs.getLong("id"), owner, rs.getString("name"), rs.getString("url"), model,
                rs.getString("sr_identifier"), rs.getLong("created_at"));
    }

    @Override
    public CompletableFuture<Inserted> insert(UUID owner, String name, String url, SkinModel model,
                                              @Nullable String skinRef, int maxOutfits) {
        return run(c -> {
            try (PreparedStatement ps = c.prepareStatement(SQL_NAME_EXISTS)) {
                ps.setString(1, owner.toString());
                ps.setString(2, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new Inserted(InsertStatus.NAME_TAKEN, null);
                    }
                }
            }
            long now = System.currentTimeMillis();
            c.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement ps = c.prepareStatement(SQL_INSERT_OUTFIT)) {
                    ps.setString(1, owner.toString());
                    ps.setString(2, name);
                    ps.setString(3, url);
                    ps.setString(4, model.name());
                    ps.setString(5, skinRef);
                    ps.setLong(6, now);
                    ps.setString(7, owner.toString());
                    ps.setInt(8, maxOutfits);
                    changed = ps.executeUpdate();
                }
                if (changed == 0) {
                    c.rollback();
                    return new Inserted(InsertStatus.LIMIT_REACHED, null);
                }
                long id;
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(SQL_LAST_ID)) {
                    rs.next();
                    id = rs.getLong(1);
                }
                c.commit();
                return new Inserted(InsertStatus.CREATED, new Outfit(id, owner, name, url, model, skinRef, now));
            } catch (SQLException e) {
                c.rollback();
                if (isUniqueViolation(e)) {
                    return new Inserted(InsertStatus.NAME_TAKEN, null);
                }
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
    }

    @Override
    public CompletableFuture<RenameStatus> rename(UUID owner, long outfitId, String newName) {
        return run(c -> {
            try (PreparedStatement ps = c.prepareStatement(SQL_RENAME)) {
                ps.setString(1, newName);
                ps.setLong(2, outfitId);
                ps.setString(3, owner.toString());
                return ps.executeUpdate() == 0 ? RenameStatus.NOT_FOUND : RenameStatus.RENAMED;
            } catch (SQLException e) {
                if (isUniqueViolation(e)) {
                    return RenameStatus.NAME_TAKEN;
                }
                throw e;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID owner, long outfitId) {
        return run(c -> {
            try (PreparedStatement ps = c.prepareStatement(SQL_DELETE)) {
                ps.setLong(1, outfitId);
                ps.setString(2, owner.toString());
                return ps.executeUpdate() > 0; // players.active_outfit_id becomes NULL by itself (ON DELETE SET NULL)
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> setActive(UUID owner, @Nullable Long outfitId) {
        return run(c -> {
            if (outfitId == null) {
                try (PreparedStatement ps = c.prepareStatement(SQL_CLEAR_ACTIVE)) {
                    ps.setString(1, owner.toString());
                    ps.executeUpdate();
                    return true;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(SQL_SET_ACTIVE)) {
                ps.setString(1, owner.toString());
                ps.setLong(2, outfitId);
                ps.setLong(3, outfitId);
                ps.setString(4, owner.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> setSkinRef(UUID owner, long outfitId, String skinRef) {
        return run(c -> {
            try (PreparedStatement ps = c.prepareStatement(SQL_SET_SKIN_REF)) {
                ps.setString(1, skinRef);
                ps.setLong(2, outfitId);
                ps.setString(3, owner.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    // ------------------------------------------------------------------------------------------ SkinCacheStore

    @Override
    public CompletableFuture<List<SkinCacheStore.Row>> loadAll() {
        return run(c -> {
            List<SkinCacheStore.Row> rows = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(SQL_SKIN_LOAD_ALL)) {
                while (rs.next()) {
                    try {
                        SkinModel model = SkinModel.valueOf(rs.getString("model"));
                        rows.add(new SkinCacheStore.Row(new SkinKey(rs.getString("url"), model), rs.getString("sr_identifier"),
                                rs.getLong("created_at"), rs.getLong("last_used_at")));
                    } catch (IllegalArgumentException e) {
                        logger.warning("Skipping a saved skin with an unknown model: " + rs.getString("cache_key"));
                    }
                }
            }
            return List.copyOf(rows);
        });
    }

    @Override
    public CompletableFuture<Void> upsert(SkinCacheStore.Row row) {
        return run(c -> {
            try (PreparedStatement ps = c.prepareStatement(SQL_SKIN_UPSERT)) {
                ps.setString(1, row.key().id());
                ps.setString(2, row.key().url());
                ps.setString(3, row.key().model().name());
                ps.setString(4, row.ref());
                ps.setLong(5, row.createdAt());
                ps.setLong(6, row.lastUsedAt());
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<Void> touch(SkinKey key, long lastUsedAt) {
        return run(c -> {
            try (PreparedStatement ps = c.prepareStatement(SQL_SKIN_TOUCH)) {
                ps.setLong(1, lastUsedAt);
                ps.setString(2, key.id());
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<Void> delete(SkinKey key) {
        return run(c -> {
            try (PreparedStatement ps = c.prepareStatement(SQL_SKIN_DELETE)) {
                ps.setString(1, key.id());
                ps.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteUsedBefore(long cutoff) {
        return run(c -> {
            try (PreparedStatement ps = c.prepareStatement(SQL_SKIN_DELETE_OLD)) {
                ps.setLong(1, cutoff);
                ps.executeUpdate();
                return null;
            }
        });
    }

    private static boolean isUniqueViolation(SQLException e) {
        String message = e.getMessage();
        return message != null && message.contains("UNIQUE");
    }

    // --------------------------------------------------------------------------------------------- closing

    /** Finishes the queued writes, then closes the file. Blocks for at most 10 seconds. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        worker.execute(() -> {
            try {
                if (connection != null) {
                    connection.close();
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Could not close the wardrobe database cleanly", e);
            }
        });
        worker.shutdown();
        try {
            if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("The wardrobe database did not finish its writes within 10 seconds.");
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }

    /** For tests and diagnostics: unwraps a failed future's cause. */
    static Throwable rootCause(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }
}
