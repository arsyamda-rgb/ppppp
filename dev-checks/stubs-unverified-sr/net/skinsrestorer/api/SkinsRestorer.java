package net.skinsrestorer.api;
/** UNVERIFIED stand-in written from memory + docs/DESIGN.md, only so the code can be compiled here. NOT the real API. */
public interface SkinsRestorer {
    net.skinsrestorer.api.storage.SkinStorage getSkinStorage();
    net.skinsrestorer.api.storage.PlayerStorage getPlayerStorage();
    <P> SkinApplier<P> getSkinApplier(Class<P> type);
    interface SkinApplier<P> { void applySkin(P player, net.skinsrestorer.api.property.SkinProperty property) throws Exception; }
}
