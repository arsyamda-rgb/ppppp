package com.veyrion.wardrobe;

import com.veyrion.wardrobe.config.WardrobeSettings;
import com.veyrion.wardrobe.integration.DependencyRules;
import com.veyrion.wardrobe.model.Outfit;
import com.veyrion.wardrobe.model.SkinModel;
import com.veyrion.wardrobe.outfit.OutfitManager;
import com.veyrion.wardrobe.outfit.OutfitStore;
import com.veyrion.wardrobe.skin.SkinUrlValidator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

/** Pure checks: outfit rules, URL validation, dependency version rules. No Bukkit, no database. See README. */
public final class WardrobeCheck {

    static int checks = 0;
    static int failures = 0;

    static void eq(String what, Object expected, Object actual) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) {
            failures++;
            System.out.println("FAIL " + what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    /** In-memory store with the same rules as the SQL one (owner check, unique name per owner, limit). */
    static final class MemoryStore implements OutfitStore {
        final Map<UUID, List<Outfit>> outfits = new HashMap<>();
        final Map<UUID, Long> active = new HashMap<>();
        long nextId = 1;
        boolean failing;
        CompletableFuture<Wardrobe> heldLoad; // when set, load() waits for it

        private <T> CompletableFuture<T> result(T value) {
            if (failing) return CompletableFuture.failedFuture(new IllegalStateException("disk full"));
            return CompletableFuture.completedFuture(value);
        }

        @Override public CompletableFuture<Wardrobe> load(UUID o) {
            if (heldLoad != null) return heldLoad;
            return result(new Wardrobe(List.copyOf(outfits.getOrDefault(o, List.of())), active.get(o)));
        }
        @Override public CompletableFuture<Inserted> insert(UUID o, String name, String url, SkinModel m, @Nullable String ref, int max) {
            List<Outfit> list = outfits.computeIfAbsent(o, k -> new ArrayList<>());
            if (list.stream().anyMatch(x -> x.name().equalsIgnoreCase(name))) return result(new Inserted(InsertStatus.NAME_TAKEN, null));
            if (list.size() >= max) return result(new Inserted(InsertStatus.LIMIT_REACHED, null));
            Outfit created = new Outfit(nextId++, o, name, url, m, ref, 1L);
            list.add(created);
            return result(new Inserted(InsertStatus.CREATED, created));
        }
        @Override public CompletableFuture<RenameStatus> rename(UUID o, long id, String n) {
            List<Outfit> list = outfits.getOrDefault(o, new ArrayList<>());
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id() == id) {
                    for (Outfit other : list) if (other.id() != id && other.name().equalsIgnoreCase(n)) return result(RenameStatus.NAME_TAKEN);
                    list.set(i, list.get(i).withName(n));
                    return result(RenameStatus.RENAMED);
                }
            }
            return result(RenameStatus.NOT_FOUND);
        }
        @Override public CompletableFuture<Boolean> delete(UUID o, long id) {
            boolean removed = outfits.getOrDefault(o, new ArrayList<>()).removeIf(x -> x.id() == id);
            if (removed && Long.valueOf(id).equals(active.get(o))) active.remove(o);
            return result(removed);
        }
        @Override public CompletableFuture<Boolean> setActive(UUID o, @Nullable Long id) {
            if (id == null) { active.remove(o); return result(true); }
            if (outfits.getOrDefault(o, List.of()).stream().noneMatch(x -> x.id() == id)) return result(false);
            active.put(o, id);
            return result(true);
        }
        @Override public CompletableFuture<Boolean> setSkinRef(UUID o, long id, String ref) {
            List<Outfit> list = outfits.getOrDefault(o, new ArrayList<>());
            for (int i = 0; i < list.size(); i++) if (list.get(i).id() == id) { list.set(i, list.get(i).withSkinRef(ref)); return result(true); }
            return result(false);
        }
    }

    static OutfitManager manager(MemoryStore store, int max) {
        WardrobeSettings s = new WardrobeSettings(max, 12, 0, 40, List.of(), 6.0, true);
        return new OutfitManager(Logger.getLogger("check"), store, Runnable::run, () -> s);
    }

    public static void main(String[] args) {
        Logger.getLogger("check").setLevel(java.util.logging.Level.OFF);
        outfitRules();
        mirrorOwnerIsNotTheUser();
        urlRules();
        dependencyRules();
        System.out.println(checks + " checks, " + failures + " failures");
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : "SOME CHECKS FAILED");
        if (failures != 0) System.exit(1);
    }

    static void outfitRules() {
        MemoryStore store = new MemoryStore();
        OutfitManager m = manager(store, 3);
        UUID p = UUID.randomUUID();

        eq("not loaded: add", OutfitManager.Status.NOT_LOADED, m.add(p, "A", "https://x.io/a.png", SkinModel.WIDE, null).join().status());
        m.load(p).join();
        eq("loaded", true, m.isLoaded(p));
        eq("empty at first", 0, m.outfits(p).size());
        eq("default name 1", "Outfit 1", m.defaultName(p));

        OutfitManager.Result a = m.add(p, "  Knight   Armor ", "https://x.io/a.png", SkinModel.SLIM, "ref-a").join();
        eq("add ok", OutfitManager.Status.OK, a.status());
        eq("name cleaned", "Knight Armor", a.outfit().name());
        eq("model kept", SkinModel.SLIM, a.outfit().model());
        eq("skin ref kept", "ref-a", a.outfit().skinRef());
        eq("owner is the user", p, a.outfit().owner());
        eq("blank name gets default", "Outfit 1", m.add(p, " ", "https://x.io/b.png", SkinModel.WIDE, null).join().outfit().name());
        eq("duplicate name (case)", OutfitManager.Status.NAME_TAKEN, m.add(p, "KNIGHT ARMOR", "https://x.io/c.png", SkinModel.WIDE, null).join().status());
        eq("bad chars", OutfitManager.Status.INVALID_NAME, m.add(p, "<red>Hack", "https://x.io/c.png", SkinModel.WIDE, null).join().status());
        eq("section sign", OutfitManager.Status.INVALID_NAME, m.add(p, "\u00a7cRed", "https://x.io/c.png", SkinModel.WIDE, null).join().status());
        eq("too long", OutfitManager.Status.INVALID_NAME, m.add(p, "x".repeat(13), "https://x.io/c.png", SkinModel.WIDE, null).join().status());
        eq("unicode letters ok", OutfitManager.Status.OK, m.add(p, "Ksatria", "https://x.io/c.png", SkinModel.WIDE, null).join().status());
        eq("limit reached", OutfitManager.Status.LIMIT_REACHED, m.add(p, "Fourth", "https://x.io/d.png", SkinModel.WIDE, null).join().status());
        eq("three kept", 3, m.outfits(p).size());

        long id = a.outfit().id();
        eq("rename ok", OutfitManager.Status.OK, m.rename(p, id, "Paladin").join().status());
        eq("renamed in cache", "Paladin", m.find(p, id).orElseThrow().name());
        eq("rename to taken", OutfitManager.Status.NAME_TAKEN, m.rename(p, id, "outfit 1").join().status());
        eq("rename invalid", OutfitManager.Status.INVALID_NAME, m.rename(p, id, "").join().status());
        eq("rename unknown", OutfitManager.Status.NOT_FOUND, m.rename(p, 9999, "Zed").join().status());
        eq("rename to own name with other case", OutfitManager.Status.OK, m.rename(p, id, "PALADIN").join().status());

        eq("no active at first", Optional.empty(), m.active(p));
        eq("set active", OutfitManager.Status.OK, m.setActive(p, id).join().status());
        eq("active is it", id, m.active(p).orElseThrow().id());
        eq("set active unknown", OutfitManager.Status.NOT_FOUND, m.setActive(p, 424242).join().status());
        eq("active unchanged after failed set", id, m.active(p).orElseThrow().id());
        eq("skin ref saved", OutfitManager.Status.OK, m.setSkinRef(p, id, "ref-new").join().status());
        eq("skin ref in cache", "ref-new", m.find(p, id).orElseThrow().skinRef());

        eq("delete active", OutfitManager.Status.OK, m.delete(p, id).join().status());
        eq("active cleared by delete", Optional.empty(), m.active(p));
        eq("delete again", OutfitManager.Status.NOT_FOUND, m.delete(p, id).join().status());
        eq("room again after delete", OutfitManager.Status.OK, m.add(p, "Again", "https://x.io/e.png", SkinModel.WIDE, null).join().status());

        // reload from the store: same data (restart)
        m.unload(p);
        eq("unloaded", false, m.isLoaded(p));
        eq("no data after unload", 0, m.outfits(p).size());
        m.load(p).join();
        eq("outfits after reload", 3, m.outfits(p).size());

        // store failure: ERROR, cache unchanged
        store.failing = true;
        eq("store failure on add", OutfitManager.Status.ERROR, m.delete(p, m.outfits(p).get(0).id()).join().status());
        eq("cache unchanged after failure", 3, m.outfits(p).size());
        store.failing = false;

        // a load that finishes after the player left must not fill the cache
        MemoryStore slow = new MemoryStore();
        OutfitManager m2 = manager(slow, 3);
        UUID q = UUID.randomUUID();
        slow.heldLoad = new CompletableFuture<>();
        CompletableFuture<Void> loading = m2.load(q);
        m2.unload(q);
        slow.heldLoad.complete(new OutfitStore.Wardrobe(List.of(), null));
        loading.join();
        eq("late load dropped", false, m2.isLoaded(q));
    }

    /** Player A owns the mirror, player B uses it: only B's wardrobe is read or changed. */
    static void mirrorOwnerIsNotTheUser() {
        MemoryStore store = new MemoryStore();
        OutfitManager m = manager(store, 5);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        m.load(a).join();
        m.load(b).join();
        long aOutfit = m.add(a, "Alpha", "https://x.io/a.png", SkinModel.WIDE, null).join().outfit().id();
        m.setActive(a, aOutfit).join();

        long bOutfit = m.add(b, "Beta", "https://x.io/b.png", SkinModel.SLIM, null).join().outfit().id();
        eq("B sees only B", List.of("Beta"), m.outfits(b).stream().map(Outfit::name).toList());
        eq("A still sees only A", List.of("Alpha"), m.outfits(a).stream().map(Outfit::name).toList());
        eq("B cannot rename A's outfit", OutfitManager.Status.NOT_FOUND, m.rename(b, aOutfit, "Stolen").join().status());
        eq("B cannot delete A's outfit", OutfitManager.Status.NOT_FOUND, m.delete(b, aOutfit).join().status());
        eq("B cannot activate A's outfit", OutfitManager.Status.NOT_FOUND, m.setActive(b, aOutfit).join().status());
        eq("A's active untouched", aOutfit, m.active(a).orElseThrow().id());
        eq("B has no active", Optional.empty(), m.active(b));
        eq("A's name untouched", "Alpha", m.find(a, aOutfit).orElseThrow().name());
        // the store enforces the owner check too, not just the cache
        eq("store: B cannot delete A's", false, store.delete(b, aOutfit).join());
        eq("store: B cannot activate A's", false, store.setActive(b, aOutfit).join());
        eq("store: A's outfit still there", 1, store.outfits.get(a).size());
        eq("same name for two players is fine", OutfitManager.Status.OK, m.add(b, "Alpha", "https://x.io/c.png", SkinModel.WIDE, null).join().status());
        eq("B active still empty", Optional.empty(), m.active(b));
        eq("B's own", true, m.find(b, bOutfit).isPresent());
    }

    static void urlRules() {
        SkinUrlValidator open = new SkinUrlValidator(List.of());
        eq("ok url", true, open.validate("https://textures.minecraft.net/texture/abc").valid());
        eq("trimmed", "https://i.imgur.com/x.png", open.validate("  https://i.imgur.com/x.png \n").url());
        eq("null", SkinUrlValidator.Problem.EMPTY, open.validate(null).problem());
        eq("blank", SkinUrlValidator.Problem.EMPTY, open.validate("   ").problem());
        eq("http", SkinUrlValidator.Problem.NOT_HTTPS, open.validate("http://x.io/a.png").problem());
        eq("ftp", SkinUrlValidator.Problem.NOT_HTTPS, open.validate("ftp://x.io/a.png").problem());
        eq("no scheme", SkinUrlValidator.Problem.MALFORMED, open.validate("x.io/a.png").problem());
        eq("spaces inside", SkinUrlValidator.Problem.MALFORMED, open.validate("https://x.io/my skin.png").problem());
        eq("credentials", SkinUrlValidator.Problem.HAS_CREDENTIALS, open.validate("https://user:pw@x.io/a.png").problem());
        eq("too long", SkinUrlValidator.Problem.TOO_LONG, open.validate("https://x.io/" + "a".repeat(600)).problem());
        for (String host : new String[] {"localhost", "127.0.0.1", "10.1.2.3", "192.168.0.5", "172.16.0.1", "172.31.255.1", "169.254.169.254", "0.0.0.0", "100.64.0.1", "intranet", "printer.local", "[::1]"}) {
            eq("private " + host, SkinUrlValidator.Problem.PRIVATE_HOST, open.validate("https://" + host + "/a.png").problem());
        }
        eq("172.32 is public", true, open.validate("https://172.32.0.1/a.png").valid());
        eq("8.8.8.8 is public", true, open.validate("https://8.8.8.8/a.png").valid());

        SkinUrlValidator limited = new SkinUrlValidator(List.of("imgur.com", "Textures.Minecraft.Net"));
        eq("allowed host", true, limited.validate("https://imgur.com/a.png").valid());
        eq("allowed subdomain", true, limited.validate("https://i.imgur.com/a.png").valid());
        eq("allowed host, case", true, limited.validate("https://textures.minecraft.net/t/1").valid());
        eq("other host", SkinUrlValidator.Problem.HOST_NOT_ALLOWED, limited.validate("https://evil.io/a.png").problem());
        eq("lookalike host", SkinUrlValidator.Problem.HOST_NOT_ALLOWED, limited.validate("https://notimgur.com/a.png").problem());
        eq("suffix trick", SkinUrlValidator.Problem.HOST_NOT_ALLOWED, limited.validate("https://imgur.com.evil.io/a.png").problem());
    }

    static void dependencyRules() {
        eq("parse 4.0.3", new DependencyRules.Version(4, 0, 3), DependencyRules.parse("4.0.3"));
        eq("parse snapshot", new DependencyRules.Version(15, 12, 6), DependencyRules.parse("15.12.6-SNAPSHOT"));
        eq("parse short", new DependencyRules.Version(4, 1, 0), DependencyRules.parse("4.1"));
        eq("parse v prefix", new DependencyRules.Version(5, 0, 0), DependencyRules.parse("v5"));
        eq("parse garbage", null, DependencyRules.parse("beta"));
        eq("parse null", null, DependencyRules.parse(null));

        Map<String, DependencyRules.Installed> ok = new HashMap<>();
        ok.put("VeyrionCharacter", new DependencyRules.Installed(true, "0.1.0"));
        ok.put("ItemsAdder", new DependencyRules.Installed(true, "4.0.3"));
        ok.put("SkinsRestorer", new DependencyRules.Installed(true, "15.12.6"));
        eq("all fine", List.of(), DependencyRules.problems(ok::get));

        Map<String, DependencyRules.Installed> m = new HashMap<>(ok);
        m.remove("SkinsRestorer");
        eq("missing SkinsRestorer", 1, DependencyRules.problems(m::get).size());
        eq("message names it", true, DependencyRules.problems(m::get).get(0).startsWith("SkinsRestorer is not installed"));
        m.put("SkinsRestorer", new DependencyRules.Installed(false, "15.12.6"));
        eq("SkinsRestorer disabled", true, DependencyRules.problems(m::get).get(0).contains("did not enable"));
        m.put("SkinsRestorer", new DependencyRules.Installed(true, "14.2.0"));
        eq("SkinsRestorer too old", true, DependencyRules.problems(m::get).get(0).contains("not supported"));
        m.put("SkinsRestorer", new DependencyRules.Installed(true, "16.0.0"));
        eq("SkinsRestorer too new (unverified)", 1, DependencyRules.problems(m::get).size());
        m.put("SkinsRestorer", new DependencyRules.Installed(true, "15.0.0"));
        m.put("ItemsAdder", new DependencyRules.Installed(true, "3.6.1"));
        eq("ItemsAdder 3.x", true, DependencyRules.problems(m::get).get(0).startsWith("ItemsAdder 3.6.1 is not supported"));
        m.put("ItemsAdder", new DependencyRules.Installed(true, "4.0.10"));
        eq("ItemsAdder 4.0.10 fine", List.of(), DependencyRules.problems(m::get));
        m.put("VeyrionCharacter", new DependencyRules.Installed(false, "0.1.0"));
        m.remove("ItemsAdder");
        eq("two problems at once", 2, DependencyRules.problems(m::get).size());
        eq("none installed", 3, DependencyRules.problems(n -> null).size());
    }
}
