package com.veyrion.wardrobe.skin;

import com.veyrion.wardrobe.model.SkinModel;
import java.util.Optional;
import java.util.UUID;

/**
 * The only door to SkinsRestorer. SkinManager knows nothing else about it, so everything around it can be checked
 * without a server. The real one is {@link SkinsRestorerIntegration}.
 */
public interface SkinBackend {

    /** Looks for a skin SkinsRestorer already holds. Never asks MineSkin. NOT on the main thread. */
    Optional<ResolvedSkin> lookup(String url, SkinModel model) throws SkinException;

    /** Finds the skin, or has SkinsRestorer process it (network, rate limited). NOT on the main thread. */
    ResolvedSkin create(String url, SkinModel model) throws SkinException;

    /** Puts the skin on the player and makes SkinsRestorer remember it for them. MAIN thread only. */
    void apply(UUID player, ResolvedSkin skin) throws SkinException;
}
