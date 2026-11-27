package com.veyrion.wardrobe.outfit;

import com.veyrion.wardrobe.config.WardrobeSettings;
import com.veyrion.wardrobe.model.Outfit;
import com.veyrion.wardrobe.model.SkinModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * Everything about a player's outfits: the list in memory (for the GUI) and the rules (name, limit, who owns
 * what). It never touches skins: applying a skin is {@code SkinManager}'s job. The {@code owner} passed to every
 * method is the player who USES the mirror, never the owner of the mirror.
 * <p>
 * Threads: call from the main thread. The store works on its own thread; results come back on {@code main}.
 */
public final class OutfitManager {

    public enum Status { OK, NOT_LOADED, INVALID_NAME, NAME_TAKEN, LIMIT_REACHED, NOT_FOUND, ERROR }

    public record Result(Status status, @Nullable Outfit outfit) {
        public boolean ok() {
            return status == Status.OK;
        }
    }

    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N} _\\-'.]+");

    private static final class State {
        final List<Outfit> outfits;
        @Nullable Long activeId;

        State(List<Outfit> outfits, @Nullable Long activeId) {
            this.outfits = new ArrayList<>(outfits);
            this.activeId = activeId;
        }

        @Nullable Outfit find(long id) {
            for (Outfit outfit : outfits) {
                if (outfit.id() == id) {
                    return outfit;
                }
            }
            return null;
        }

        boolean hasName(String name) {
            return outfits.stream().anyMatch(o -> o.name().equalsIgnoreCase(name));
        }
    }

    private final Logger logger;
    private final OutfitStore store;
    private final Executor main;
    private final Supplier<WardrobeSettings> settings;
    /** Main thread only. */
    private final Map<UUID, State> cache = new HashMap<>();
    /** Players whose data should be in the cache: a load that finishes after {@link #unload} is dropped. */
    private final Set<UUID> wanted = new HashSet<>();

    public OutfitManager(Logger logger, OutfitStore store, Executor main, Supplier<WardrobeSettings> settings) {
        this.logger = logger;
        this.store = store;
        this.main = main;
        this.settings = settings;
    }

    // ------------------------------------------------------------------------------------------ loading

    /** Loads a player's outfits into memory (on join). The future completes when they can be used. */
    public CompletableFuture<Void> load(UUID player) {
        wanted.add(player);
        return store.load(player).handleAsync((wardrobe, error) -> {
            if (error != null) {
                logger.log(Level.SEVERE, "Could not load the wardrobe of " + player, error);
            } else if (wanted.contains(player)) {
                cache.put(player, new State(wardrobe.outfits(), wardrobe.activeId()));
            }
            return null;
        }, main);
    }

    /** Forgets a player's outfits (on quit). Saved data is untouched. */
    public void unload(UUID player) {
        wanted.remove(player);
        cache.remove(player);
    }

    public void unloadAll() {
        wanted.clear();
        cache.clear();
    }

    public boolean isLoaded(UUID player) {
        return cache.containsKey(player);
    }

    // ------------------------------------------------------------------------------------------ reading

    /** The player's outfits, oldest first; empty if not loaded. A copy. */
    public List<Outfit> outfits(UUID player) {
        State state = cache.get(player);
        return state == null ? List.of() : List.copyOf(state.outfits);
    }

    public Optional<Outfit> find(UUID player, long outfitId) {
        State state = cache.get(player);
        return state == null ? Optional.empty() : Optional.ofNullable(state.find(outfitId));
    }

    public Optional<Outfit> active(UUID player) {
        State state = cache.get(player);
        if (state == null || state.activeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.find(state.activeId));
    }

    public int limit() {
        return settings.get().maxOutfits();
    }

    /** "Outfit 1", "Outfit 2" ...: the first one this player does not have yet. */
    public String defaultName(UUID player) {
        State state = cache.get(player);
        for (int n = 1; ; n++) {
            String candidate = "Outfit " + n;
            if (state == null || !state.hasName(candidate)) {
                return candidate;
            }
        }
    }

    /** @return the cleaned name (trimmed, single spaces), or empty if it is not allowed */
    public Optional<String> cleanName(@Nullable String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String name = raw.strip().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > settings.get().nameMaxLength() || !NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        return Optional.of(name);
    }

    // ----------------------------------------------------------------------------------------- changing

    /**
     * Saves a new outfit. {@code skinRef} is SkinsRestorer's identifier of the processed skin (may be null).
     * {@code rawName} null/blank = a default name.
     */
    public CompletableFuture<Result> add(UUID player, @Nullable String rawName, String url, SkinModel model,
                                         @Nullable String skinRef) {
        State state = cache.get(player);
        if (state == null) {
            return done(Status.NOT_LOADED);
        }
        String name;
        if (rawName == null || rawName.isBlank()) {
            name = defaultName(player);
        } else {
            Optional<String> cleaned = cleanName(rawName);
            if (cleaned.isEmpty()) {
                return done(Status.INVALID_NAME);
            }
            name = cleaned.get();
        }
        return store.insert(player, name, url, model, skinRef, limit()).handleAsync((inserted, error) -> {
            if (error != null) {
                return failed("save an outfit for " + player, error);
            }
            return switch (inserted.status()) {
                case CREATED -> {
                    State current = cache.get(player);
                    if (current != null) {
                        current.outfits.add(inserted.outfit());
                    }
                    yield new Result(Status.OK, inserted.outfit());
                }
                case LIMIT_REACHED -> new Result(Status.LIMIT_REACHED, null);
                case NAME_TAKEN -> new Result(Status.NAME_TAKEN, null);
            };
        }, main);
    }

    public CompletableFuture<Result> rename(UUID player, long outfitId, @Nullable String rawName) {
        State state = cache.get(player);
        if (state == null) {
            return done(Status.NOT_LOADED);
        }
        if (state.find(outfitId) == null) {
            return done(Status.NOT_FOUND);
        }
        Optional<String> cleaned = cleanName(rawName);
        if (cleaned.isEmpty()) {
            return done(Status.INVALID_NAME);
        }
        String name = cleaned.get();
        return store.rename(player, outfitId, name).handleAsync((status, error) -> {
            if (error != null) {
                return failed("rename an outfit of " + player, error);
            }
            return switch (status) {
                case RENAMED -> {
                    State current = cache.get(player);
                    Outfit renamed = null;
                    if (current != null) {
                        for (int i = 0; i < current.outfits.size(); i++) {
                            if (current.outfits.get(i).id() == outfitId) {
                                renamed = current.outfits.get(i).withName(name);
                                current.outfits.set(i, renamed);
                            }
                        }
                    }
                    yield new Result(Status.OK, renamed);
                }
                case NOT_FOUND -> new Result(Status.NOT_FOUND, null);
                case NAME_TAKEN -> new Result(Status.NAME_TAKEN, null);
            };
        }, main);
    }

    public CompletableFuture<Result> delete(UUID player, long outfitId) {
        State state = cache.get(player);
        if (state == null) {
            return done(Status.NOT_LOADED);
        }
        if (state.find(outfitId) == null) {
            return done(Status.NOT_FOUND);
        }
        return store.delete(player, outfitId).handleAsync((deleted, error) -> {
            if (error != null) {
                return failed("delete an outfit of " + player, error);
            }
            State current = cache.get(player);
            Outfit removed = null;
            if (current != null) {
                removed = current.find(outfitId);
                current.outfits.removeIf(o -> o.id() == outfitId);
                if (current.activeId != null && current.activeId == outfitId) {
                    current.activeId = null; // the store cleared it too
                }
            }
            return deleted ? new Result(Status.OK, removed) : new Result(Status.NOT_FOUND, null);
        }, main);
    }

    /** Marks an outfit as the one being worn. Does not change the skin: call SkinManager for that. */
    public CompletableFuture<Result> setActive(UUID player, long outfitId) {
        State state = cache.get(player);
        if (state == null) {
            return done(Status.NOT_LOADED);
        }
        Outfit outfit = state.find(outfitId);
        if (outfit == null) {
            return done(Status.NOT_FOUND);
        }
        return store.setActive(player, outfitId).handleAsync((changed, error) -> {
            if (error != null) {
                return failed("set the active outfit of " + player, error);
            }
            if (!changed) {
                return new Result(Status.NOT_FOUND, null);
            }
            State current = cache.get(player);
            if (current != null) {
                current.activeId = outfitId;
            }
            return new Result(Status.OK, outfit);
        }, main);
    }

    /** Remembers the SkinsRestorer identifier after a skin was (re)processed. */
    public CompletableFuture<Result> setSkinRef(UUID player, long outfitId, String skinRef) {
        State state = cache.get(player);
        if (state == null) {
            return done(Status.NOT_LOADED);
        }
        if (state.find(outfitId) == null) {
            return done(Status.NOT_FOUND);
        }
        return store.setSkinRef(player, outfitId, skinRef).handleAsync((changed, error) -> {
            if (error != null) {
                return failed("save a skin reference for " + player, error);
            }
            State current = cache.get(player);
            Outfit updated = null;
            if (changed && current != null) {
                for (int i = 0; i < current.outfits.size(); i++) {
                    if (current.outfits.get(i).id() == outfitId) {
                        updated = current.outfits.get(i).withSkinRef(skinRef);
                        current.outfits.set(i, updated);
                    }
                }
            }
            return new Result(changed ? Status.OK : Status.NOT_FOUND, updated);
        }, main);
    }

    // --------------------------------------------------------------------------------------------- helpers

    private static CompletableFuture<Result> done(Status status) {
        return CompletableFuture.completedFuture(new Result(status, null));
    }

    private Result failed(String what, Throwable error) {
        logger.log(Level.SEVERE, "Could not " + what, error);
        return new Result(Status.ERROR, null);
    }
}
