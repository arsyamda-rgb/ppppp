package com.veyrion.wardrobe;

import com.veyrion.character.api.CharacterPreview;
import com.veyrion.wardrobe.config.WardrobeSettings;
import com.veyrion.wardrobe.model.Outfit;
import com.veyrion.wardrobe.model.SkinModel;
import com.veyrion.wardrobe.outfit.OutfitManager;
import com.veyrion.wardrobe.skin.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Stage 2 checks: SkinManager, SkinCache, SkinPreviews, PngHeader and HttpSkinImageProbe with fakes. No Paper, no
 * SkinsRestorer, no database: the backend, the stores and the "main thread" are fakes (see README for the command).
 * "Main thread" here is a queue the test drains itself, so a result that is delivered on the wrong thread shows up.
 */
public final class SkinCheck {

    static int checks = 0;
    static int failures = 0;
    static final Logger LOG = Logger.getLogger("skincheck");

    static void eq(String what, Object expected, Object actual) {
        checks++;
        if (!Objects.equals(expected, actual)) {
            failures++;
            System.out.println("FAIL " + what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static final String U1 = "https://skins.example.com/one.png";
    static final String U2 = "https://skins.example.com/two.png";
    static final String U3 = "https://skins.example.com/three.png";

    // ------------------------------------------------------------------------------------------------ fakes

    /** SkinsRestorer: its own storage ({@code known}) outlives our restarts. */
    static final class FakeBackend implements SkinBackend {
        final AtomicInteger lookups = new AtomicInteger();
        final AtomicInteger creates = new AtomicInteger();
        final List<String> applied = Collections.synchronizedList(new ArrayList<>());
        final Map<SkinKey, ResolvedSkin> known = new ConcurrentHashMap<>();
        volatile SkinStatus createFailure;
        volatile SkinStatus applyFailure;
        volatile CountDownLatch gate;
        volatile Thread lastApplyThread;

        @Override public Optional<ResolvedSkin> lookup(String url, SkinModel model) {
            lookups.incrementAndGet();
            return Optional.ofNullable(known.get(SkinKey.of(url, model)));
        }
        @Override public ResolvedSkin create(String url, SkinModel model) {
            creates.incrementAndGet();
            CountDownLatch g = gate;
            if (g != null) {
                try {
                    g.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (createFailure != null) {
                throw new SkinException(createFailure, "fake failure");
            }
            ResolvedSkin skin = new ResolvedSkin("ref:" + model + ":" + url, "handle");
            known.put(SkinKey.of(url, model), skin);
            return skin;
        }
        @Override public void apply(UUID player, ResolvedSkin skin) {
            lastApplyThread = Thread.currentThread();
            if (applyFailure != null) {
                throw new SkinException(applyFailure, "fake apply failure");
            }
            applied.add(player + "|" + skin.ref());
        }
    }

    static final class FakeProbe implements SkinImageProbe {
        final AtomicInteger probes = new AtomicInteger();
        volatile SkinStatus failure;
        @Override public void probe(String url) {
            probes.incrementAndGet();
            if (failure != null) {
                throw new SkinException(failure, "fake probe failure");
            }
        }
    }

    static final class MemCacheStore implements SkinCacheStore {
        final Map<String, Row> rows = new ConcurrentHashMap<>();
        volatile boolean failing;
        private <T> CompletableFuture<T> result(T value) {
            return failing ? CompletableFuture.failedFuture(new IllegalStateException("disk full")) : CompletableFuture.completedFuture(value);
        }
        @Override public CompletableFuture<List<Row>> loadAll() { return result(List.copyOf(rows.values())); }
        @Override public CompletableFuture<Void> upsert(Row row) { if (!failing) rows.put(row.key().id(), row); return result(null); }
        @Override public CompletableFuture<Void> touch(SkinKey key, long when) {
            Row r = rows.get(key.id());
            if (r != null && !failing) rows.put(key.id(), new Row(r.key(), r.ref(), r.createdAt(), when));
            return result(null);
        }
        @Override public CompletableFuture<Void> delete(SkinKey key) { if (!failing) rows.remove(key.id()); return result(null); }
        @Override public CompletableFuture<Void> deleteUsedBefore(long cutoff) { if (!failing) rows.values().removeIf(r -> r.lastUsedAt() < cutoff); return result(null); }
    }

    static final class FakePreview implements CharacterPreview {
        final UUID subject;
        boolean active = true;
        int refreshes;
        int removes;
        boolean throwOnRefresh;
        FakePreview(UUID subject) { this.subject = subject; }
        @Override public UUID subject() { return subject; }
        @Override public boolean isActive() { return active; }
        @Override public void show(org.bukkit.entity.Player viewer) {}
        @Override public void refreshSkin() {
            if (throwOnRefresh) throw new IllegalStateException("packet error");
            refreshes++;
        }
        @Override public void look(float yaw, float pitch) {}
        @Override public void remove() { active = false; removes++; }
    }

    static WardrobeSettings settings(int cooldown, int timeout, int concurrent) {
        return new WardrobeSettings(10, 24, cooldown, timeout, List.of(), 6.0, true, concurrent, 2000, 90, false, 1024, false, 2);
    }

    /** Everything wired like the plugin does, with a hand-driven main thread. */
    static final class Rig {
        final Queue<Runnable> mainQueue = new ConcurrentLinkedQueue<>();
        final Executor main = mainQueue::add;
        final ExecutorService pool = Executors.newFixedThreadPool(4);
        final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        final Set<UUID> online = ConcurrentHashMap.newKeySet();
        final WardrobeCheck.MemoryStore outfitStore = new WardrobeCheck.MemoryStore();
        final MemCacheStore cacheStore = new MemCacheStore();
        final FakeBackend backend = new FakeBackend();
        final FakeProbe probe = new FakeProbe();
        final SkinPreviews previews = new SkinPreviews();
        final List<UUID> refreshed = Collections.synchronizedList(new ArrayList<>());
        final Thread mainThread = Thread.currentThread();
        volatile boolean offMainViolation;
        WardrobeSettings settings;
        OutfitManager outfits;
        SkinCache cache;
        SkinManager skins;
        Executor asyncOverride;

        Rig(WardrobeSettings settings) {
            this.settings = settings;
            start();
        }

        void start() {
            outfits = new OutfitManager(LOG, outfitStore, main, () -> settings);
            cache = new SkinCache(LOG, cacheStore, () -> settings, clock::get);
            cache.load(Duration.ofSeconds(2));
            skins = new SkinManager(LOG, outfits, backend, probe, cache, main, asyncOverride != null ? asyncOverride : pool,
                    () -> settings, clock::get, online::contains, id -> {
                        if (Thread.currentThread() != mainThread) offMainViolation = true;
                        refreshed.add(id);
                        previews.refresh(id);
                    });
        }

        /** A server restart: the memory is gone; the outfit database, the skin database and SkinsRestorer's storage stay. */
        void restart() {
            skins.shutdown();
            pump();
            mainQueue.clear();
            online.clear();
            start();
        }

        UUID join() {
            UUID id = UUID.randomUUID();
            joinAs(id);
            return id;
        }

        void joinAs(UUID id) {
            online.add(id);
            await(outfits.load(id));
        }

        void quit(UUID id) {
            online.remove(id);
            skins.onQuit(id);
            previews.onQuit(id);
            outfits.unload(id);
        }

        void pump() {
            Runnable task;
            while ((task = mainQueue.poll()) != null) {
                task.run();
            }
        }

        <T> T await(CompletableFuture<T> future) {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!future.isDone() && System.nanoTime() < end) {
                pump();
                sleep(2);
            }
            pump();
            if (!future.isDone()) {
                throw new AssertionError("future did not complete");
            }
            return future.join();
        }

        boolean waitFor(java.util.function.BooleanSupplier condition, int millis) {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            while (!condition.getAsBoolean() && System.nanoTime() < end) {
                pump();
                sleep(2);
            }
            pump();
            return condition.getAsBoolean();
        }

        SkinResult add(UUID p, String url, SkinModel m, String name) {
            return await(skins.addOutfit(p, url, m, name));
        }

        void close() {
            skins.shutdown();
            pool.shutdownNow();
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------------------ 1, 10

    static void validAndApplied() {
        Rig r = new Rig(settings(0, 5, 2));
        UUID a = r.join();
        SkinResult res = r.add(a, "  " + U1 + " ", SkinModel.SLIM, "Knight");
        eq("1 valid url: ok", SkinStatus.OK, res.status());
        eq("1 outfit has name", "Knight", res.outfit().name());
        eq("1 outfit has url (trimmed)", U1, res.outfit().url());
        eq("1 outfit has model", SkinModel.SLIM, res.outfit().model());
        eq("1 outfit has skin ref", "ref:SLIM:" + U1, res.outfit().skinRef());
        eq("1 one outfit saved for the player", 1, r.outfits.outfits(a).size());
        eq("1 outfit is active", res.outfit().id(), r.outfits.active(a).orElseThrow().id());
        eq("1 saved in the store with the ref", "ref:SLIM:" + U1, r.outfitStore.outfits.get(a).get(0).skinRef());
        eq("10 skin applied once to that player", List.of(a + "|ref:SLIM:" + U1), r.backend.applied);
        eq("10 applied on the main thread", true, r.backend.lastApplyThread == Thread.currentThread());
        eq("10 mirror refresh asked once", List.of(a), r.refreshed);
        eq("10 refresh on the main thread", false, r.offMainViolation);
        eq("1 cached after success", 1, r.cache.size());
        eq("1 nothing left in flight", 0, r.skins.inFlight());
        eq("1 default name when none given", "Outfit 1", r.add(a, U2, SkinModel.WIDE, null).outfit().name());
        eq("1 message for ok", "Skin applied.", res.message());
        r.close();
    }

    static void invalidUrls() {
        Rig r = new Rig(settings(30, 5, 2));
        UUID a = r.join();
        Object[][] cases = {
                {null, SkinStatus.URL_EMPTY}, {"", SkinStatus.URL_EMPTY}, {"   ", SkinStatus.URL_EMPTY},
                {"not a url", SkinStatus.URL_MALFORMED}, {"https://", SkinStatus.URL_MALFORMED}, {"just-text", SkinStatus.URL_MALFORMED},
                {"http://skins.example.com/a.png", SkinStatus.URL_NOT_HTTPS}, {"ftp://skins.example.com/a.png", SkinStatus.URL_NOT_HTTPS},
                {"https://user:pw@skins.example.com/a.png", SkinStatus.URL_HAS_CREDENTIALS},
                {"https://localhost/a.png", SkinStatus.URL_PRIVATE_HOST}, {"https://127.0.0.1/a.png", SkinStatus.URL_PRIVATE_HOST},
                {"https://192.168.1.5/a.png", SkinStatus.URL_PRIVATE_HOST}, {"https://10.0.0.1/a.png", SkinStatus.URL_PRIVATE_HOST},
                {"https://[::1]/a.png", SkinStatus.URL_PRIVATE_HOST}, {"https://intranet/a.png", SkinStatus.URL_PRIVATE_HOST},
                {"https://skins.example.com/" + "a".repeat(600), SkinStatus.URL_TOO_LONG},
        };
        for (Object[] c : cases) {
            eq("2 invalid url " + c[0], c[1], r.add(a, (String) c[0], SkinModel.WIDE, null).status());
        }
        eq("2 nothing processed", 0, r.backend.creates.get() + r.backend.lookups.get() + r.probe.probes.get());
        eq("2 nothing saved", 0, r.outfits.outfits(a).size());
        eq("2 bad url does not start a cooldown", SkinStatus.OK, r.add(a, U1, SkinModel.WIDE, null).status());
        eq("2 every status has a message", true, Arrays.stream(SkinStatus.values()).allMatch(s -> !s.message(5).isBlank()));
        eq("2 messages differ per url problem", 7L, Arrays.stream(SkinStatus.values()).filter(s -> s.name().startsWith("URL_")).map(s -> s.message(0)).distinct().count());

        r.settings = new WardrobeSettings(10, 24, 0, 5, List.of("example.com"), 6.0, true, 2, 2000, 90, false, 1024, false, 2);
        r.clock.addAndGet(31_000); // the OK above started a cooldown
        eq("2 host not on allow-list", SkinStatus.URL_HOST_NOT_ALLOWED, r.add(a, "https://evil.org/a.png", SkinModel.WIDE, null).status());
        eq("2 subdomain of allowed host", SkinStatus.OK, r.add(a, "https://img.example.com/ok.png", SkinModel.WIDE, null).status());

        UUID gone = UUID.randomUUID();
        eq("2 player offline", SkinStatus.PLAYER_OFFLINE, r.add(gone, U1, SkinModel.WIDE, null).status());
        UUID unloaded = UUID.randomUUID();
        r.online.add(unloaded);
        eq("2 wardrobe not loaded", SkinStatus.NOT_LOADED, r.add(unloaded, U1, SkinModel.WIDE, null).status());
        r.close();
    }

    static void namesAndLimitBeforeProcessing() {
        Rig r = new Rig(new WardrobeSettings(2, 24, 0, 5, List.of(), 6.0, true));
        UUID a = r.join();
        r.add(a, U1, SkinModel.WIDE, "One");
        int creates = r.backend.creates.get();
        eq("name taken is caught before processing", SkinStatus.NAME_TAKEN, r.add(a, U2, SkinModel.WIDE, "one").status());
        eq("bad name is caught before processing", SkinStatus.INVALID_NAME, r.add(a, U2, SkinModel.WIDE, "<b>x").status());
        r.add(a, U2, SkinModel.WIDE, "Two");
        creates = r.backend.creates.get();
        eq("limit is caught before processing", SkinStatus.LIMIT_REACHED, r.add(a, U3, SkinModel.WIDE, "Three").status());
        eq("no skin was processed for the refused request", creates, r.backend.creates.get());
        r.close();
    }

    // ------------------------------------------------------------------------------------------------ 3, 11

    static void failures() {
        Rig r = new Rig(settings(0, 5, 2));
        UUID a = r.join();
        eq("setup", SkinStatus.OK, r.add(a, U1, SkinModel.WIDE, "Old").status());
        long activeBefore = r.outfits.active(a).orElseThrow().id();
        int appliedBefore = r.backend.applied.size();

        r.backend.createFailure = SkinStatus.PROCESSING_FAILED;
        SkinResult failed = r.add(a, U2, SkinModel.WIDE, "New");
        eq("3 processing failed", SkinStatus.PROCESSING_FAILED, failed.status());
        eq("3 nothing saved", 1, r.outfits.outfits(a).size());
        eq("3 store untouched", 1, r.outfitStore.outfits.get(a).size());
        eq("3 old outfit still active", activeBefore, r.outfits.active(a).orElseThrow().id());
        eq("3 no skin applied", appliedBefore, r.backend.applied.size());
        eq("3 failure is not cached", 1, r.cache.size());
        eq("3 no failure row on disk", 1, r.cacheStore.rows.size());
        eq("3 no refresh for a failure", 1, r.refreshed.size());
        eq("3 nothing left in flight", 0, r.skins.inFlight());

        r.backend.createFailure = SkinStatus.SKINSRESTORER_UNAVAILABLE;
        eq("3 skinsrestorer unavailable", SkinStatus.SKINSRESTORER_UNAVAILABLE, r.add(a, U2, SkinModel.WIDE, "New").status());

        r.backend.createFailure = null; // a retry works: the failure was not remembered
        eq("3 retry after failure works", SkinStatus.OK, r.add(a, U2, SkinModel.WIDE, "New").status());

        for (SkinStatus s : List.of(SkinStatus.IMAGE_UNREACHABLE, SkinStatus.IMAGE_NOT_A_SKIN, SkinStatus.IMAGE_TOO_LARGE)) {
            Rig p = new Rig(new WardrobeSettings(10, 24, 0, 5, List.of(), 6.0, true, 2, 2000, 90, true, 1024, false, 2));
            UUID b = p.join();
            p.probe.failure = s;
            eq("3 image check: " + s, s, p.add(b, U1, SkinModel.WIDE, null).status());
            eq("3 skinsrestorer not asked after " + s, 0, p.backend.creates.get());
            eq("3 nothing cached after " + s, 0, p.cache.size());
            eq("3 nothing saved after " + s, 0, p.outfits.outfits(b).size());
            p.close();
        }

        // 11: the skin is processed but cannot be applied
        Rig q = new Rig(settings(0, 5, 2));
        UUID c = q.join();
        q.add(c, U1, SkinModel.WIDE, "Keep");
        long keep = q.outfits.active(c).orElseThrow().id();
        q.backend.applyFailure = SkinStatus.APPLY_FAILED;
        eq("11 apply failed (add)", SkinStatus.APPLY_FAILED, q.add(c, U2, SkinModel.WIDE, "Fail").status());
        eq("11 new outfit rolled back", 1, q.outfits.outfits(c).size());
        eq("11 rolled back in the store", 1, q.outfitStore.outfits.get(c).size());
        eq("11 old outfit still active", keep, q.outfits.active(c).orElseThrow().id());
        eq("11 the processed skin stays cached (retry is instant)", 2, q.cache.size());
        eq("11 no refresh after a failed apply", 1, q.refreshed.size());
        q.backend.applyFailure = SkinStatus.PLAYER_OFFLINE;
        eq("11 apply says player gone", SkinStatus.PLAYER_OFFLINE, q.add(c, U3, SkinModel.WIDE, "Gone").status());
        eq("11 and nothing is left behind", 1, q.outfits.outfits(c).size());
        q.backend.applyFailure = null;
        long other = q.add(c, U2, SkinModel.WIDE, "Second").outfit().id();
        q.backend.applyFailure = SkinStatus.APPLY_FAILED;
        eq("11 apply failed (wear)", SkinStatus.APPLY_FAILED, q.await(q.skins.wear(c, keep)).status());
        eq("11 wear failure keeps the active outfit", other, q.outfits.active(c).orElseThrow().id());
        eq("11 wear failure keeps the outfit list", 2, q.outfits.outfits(c).size());
        q.backend.applyFailure = null;
        eq("11 apply works again", SkinStatus.OK, q.await(q.skins.wear(c, keep)).status());
        eq("11 wear makes it active", keep, q.outfits.active(c).orElseThrow().id());
        q.close();
        r.close();
    }

    // ------------------------------------------------------------------------------------------------ 4

    static void timeouts() {
        Rig r = new Rig(settings(0, 1, 2));
        UUID a = r.join();
        r.backend.gate = new CountDownLatch(1);
        long start = System.nanoTime();
        SkinResult res = r.add(a, U1, SkinModel.WIDE, "Slow");
        long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        eq("4 timeout status", SkinStatus.TIMEOUT, res.status());
        eq("4 returned after about 1 second, not 15", true, seconds >= 0 && seconds <= 3);
        eq("4 nothing saved on timeout", 0, r.outfits.outfits(a).size());
        eq("4 nothing applied on timeout", 0, r.backend.applied.size());
        eq("4 the late skin is still being processed", 1, r.skins.inFlight());
        r.backend.gate.countDown(); // SkinsRestorer finally answers
        eq("4 late success is cached", true, r.waitFor(() -> r.cache.size() == 1, 3000));
        eq("4 retry is a cache hit: no new processing", SkinStatus.OK, r.add(a, U1, SkinModel.WIDE, "Slow").status());
        eq("4 processed only once in total", 1, r.backend.creates.get());
        eq("4 flight is gone", 0, r.skins.inFlight());

        // a hung SkinsRestorer must not block the slots forever
        Rig h = new Rig(settings(0, 1, 1));
        UUID b = h.join();
        h.backend.gate = new CountDownLatch(1);
        eq("4 hung: first request times out", SkinStatus.TIMEOUT, h.add(b, U1, SkinModel.WIDE, null).status());
        eq("4 hung: the single slot is busy", 1, h.skins.inFlight());
        eq("4 hung: slot is freed by the hard timeout", true, h.waitFor(() -> h.skins.inFlight() == 0, 5000));
        h.backend.gate.countDown();
        h.close();
        r.close();
    }

    // ------------------------------------------------------------------------------------------------ 5, 6

    static void cacheHitAndMiss() {
        Rig r = new Rig(settings(0, 5, 2));
        UUID a = r.join();
        UUID b = r.join();
        eq("6 miss: processed", SkinStatus.OK, r.add(a, U1, SkinModel.WIDE, "A1").status());
        eq("6 miss: one create", 1, r.backend.creates.get());
        eq("5 hit: other player, same url+model", SkinStatus.OK, r.add(b, U1, SkinModel.WIDE, "B1").status());
        eq("5 hit: no new processing", 1, r.backend.creates.get());
        eq("5 hit: skinsrestorer asked locally", 1, r.backend.lookups.get());
        eq("5 hit: applied to the second player", true, r.backend.applied.contains(b + "|ref:WIDE:" + U1));
        eq("5 hit: canonical url (case of host, fragment)", SkinStatus.OK, r.add(a, "HTTPS://Skins.Example.COM/one.png#top", SkinModel.WIDE, "A2").status());
        eq("5 hit: still one processing", 1, r.backend.creates.get());
        eq("6 miss: other model is another skin", SkinStatus.OK, r.add(a, U1, SkinModel.SLIM, "A3").status());
        eq("6 miss: processed again", 2, r.backend.creates.get());
        eq("6 miss: other url", SkinStatus.OK, r.add(a, U2, SkinModel.WIDE, "A4").status());
        eq("6 miss: processed again (3)", 3, r.backend.creates.get());
        eq("5 three different skins cached", 3, r.cache.size());
        r.add(a, "https://skins.example.com/One.png", SkinModel.WIDE, "A5");
        eq("5 the path keeps its case: another skin", 4, r.cache.size());

        // SkinsRestorer forgot a skin we remember: it is processed again and the cache is repaired
        r.backend.known.clear();
        int before = r.backend.creates.get();
        eq("5 forgotten by skinsrestorer: ok", SkinStatus.OK, r.add(b, U2, SkinModel.WIDE, "B2").status());
        eq("5 forgotten by skinsrestorer: processed again", before + 1, r.backend.creates.get());
        eq("5 cache still has it once", 4, r.cache.size());
        r.close();
    }

    static void cacheLimitsAndStore() {
        AtomicLong clock = new AtomicLong(1_000_000_000_000L);
        MemCacheStore store = new MemCacheStore();
        WardrobeSettings tiny = new WardrobeSettings(10, 24, 0, 5, List.of(), 6.0, true, 2, 3, 90, false, 1024, false, 2);
        SkinCache cache = new SkinCache(LOG, store, () -> tiny, clock::get);
        SkinKey k1 = SkinKey.of(U1, SkinModel.WIDE), k2 = SkinKey.of(U2, SkinModel.WIDE), k3 = SkinKey.of(U3, SkinModel.WIDE), k4 = SkinKey.of(U1, SkinModel.SLIM);
        cache.put(k1, "r1");
        clock.addAndGet(1000);
        cache.put(k2, "r2");
        clock.addAndGet(1000);
        cache.put(k3, "r3");
        eq("cache: three fit", 3, cache.size());
        cache.get(k1); // k1 is now the most recently used, k2 the least
        cache.put(k4, "r4");
        eq("cache: size is capped", 3, cache.size());
        eq("cache: least recently used was evicted", Optional.empty(), cache.get(k2));
        eq("cache: recently used kept", "r1", cache.get(k1).orElseThrow().ref());
        eq("cache: eviction also removed it on disk", false, store.rows.containsKey(k2.id()));
        eq("cache: disk has the same three", Set.of(k1.id(), k3.id(), k4.id()), store.rows.keySet());

        // expiry (90 days)
        clock.addAndGet(TimeUnit.DAYS.toMillis(91));
        eq("cache: expired entry is gone on read", Optional.empty(), cache.get(k3));
        eq("cache: expired entries leave the disk", 0, store.rows.size());

        // restart: a new cache object reads the disk
        WardrobeSettings roomy = settings(0, 5, 2);
        SkinCache first = new SkinCache(LOG, store, () -> roomy, clock::get);
        first.put(k1, "r1");
        first.put(k2, "r2");
        SkinCache second = new SkinCache(LOG, store, () -> roomy, clock::get);
        second.load(Duration.ofSeconds(1));
        eq("cache restart: entries survive", 2, second.size());
        eq("cache restart: ref survives", "r2", second.get(k2).orElseThrow().ref());
        clock.addAndGet(TimeUnit.DAYS.toMillis(100));
        SkinCache third = new SkinCache(LOG, store, () -> roomy, clock::get);
        third.load(Duration.ofSeconds(1));
        eq("cache restart: expired entries are not loaded", 0, third.size());
        eq("cache restart: and are deleted from disk", 0, store.rows.size());

        // a broken disk never breaks the cache
        store.failing = true;
        SkinCache broken = new SkinCache(LOG, store, () -> roomy, clock::get);
        broken.load(Duration.ofSeconds(1));
        eq("cache broken disk: starts empty", 0, broken.size());
        broken.put(k1, "r1");
        eq("cache broken disk: memory still works", "r1", broken.get(k1).orElseThrow().ref());

        // touch is throttled
        store.failing = false;
        clock.set(2_000_000_000_000L);
        SkinCache t = new SkinCache(LOG, store, () -> roomy, clock::get);
        t.put(k1, "r1");
        long saved = store.rows.get(k1.id()).lastUsedAt();
        clock.addAndGet(60_000);
        t.get(k1);
        eq("cache: a hit within 10 minutes does not write", saved, store.rows.get(k1.id()).lastUsedAt());
        clock.addAndGet(SkinCheckAccess.touchIntervalMs());
        t.get(k1);
        eq("cache: a later hit refreshes last use", true, store.rows.get(k1.id()).lastUsedAt() > saved);
    }

    // ------------------------------------------------------------------------------------------------ 7

    static void concurrentSameUrl() {
        Rig r = new Rig(settings(0, 10, 2));
        UUID a = r.join(), b = r.join(), c = r.join();
        r.backend.gate = new CountDownLatch(1);
        CompletableFuture<SkinResult> fa = r.skins.addOutfit(a, U1, SkinModel.WIDE, "A");
        CompletableFuture<SkinResult> fb = r.skins.addOutfit(b, U1, SkinModel.WIDE, "B");
        CompletableFuture<SkinResult> fc = r.skins.addOutfit(c, U1, SkinModel.SLIM, "C"); // other model: other skin
        eq("7 two different skins in flight", true, r.waitFor(() -> r.backend.creates.get() == 2, 3000));
        sleep(100);
        r.pump();
        eq("7 same url+model processed once, other model separately", 2, r.backend.creates.get());
        eq("7 two flights", 2, r.skins.inFlight());
        eq("7 nobody done yet", false, fa.isDone() || fb.isDone() || fc.isDone());
        r.backend.gate.countDown();
        eq("7 A ok", SkinStatus.OK, r.await(fa).status());
        eq("7 B ok (shared flight)", SkinStatus.OK, r.await(fb).status());
        eq("7 C ok", SkinStatus.OK, r.await(fc).status());
        eq("7 still two creates", 2, r.backend.creates.get());
        eq("7 all three skins applied", 3, r.backend.applied.size());
        eq("7 everyone has their own outfit", List.of(1, 1, 1), List.of(r.outfits.outfits(a).size(), r.outfits.outfits(b).size(), r.outfits.outfits(c).size()));
        eq("7 no flight left", 0, r.skins.inFlight());

        // the server-wide limit
        Rig l = new Rig(settings(0, 10, 2));
        UUID p1 = l.join(), p2 = l.join(), p3 = l.join();
        l.backend.gate = new CountDownLatch(1);
        CompletableFuture<SkinResult> f1 = l.skins.addOutfit(p1, U1, SkinModel.WIDE, null);
        CompletableFuture<SkinResult> f2 = l.skins.addOutfit(p2, U2, SkinModel.WIDE, null);
        eq("7 limit: third skin is busy", SkinStatus.BUSY, l.add(p3, U3, SkinModel.WIDE, null).status());
        CompletableFuture<SkinResult> f3 = l.skins.addOutfit(p3, U1, SkinModel.WIDE, null); // joins the running skin
        eq("7 limit: joining a running skin is not busy and starts nothing", 2, l.skins.inFlight());
        l.backend.gate.countDown();
        eq("7 limit: first ok", SkinStatus.OK, l.await(f1).status());
        eq("7 limit: second ok", SkinStatus.OK, l.await(f2).status());
        eq("7 limit: the joiner is ok", SkinStatus.OK, l.await(f3).status());
        eq("7 limit: after they finish the third works", SkinStatus.OK, l.add(p3, U3, SkinModel.WIDE, "Later").status());

        // a full worker queue
        Rig full = new Rig(settings(0, 10, 2));
        full.asyncOverride = task -> { throw new RejectedExecutionException("queue full"); };
        full.start();
        UUID f = full.join();
        eq("7 full queue: cache miss is busy, not an exception", SkinStatus.BUSY, full.add(f, U1, SkinModel.WIDE, null).status());
        eq("7 full queue: nothing in flight", 0, full.skins.inFlight());
        full.close();

        // one request per player
        Rig o = new Rig(settings(0, 10, 2));
        UUID x = o.join();
        o.backend.gate = new CountDownLatch(1);
        CompletableFuture<SkinResult> first = o.skins.addOutfit(x, U1, SkinModel.WIDE, "First");
        eq("7 same player twice: second is refused", SkinStatus.ALREADY_PROCESSING, o.add(x, U2, SkinModel.WIDE, "Second").status());
        o.backend.gate.countDown();
        eq("7 same player: first still completes", SkinStatus.OK, o.await(first).status());
        eq("7 same player: free again afterwards", SkinStatus.OK, o.add(x, U2, SkinModel.WIDE, "Second").status());
        o.close();
        l.close();
        r.close();
    }

    // ------------------------------------------------------------------------------------------------ 8

    static void cooldown() {
        Rig r = new Rig(settings(30, 5, 2));
        UUID a = r.join(), b = r.join();
        eq("8 first request", SkinStatus.OK, r.add(a, U1, SkinModel.WIDE, "One").status());
        SkinResult cd = r.add(a, U2, SkinModel.WIDE, "Two");
        eq("8 second new skin: cooldown", SkinStatus.COOLDOWN, cd.status());
        eq("8 shows the seconds left", 30, cd.retryAfterSeconds());
        eq("8 message names the seconds", true, cd.message().contains("30"));
        eq("8 not processed during cooldown", 1, r.backend.creates.get());
        eq("8 another player is not affected", SkinStatus.OK, r.add(b, U2, SkinModel.WIDE, "Two").status());
        eq("8 a cached skin needs no cooldown", SkinStatus.OK, r.add(a, U1, SkinModel.WIDE, "Again").status());
        r.clock.addAndGet(29_000);
        eq("8 one second left", 1, r.add(a, U3, SkinModel.WIDE, "Three").retryAfterSeconds());
        r.clock.addAndGet(1_500);
        eq("8 after the cooldown", SkinStatus.OK, r.add(a, U3, SkinModel.WIDE, "Three").status());

        Rig f = new Rig(settings(30, 5, 2));
        UUID c = f.join();
        f.backend.createFailure = SkinStatus.PROCESSING_FAILED;
        f.add(c, U1, SkinModel.WIDE, null);
        f.backend.createFailure = null;
        eq("8 a failed processing also starts the cooldown", SkinStatus.COOLDOWN, f.add(c, U2, SkinModel.WIDE, null).status());
        f.close();

        Rig z = new Rig(settings(0, 5, 2));
        UUID d = z.join();
        z.add(d, U1, SkinModel.WIDE, null);
        eq("8 cooldown 0 = none", SkinStatus.OK, z.add(d, U2, SkinModel.WIDE, null).status());
        z.close();
        r.close();
    }

    // ------------------------------------------------------------------------------------------------ 9

    static void disconnectWhileProcessing() {
        Rig r = new Rig(settings(0, 10, 2));
        UUID a = r.join(), b = r.join();
        r.backend.gate = new CountDownLatch(1);
        CompletableFuture<SkinResult> fa = r.skins.addOutfit(a, U1, SkinModel.WIDE, "A");
        CompletableFuture<SkinResult> fb = r.skins.addOutfit(b, U1, SkinModel.WIDE, "B"); // shares the flight
        FakePreview preview = new FakePreview(a);
        r.previews.track(preview);
        r.waitFor(() -> r.backend.creates.get() == 1, 3000);
        r.quit(a); // A disconnects while the skin is being processed
        eq("9 preview of the leaver removed", false, preview.isActive());
        r.backend.gate.countDown();
        SkinResult ra = r.await(fa);
        eq("9 leaver gets PLAYER_OFFLINE, no exception", SkinStatus.PLAYER_OFFLINE, ra.status());
        eq("9 leaver gets no skin", false, r.backend.applied.stream().anyMatch(s -> s.startsWith(a.toString())));
        eq("9 leaver gets no outfit", 0, r.outfitStore.outfits.getOrDefault(a, List.of()).size());
        eq("9 the other player on the same flight is fine", SkinStatus.OK, r.await(fb).status());
        eq("9 the skin was cached anyway", 1, r.cache.size());
        eq("9 no refresh for the leaver", false, r.refreshed.contains(a));
        eq("9 flight finished", 0, r.skins.inFlight());

        r.joinAs(a); // comes back
        r.backend.gate = null;
        eq("9 rejoined player is not stuck as 'busy'", SkinStatus.OK, r.add(a, U1, SkinModel.WIDE, "A").status());
        eq("9 and the cached skin is used: still one processing", 1, r.backend.creates.get());

        // leaves, the request ends, nothing throws on the main thread
        UUID c = r.join();
        r.backend.gate = new CountDownLatch(1);
        CompletableFuture<SkinResult> fc = r.skins.addOutfit(c, U2, SkinModel.WIDE, null);
        r.quit(c);
        r.backend.gate.countDown();
        eq("9 leaver (2): offline", SkinStatus.PLAYER_OFFLINE, r.await(fc).status());
        r.close();
    }

    // ------------------------------------------------------------------------------------------------ 12

    static void restartAndReapply() {
        Rig r = new Rig(settings(0, 5, 2));
        UUID a = r.join();
        r.add(a, U1, SkinModel.SLIM, "Night");
        r.add(a, U2, SkinModel.WIDE, "Day");
        long night = r.outfits.outfits(a).get(0).id();
        r.await(r.skins.wear(a, night));
        int creates = r.backend.creates.get();
        r.backend.applied.clear();
        r.refreshed.clear();

        r.restart(); // new SkinManager, OutfitManager, SkinCache; same databases and same SkinsRestorer storage
        eq("12 restart: memory cache starts from disk", 2, r.cache.size());
        eq("12 restart: outfits not loaded until join", false, r.outfits.isLoaded(a));
        r.joinAs(a);
        eq("12 restart: outfits come back", 2, r.outfits.outfits(a).size());
        eq("12 restart: active outfit remembered", night, r.outfits.active(a).orElseThrow().id());
        SkinResult re = r.await(r.skins.reapplyActive(a));
        eq("12 restart: reapply ok", SkinStatus.OK, re.status());
        eq("12 restart: the right skin is applied", List.of(a + "|ref:SLIM:" + U1), r.backend.applied);
        eq("12 restart: nothing was processed again", creates, r.backend.creates.get());
        eq("12 restart: mirror refresh asked", List.of(a), r.refreshed);

        // skinsrestorer lost its data too: the saved url and model are enough to process again
        r.backend.known.clear();
        r.restart();
        r.joinAs(a);
        eq("12 lost skinsrestorer data: reapply processes again", SkinStatus.OK, r.await(r.skins.reapplyActive(a)).status());
        eq("12 lost skinsrestorer data: one new processing", creates + 1, r.backend.creates.get());

        // no cache and no active outfit
        UUID b = r.join();
        eq("12 no active outfit: NOT_FOUND", SkinStatus.NOT_FOUND, r.await(r.skins.reapplyActive(b)).status());
        eq("12 wear unknown outfit", SkinStatus.NOT_FOUND, r.await(r.skins.wear(b, 999)).status());
        eq("12 wear someone else's outfit", SkinStatus.NOT_FOUND, r.await(r.skins.wear(b, night)).status());
        eq("12 reapply for a player that is not loaded", SkinStatus.NOT_LOADED, r.await(r.skins.reapplyActive(UUID.randomUUID())).status());

        // wear refreshes the stored ref if SkinsRestorer gave another one
        r.backend.known.clear();
        r.backend.known.put(SkinKey.of(U2, SkinModel.WIDE), new ResolvedSkin("ref:new", "h"));
        long day = r.outfits.outfits(a).get(1).id();
        r.cache.put(SkinKey.of(U2, SkinModel.WIDE), "ref:new");
        r.await(r.skins.wear(a, day));
        eq("12 stored ref follows SkinsRestorer", "ref:new", r.outfits.find(a, day).orElseThrow().skinRef());
        eq("12 and it is saved", "ref:new", r.outfitStore.outfits.get(a).get(1).skinRef());

        // shutdown
        r.skins.shutdown();
        eq("12 after shutdown", SkinStatus.SHUTTING_DOWN, r.await(r.skins.addOutfit(a, U3, SkinModel.WIDE, null)).status());
        r.close();
    }

    // ------------------------------------------------------------------------------------------------ previews

    static void previews() {
        SkinPreviews p = new SkinPreviews();
        UUID a = UUID.randomUUID();
        FakePreview one = new FakePreview(a), two = new FakePreview(a);
        p.track(one);
        p.refresh(a);
        eq("preview: refreshSkin called", 1, one.refreshes);
        p.track(two);
        eq("preview: a second preview replaces the first (never two)", false, one.isActive());
        eq("preview: the first was removed once", 1, one.removes);
        p.refresh(a);
        eq("preview: only the new one is refreshed", List.of(1, 1), List.of(one.refreshes, two.refreshes));
        two.remove(); // the mirror closed
        p.refresh(a); // must not throw, must not refresh
        eq("preview: inactive preview is not refreshed", 1, two.refreshes);
        eq("preview: inactive preview is dropped", false, p.hasPreview(a));
        p.refresh(UUID.randomUUID()); // unknown player: nothing happens
        FakePreview three = new FakePreview(a);
        p.track(three);
        p.release(a, two); // an old preview of the closed mirror cannot release the new one
        eq("preview: release of an old preview keeps the new one", true, p.hasPreview(a));
        p.release(a, three);
        eq("preview: released", false, p.hasPreview(a));
        FakePreview four = new FakePreview(a);
        p.track(four);
        p.onQuit(a);
        eq("preview: quit removes it", false, four.isActive());
        p.onQuit(a); // twice is fine
        FakePreview five = new FakePreview(a), six = new FakePreview(UUID.randomUUID());
        p.track(five);
        p.track(six);
        p.removeAll();
        eq("preview: removeAll", List.of(false, false), List.of(five.isActive(), six.isActive()));

        // a broken preview never undoes an applied skin
        Rig r = new Rig(settings(0, 5, 2));
        UUID b = r.join();
        FakePreview broken = new FakePreview(b);
        broken.throwOnRefresh = true;
        r.previews.track(broken);
        SkinResult res = r.add(b, U1, SkinModel.WIDE, null);
        eq("preview: refresh error does not fail the skin", SkinStatus.OK, res.status());
        eq("preview: skin was applied", 1, r.backend.applied.size());
        eq("preview: broken preview is dropped", false, r.previews.hasPreview(b));
        // after the apply the mirror preview shows the new skin: exactly one refresh per apply
        Rig q = new Rig(settings(0, 5, 2));
        UUID c = q.join();
        FakePreview good = new FakePreview(c);
        q.previews.track(good);
        q.add(c, U1, SkinModel.WIDE, null);
        q.add(c, U2, SkinModel.WIDE, null);
        eq("preview: one refresh per applied skin", 2, good.refreshes);
        q.close();
        r.close();
    }

    // ------------------------------------------------------------------------------------------------ png + http

    static byte[] png(int w, int h, int extra) {
        byte[] b = new byte[33 + extra];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
        System.arraycopy(sig, 0, b, 0, sig.length);
        for (int i = 0; i < 4; i++) {
            b[16 + i] = (byte) (w >>> (24 - 8 * i));
            b[20 + i] = (byte) (h >>> (24 - 8 * i));
        }
        b[24] = 8;
        b[25] = 6;
        return b;
    }

    static void pngAndNetwork() throws Exception {
        eq("png: 64x64", PngHeader.Verdict.SKIN, PngHeader.check(png(64, 64, 0)));
        eq("png: 64x32", PngHeader.Verdict.SKIN, PngHeader.check(png(64, 32, 0)));
        eq("png: 128x128", PngHeader.Verdict.WRONG_SIZE, PngHeader.check(png(128, 128, 0)));
        eq("png: 64x63", PngHeader.Verdict.WRONG_SIZE, PngHeader.check(png(64, 63, 0)));
        eq("png: 0x0", PngHeader.Verdict.WRONG_SIZE, PngHeader.check(png(0, 0, 0)));
        eq("png: html", PngHeader.Verdict.NOT_PNG, PngHeader.check("<html><body>not a skin at all</body></html>".getBytes()));
        eq("png: too short", PngHeader.Verdict.NOT_PNG, PngHeader.check(new byte[5]));
        eq("png: null", PngHeader.Verdict.NOT_PNG, PngHeader.check(null));
        byte[] noIhdr = png(64, 64, 0);
        noIhdr[12] = 'X';
        eq("png: first chunk is not IHDR", PngHeader.Verdict.NOT_PNG, PngHeader.check(noIhdr));
        byte[] jpg = new byte[40];
        jpg[0] = (byte) 0xFF;
        jpg[1] = (byte) 0xD8;
        eq("png: jpeg", PngHeader.Verdict.NOT_PNG, PngHeader.check(jpg));

        // key
        eq("key: host case and fragment", SkinKey.of("https://X.io/a.png", SkinModel.WIDE), SkinKey.of("HTTPS://x.IO/a.png#f", SkinModel.WIDE));
        eq("key: model matters", false, SkinKey.of("https://x.io/a.png", SkinModel.WIDE).equals(SkinKey.of("https://x.io/a.png", SkinModel.SLIM)));
        eq("key: path case matters", false, SkinKey.of("https://x.io/A.png", SkinModel.WIDE).equals(SkinKey.of("https://x.io/a.png", SkinModel.WIDE)));
        eq("key: query matters", false, SkinKey.of("https://x.io/a.png?v=1", SkinModel.WIDE).equals(SkinKey.of("https://x.io/a.png?v=2", SkinModel.WIDE)));
        eq("key: id", "SLIM|https://x.io/a.png", SkinKey.of("https://x.io/a.png", SkinModel.SLIM).id());

        // addresses
        for (String bad : List.of("127.0.0.1", "10.1.2.3", "192.168.0.9", "172.16.5.5", "169.254.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "::1", "fc00::1", "fe80::1")) {
            eq("address " + bad + " is not public", false, SkinCheckAccess.isPublic(java.net.InetAddress.getByName(bad)));
        }
        for (String good : List.of("8.8.8.8", "1.1.1.1", "93.184.216.34", "2606:4700:4700::1111")) {
            eq("address " + good + " is public", true, SkinCheckAccess.isPublic(java.net.InetAddress.getByName(good)));
        }

        // the real probe, no network needed
        HttpSkinImageProbe strict = new HttpSkinImageProbe(1024, Duration.ofSeconds(2));
        eq("probe: http refused", SkinStatus.URL_NOT_HTTPS, status(strict, "http://example.com/a.png"));
        eq("probe: localhost refused", SkinStatus.URL_PRIVATE_HOST, status(strict, "https://localhost/a.png"));
        eq("probe: unknown host", SkinStatus.IMAGE_UNREACHABLE, status(strict, "https://no-such-host.invalid/a.png"));
        eq("probe: credentials refused", SkinStatus.URL_HAS_CREDENTIALS, status(strict, "https://u:p@example.com/a.png"));

        // against a local server (test mode allows http + loopback)
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/skin.png", ex -> send(ex, 200, png(64, 64, 200)));
        server.createContext("/old.png", ex -> send(ex, 200, png(64, 32, 0)));
        server.createContext("/big.png", ex -> send(ex, 200, png(128, 128, 0)));
        server.createContext("/page.html", ex -> send(ex, 200, "<html>hello</html>".getBytes()));
        server.createContext("/missing", ex -> send(ex, 404, new byte[0]));
        server.createContext("/boom", ex -> send(ex, 500, new byte[0]));
        server.createContext("/go", ex -> { ex.getResponseHeaders().add("Location", "/skin.png"); send(ex, 302, new byte[0]); });
        server.createContext("/loop", ex -> { ex.getResponseHeaders().add("Location", "/loop"); send(ex, 302, new byte[0]); });
        server.createContext("/nolocation", ex -> send(ex, 302, new byte[0]));
        server.createContext("/huge", ex -> send(ex, 200, png(64, 64, 5000)));
        server.createContext("/chunked", ex -> {
            ex.sendResponseHeaders(200, 0); // chunked: no Content-Length
            try (OutputStream out = ex.getResponseBody()) {
                out.write(png(64, 64, 0));
                for (int i = 0; i < 10; i++) out.write(new byte[1024]);
            } catch (IOException ignored) { }
        });
        server.createContext("/slow", ex -> { sleep(2500); try { send(ex, 200, png(64, 64, 0)); } catch (IOException ignored) { } });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            SkinImageProbe local = SkinCheckAccess.localProbe(client, 4096, Duration.ofMillis(800));
            eq("probe: valid skin", null, status(local, base + "/skin.png"));
            eq("probe: old 64x32 skin", null, status(local, base + "/old.png"));
            eq("probe: wrong size", SkinStatus.IMAGE_NOT_A_SKIN, status(local, base + "/big.png"));
            eq("probe: html page", SkinStatus.IMAGE_NOT_A_SKIN, status(local, base + "/page.html"));
            eq("probe: 404", SkinStatus.IMAGE_UNREACHABLE, status(local, base + "/missing"));
            eq("probe: 500", SkinStatus.IMAGE_UNREACHABLE, status(local, base + "/boom"));
            eq("probe: redirect is followed", null, status(local, base + "/go"));
            eq("probe: redirect loop stops", SkinStatus.IMAGE_UNREACHABLE, status(local, base + "/loop"));
            eq("probe: redirect without Location", SkinStatus.IMAGE_UNREACHABLE, status(local, base + "/nolocation"));
            eq("probe: too big (Content-Length)", SkinStatus.IMAGE_TOO_LARGE, status(local, base + "/huge"));
            eq("probe: too big (chunked, no length)", SkinStatus.IMAGE_TOO_LARGE,
                    status(SkinCheckAccess.localProbe(client, 1000, Duration.ofMillis(800)), base + "/chunked"));
            eq("probe: slow server", SkinStatus.TIMEOUT, status(local, base + "/slow"));
            eq("probe: connection refused", SkinStatus.IMAGE_UNREACHABLE, status(local, "http://127.0.0.1:1/a.png"));
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    static void send(com.sun.net.httpserver.HttpExchange ex, int code, byte[] body) throws IOException {
        ex.sendResponseHeaders(code, code == 302 ? -1 : (body.length == 0 ? -1 : body.length));
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
        ex.close();
    }

    static SkinStatus status(SkinImageProbe probe, String url) {
        try {
            probe.probe(url);
            return null;
        } catch (SkinException e) {
            return e.status();
        }
    }

    // ------------------------------------------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        LOG.setLevel(Level.OFF);
        validAndApplied();
        invalidUrls();
        namesAndLimitBeforeProcessing();
        failures();
        timeouts();
        cacheHitAndMiss();
        cacheLimitsAndStore();
        concurrentSameUrl();
        cooldown();
        disconnectWhileProcessing();
        restartAndReapply();
        previews();
        pngAndNetwork();
        System.out.println(checks + " checks, " + failures + " failures");
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
