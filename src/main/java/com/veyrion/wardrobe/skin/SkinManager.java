package com.veyrion.wardrobe.skin;

import com.veyrion.wardrobe.config.WardrobeSettings;
import com.veyrion.wardrobe.model.Outfit;
import com.veyrion.wardrobe.model.SkinModel;
import com.veyrion.wardrobe.outfit.OutfitManager;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Turns a skin URL into a worn outfit: check the URL, find or process the skin (SkinsRestorer, off the main
 * thread), put it on the player (main thread), save the outfit (OutfitManager), redraw the mirror preview.
 * <p>
 * Threads. Every public method is called on the main thread and every future it returns completes on the main
 * thread. All state here is touched on the main thread only, so there is no lock and no race: the async part
 * (image check, SkinsRestorer) gets plain values in and hands one plain value back.
 * <p>
 * Rules this class keeps:
 * <ul>
 *   <li>A failure saves nothing and changes nothing: the old outfit stays, the player keeps the skin they wear.</li>
 *   <li>Only successes are cached. The cache is filled even when the player left or timed out meanwhile.</li>
 *   <li>The same skin requested by several players at once is processed once (they share one "flight").</li>
 *   <li>Cooldown and the server-wide limit count only real processing, not a cache hit or joining a flight.</li>
 *   <li>One request per player at a time. A player who left gets no apply and no error.</li>
 * </ul>
 */
public final class SkinManager {

    private record Resolution(SkinStatus status, @Nullable ResolvedSkin skin, int retryAfterSeconds) {
        boolean ok() {
            return status == SkinStatus.OK;
        }

        static Resolution ok(ResolvedSkin skin) {
            return new Resolution(SkinStatus.OK, skin, 0);
        }

        static Resolution fail(SkinStatus status) {
            return new Resolution(status, null, 0);
        }
    }

    private record Lookup(@Nullable ResolvedSkin skin, @Nullable SkinStatus failure) {}

    private record Flight(CompletableFuture<Resolution> future) {}

    private final Logger logger;
    private final OutfitManager outfits;
    private final SkinBackend backend;
    private final SkinImageProbe probe;
    private final SkinCache cache;
    private final Executor main;
    private final Executor async;
    private final Supplier<WardrobeSettings> settings;
    private final LongSupplier clock;
    private final Predicate<UUID> online;
    private final Consumer<UUID> afterApply;

    // Main thread only:
    private final Map<SkinKey, Flight> flights = new HashMap<>();
    private final Map<UUID, Object> busy = new HashMap<>();
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private boolean closed;

    /**
     * @param main       runs a task on the main server thread
     * @param async      runs a task on a worker thread; it may reject when full (the request then gets BUSY)
     * @param clock      current time in milliseconds
     * @param online     whether a player is online (main thread)
     * @param afterApply called on the main thread after a skin was put on a player (redraws the mirror preview)
     */
    public SkinManager(Logger logger, OutfitManager outfits, SkinBackend backend, SkinImageProbe probe, SkinCache cache,
                       Executor main, Executor async, Supplier<WardrobeSettings> settings, LongSupplier clock,
                       Predicate<UUID> online, Consumer<UUID> afterApply) {
        this.logger = logger;
        this.outfits = outfits;
        this.backend = backend;
        this.probe = probe;
        this.cache = cache;
        this.main = main;
        this.async = async;
        this.settings = settings;
        this.clock = clock;
        this.online = online;
        this.afterApply = afterApply;
    }

    // ------------------------------------------------------------------------------------------ requests

    /**
     * "Add Outfit": the whole flow of the GUI in one call. {@code rawName} null/blank = a default name.
     * Nothing is processed when the name or the limit would make the save fail anyway.
     */
    public CompletableFuture<SkinResult> addOutfit(UUID player, @Nullable String rawUrl, SkinModel model, @Nullable String rawName) {
        if (closed) {
            return done(SkinStatus.SHUTTING_DOWN);
        }
        if (!online.test(player)) {
            return done(SkinStatus.PLAYER_OFFLINE);
        }
        SkinUrlValidator.Result checked = new SkinUrlValidator(settings.get().allowedHosts()).validate(rawUrl);
        if (!checked.valid()) {
            return done(fromProblem(checked.problem()));
        }
        if (!outfits.isLoaded(player)) {
            return done(SkinStatus.NOT_LOADED);
        }
        String name = null;
        if (rawName != null && !rawName.isBlank()) {
            Optional<String> cleaned = outfits.cleanName(rawName);
            if (cleaned.isEmpty()) {
                return done(SkinStatus.INVALID_NAME);
            }
            name = cleaned.get();
            String wanted = name;
            if (outfits.outfits(player).stream().anyMatch(o -> o.name().equalsIgnoreCase(wanted))) {
                return done(SkinStatus.NAME_TAKEN);
            }
        }
        if (outfits.outfits(player).size() >= outfits.limit()) {
            return done(SkinStatus.LIMIT_REACHED);
        }
        String url = checked.url();
        String outfitName = name;
        return resolve(player, url, model).thenCompose(resolution -> {
            if (!resolution.ok()) {
                return CompletableFuture.completedFuture(new SkinResult(resolution.status(), null, resolution.retryAfterSeconds()));
            }
            return saveAndApply(player, url, model, outfitName, resolution.skin());
        });
    }

    /** Puts a saved outfit on the player again (also what "after a restart" means) and makes it the active one. */
    public CompletableFuture<SkinResult> wear(UUID player, long outfitId) {
        if (closed) {
            return done(SkinStatus.SHUTTING_DOWN);
        }
        if (!online.test(player)) {
            return done(SkinStatus.PLAYER_OFFLINE);
        }
        if (!outfits.isLoaded(player)) {
            return done(SkinStatus.NOT_LOADED);
        }
        Optional<Outfit> found = outfits.find(player, outfitId);
        if (found.isEmpty()) {
            return done(SkinStatus.NOT_FOUND);
        }
        Outfit outfit = found.get();
        return resolve(player, outfit.url(), outfit.model()).thenCompose(resolution -> {
            if (!resolution.ok()) {
                return CompletableFuture.completedFuture(new SkinResult(resolution.status(), null, resolution.retryAfterSeconds()));
            }
            if (outfits.find(player, outfitId).isEmpty()) { // deleted while the skin was being processed
                return done(SkinStatus.NOT_FOUND);
            }
            SkinStatus applied = applyNow(player, resolution.skin());
            if (applied != SkinStatus.OK) {
                return done(applied); // the outfit data is untouched
            }
            String ref = resolution.skin().ref();
            CompletableFuture<?> refSaved = ref.equals(outfit.skinRef())
                    ? CompletableFuture.completedFuture(null) : outfits.setSkinRef(player, outfitId, ref);
            return refSaved.thenCompose(ignored -> activate(player, outfit.withSkinRef(ref)));
        });
    }

    /** Applies the player's active outfit again, if they have one. NOT_FOUND when there is none. */
    public CompletableFuture<SkinResult> reapplyActive(UUID player) {
        if (!outfits.isLoaded(player)) {
            return done(SkinStatus.NOT_LOADED);
        }
        return outfits.active(player).map(o -> wear(player, o.id())).orElseGet(() -> done(SkinStatus.NOT_FOUND));
    }

    /** The player left: their pending request ends quietly when it finishes. Their cooldown stays. */
    public void onQuit(UUID player) {
        busy.remove(player);
    }

    /** Plugin disable: no new work, waiting players are told the server is stopping. */
    public void shutdown() {
        closed = true;
        for (Flight flight : Map.copyOf(flights).values()) {
            flight.future().complete(Resolution.fail(SkinStatus.SHUTTING_DOWN));
        }
        flights.clear();
        busy.clear();
        cooldownUntil.clear();
    }

    /** Skins being processed right now. */
    public int inFlight() {
        return flights.size();
    }

    // ---------------------------------------------------------------------------------- finding the skin

    private CompletableFuture<Resolution> resolve(UUID player, String url, SkinModel model) {
        Object token = new Object();
        if (busy.putIfAbsent(player, token) != null) {
            return CompletableFuture.completedFuture(Resolution.fail(SkinStatus.ALREADY_PROCESSING));
        }
        SkinKey key = SkinKey.of(url, model);
        CompletableFuture<Resolution> work;
        try {
            work = cache.get(key).isPresent() ? viaCache(player, key) : viaNetwork(player, key);
        } catch (RuntimeException e) {
            busy.remove(player, token);
            logger.log(Level.WARNING, "Skin request of " + player + " failed unexpectedly", e);
            return CompletableFuture.completedFuture(Resolution.fail(SkinStatus.PROCESSING_FAILED));
        }
        int seconds = settings.get().skinTimeoutSeconds();
        // The timeout is this player's only. The shared flight keeps running and fills the cache when it ends.
        return work.copy().orTimeout(seconds, TimeUnit.SECONDS).handleAsync((resolution, error) -> {
            busy.remove(player, token);
            if (error == null) {
                return resolution;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            if (cause instanceof TimeoutException) {
                return Resolution.fail(SkinStatus.TIMEOUT);
            }
            logger.log(Level.WARNING, "Skin request of " + player + " failed unexpectedly", cause);
            return Resolution.fail(SkinStatus.PROCESSING_FAILED);
        }, main);
    }

    /** We processed this skin before: ask SkinsRestorer for it without MineSkin. If it forgot it, process again. */
    private CompletableFuture<Resolution> viaCache(UUID player, SkinKey key) {
        return onAsync(() -> lookup(key), status -> new Lookup(null, status)).thenComposeAsync(lookup -> {
            if (lookup.skin() != null) {
                return CompletableFuture.completedFuture(Resolution.ok(lookup.skin()));
            }
            SkinStatus failure = lookup.failure();
            if (failure != null && failure != SkinStatus.PROCESSING_FAILED) {
                return CompletableFuture.completedFuture(Resolution.fail(failure));
            }
            cache.invalidate(key); // SkinsRestorer no longer has it (or could not tell): process it again
            return viaNetwork(player, key);
        }, main);
    }

    private CompletableFuture<Resolution> viaNetwork(UUID player, SkinKey key) {
        long now = clock.getAsLong();
        Flight flight = flights.get(key);
        boolean starter = flight == null;
        Long until = cooldownUntil.get(player);
        if (until != null && until > now && starter) {
            return CompletableFuture.completedFuture(new Resolution(SkinStatus.COOLDOWN, null, (int) Math.ceil((until - now) / 1000.0)));
        }
        if (starter) {
            if (flights.size() >= settings.get().skinMaxConcurrent()) {
                return CompletableFuture.completedFuture(Resolution.fail(SkinStatus.BUSY));
            }
            flight = startFlight(key);
            long cooldownMs = settings.get().skinCooldownSeconds() * 1000L;
            if (cooldownMs > 0) {
                if (cooldownUntil.size() > 128) {
                    cooldownUntil.values().removeIf(t -> t <= now);
                }
                cooldownUntil.put(player, now + cooldownMs);
            }
        }
        return flight.future();
    }

    private Flight startFlight(SkinKey key) {
        Flight flight = new Flight(new CompletableFuture<>());
        flights.put(key, flight);
        // Frees the slot if SkinsRestorer hangs; a late answer is still cached below (the flight is gone by then).
        flight.future().orTimeout(settings.get().skinTimeoutSeconds() * 3L, TimeUnit.SECONDS)
                .whenCompleteAsync((r, e) -> flights.remove(key, flight), main);
        onAsync(() -> process(key), Resolution::fail).whenCompleteAsync((resolution, error) -> {
            flights.remove(key, flight);
            Resolution result = error != null ? Resolution.fail(SkinStatus.PROCESSING_FAILED) : resolution;
            if (result.ok() && !closed) {
                cache.put(key, result.skin().ref()); // only successes, and even if nobody is waiting any more
            }
            flight.future().complete(result);
        }, main);
        return flight;
    }

    /** Worker thread. Never throws. */
    private Lookup lookup(SkinKey key) {
        try {
            return new Lookup(backend.lookup(key.url(), key.model()).orElse(null), null);
        } catch (SkinException e) {
            return new Lookup(null, e.status());
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.WARNING, "SkinsRestorer lookup failed for " + key.url(), e);
            return new Lookup(null, SkinStatus.PROCESSING_FAILED);
        }
    }

    /** Worker thread. Never throws. */
    private Resolution process(SkinKey key) {
        try {
            if (settings.get().skinProbeEnabled()) {
                probe.probe(key.url());
            }
            return Resolution.ok(backend.create(key.url(), key.model()));
        } catch (SkinException e) {
            if (e.status() == SkinStatus.PROCESSING_FAILED || e.status() == SkinStatus.SKINSRESTORER_UNAVAILABLE) {
                logger.log(Level.WARNING, "Skin processing failed: " + e.getMessage(), e.getCause());
            }
            return Resolution.fail(e.status());
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.WARNING, "Skin processing failed unexpectedly for " + key.url(), e);
            return Resolution.fail(SkinStatus.PROCESSING_FAILED);
        }
    }

    /** Runs {@code task} on the worker pool. A full or closed pool gives BUSY or SHUTTING_DOWN instead of an exception. */
    private <T> CompletableFuture<T> onAsync(Supplier<T> task, Function<SkinStatus, T> rejected) {
        try {
            return CompletableFuture.supplyAsync(task, async);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(rejected.apply(closed ? SkinStatus.SHUTTING_DOWN : SkinStatus.BUSY));
        }
    }

    // ----------------------------------------------------------------------------- applying and saving

    private CompletableFuture<SkinResult> saveAndApply(UUID player, String url, SkinModel model, @Nullable String name, ResolvedSkin skin) {
        if (!online.test(player)) {
            return done(SkinStatus.PLAYER_OFFLINE); // the skin is cached: they can add it again without waiting
        }
        return outfits.add(player, name, url, model, skin.ref()).thenCompose(added -> {
            if (!added.ok()) {
                return done(fromOutfit(added.status()));
            }
            Outfit outfit = added.outfit();
            SkinStatus applied = applyNow(player, skin);
            if (applied != SkinStatus.OK) {
                // Roll back: a failed request must leave nothing behind.
                return outfits.delete(player, outfit.id()).handle((deleted, error) -> {
                    if (error != null || !deleted.ok()) {
                        logger.warning("Could not remove outfit " + outfit.id() + " of " + player + " after its skin failed to apply.");
                    }
                    return SkinResult.fail(applied);
                });
            }
            return activate(player, outfit);
        });
    }

    private CompletableFuture<SkinResult> activate(UUID player, Outfit outfit) {
        return outfits.setActive(player, outfit.id()).thenApply(result -> result.ok() ? SkinResult.ok(outfit) : SkinResult.fail(SkinStatus.SAVE_FAILED));
    }

    /** Main thread. */
    private SkinStatus applyNow(UUID player, ResolvedSkin skin) {
        if (!online.test(player)) {
            return SkinStatus.PLAYER_OFFLINE;
        }
        try {
            backend.apply(player, skin);
        } catch (SkinException e) {
            if (e.status() != SkinStatus.PLAYER_OFFLINE) {
                logger.log(Level.WARNING, "Could not apply a skin to " + player + ": " + e.getMessage(), e.getCause());
            }
            return e.status();
        } catch (RuntimeException | LinkageError e) {
            logger.log(Level.WARNING, "Could not apply a skin to " + player, e);
            return SkinStatus.APPLY_FAILED;
        }
        try {
            afterApply.accept(player);
        } catch (RuntimeException e) { // the skin is on; a broken preview must not undo that
            logger.log(Level.WARNING, "The mirror preview of " + player + " could not be redrawn", e);
        }
        return SkinStatus.OK;
    }

    // --------------------------------------------------------------------------------------------- helpers

    private static CompletableFuture<SkinResult> done(SkinStatus status) {
        return CompletableFuture.completedFuture(SkinResult.fail(status));
    }

    private static SkinStatus fromProblem(SkinUrlValidator.Problem problem) {
        return switch (problem) {
            case EMPTY -> SkinStatus.URL_EMPTY;
            case TOO_LONG -> SkinStatus.URL_TOO_LONG;
            case MALFORMED -> SkinStatus.URL_MALFORMED;
            case NOT_HTTPS -> SkinStatus.URL_NOT_HTTPS;
            case HAS_CREDENTIALS -> SkinStatus.URL_HAS_CREDENTIALS;
            case PRIVATE_HOST -> SkinStatus.URL_PRIVATE_HOST;
            case HOST_NOT_ALLOWED -> SkinStatus.URL_HOST_NOT_ALLOWED;
        };
    }

    private static SkinStatus fromOutfit(OutfitManager.Status status) {
        return switch (status) {
            case OK -> SkinStatus.OK;
            case NOT_LOADED -> SkinStatus.NOT_LOADED;
            case INVALID_NAME -> SkinStatus.INVALID_NAME;
            case NAME_TAKEN -> SkinStatus.NAME_TAKEN;
            case LIMIT_REACHED -> SkinStatus.LIMIT_REACHED;
            case NOT_FOUND -> SkinStatus.NOT_FOUND;
            case ERROR -> SkinStatus.SAVE_FAILED;
        };
    }
}
