package com.veyrion.wardrobe.skin;

import com.veyrion.wardrobe.model.SkinModel;
import java.util.Optional;
import java.util.UUID;
import net.skinsrestorer.api.SkinsRestorer;
import net.skinsrestorer.api.SkinsRestorerProvider;
import net.skinsrestorer.api.property.SkinVariant;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The real {@link SkinBackend}: SkinsRestorer 15.x through its API, never through commands, and without our own
 * MineSkin or signing code.
 * <p>
 * Flow (from docs/DESIGN.md, read from the 15.12.6 jar in Stage 1):
 * <ol>
 *   <li>{@code SkinStorage.findSkinData(url, variant)}: a skin SkinsRestorer already holds (no network)</li>
 *   <li>else {@code SkinStorage.findOrCreateSkinData(url, variant)}: SkinsRestorer asks MineSkin (network)</li>
 *   <li>{@code PlayerStorage.setSkinIdOfPlayer(uuid, identifier)}: it remembers the skin for that player</li>
 *   <li>{@code getSkinApplier(Player.class).applySkin(player, property)}: puts it on the player</li>
 * </ol>
 * VERIFY AGAINST THE JAR (the 15.12.6 jar was not available when this file was written): the lines marked
 * {@code // VERIFY} use names that Stage 1 did not record: {@code SkinVariant}'s package, the result type's
 * {@code getIdentifier()} / {@code getProperty()}, and the {@code Optional} return type. See the javap commands in
 * docs/DESIGN.md. Everything else in Stage 2 only touches the {@link SkinBackend} interface.
 */
public final class SkinsRestorerIntegration implements SkinBackend {

    private final SkinsRestorer api;

    /** @throws IllegalStateException when SkinsRestorer's API is not available (DependencyGuard checks this first) */
    public SkinsRestorerIntegration() {
        this.api = SkinsRestorerProvider.get();
    }

    /** "WIDE" in the GUI is SkinsRestorer's CLASSIC; SLIM is SLIM. */
    private static SkinVariant variant(SkinModel model) {
        return model == SkinModel.SLIM ? SkinVariant.SLIM : SkinVariant.CLASSIC;
    }

    @Override
    public Optional<ResolvedSkin> lookup(String url, SkinModel model) {
        try {
            var found = api.getSkinStorage().findSkinData(url, variant(model)); // VERIFY: Optional<InputDataResult>
            return found.map(SkinsRestorerIntegration::resolved);
        } catch (Exception | LinkageError e) {
            throw failure("look up", e);
        }
    }

    @Override
    public ResolvedSkin create(String url, SkinModel model) {
        try {
            var created = api.getSkinStorage().findOrCreateSkinData(url, variant(model)); // VERIFY: Optional<InputDataResult>
            return created.map(SkinsRestorerIntegration::resolved)
                    .orElseThrow(() -> new SkinException(SkinStatus.PROCESSING_FAILED, "SkinsRestorer returned no skin"));
        } catch (SkinException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            throw failure("process", e);
        }
    }

    @Override
    public void apply(UUID playerId, ResolvedSkin skin) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            throw new SkinException(SkinStatus.PLAYER_OFFLINE, playerId + " left before the skin was applied");
        }
        try {
            var result = (net.skinsrestorer.api.property.InputDataResult) skin.handle(); // VERIFY: package and type name
            api.getPlayerStorage().setSkinIdOfPlayer(playerId, result.getIdentifier()); // VERIFY: getIdentifier()
            api.getSkinApplier(Player.class).applySkin(player, result.getProperty()); // VERIFY: getProperty()
        } catch (Exception | LinkageError e) {
            throw new SkinException(SkinStatus.APPLY_FAILED, String.valueOf(e), e);
        }
    }

    private static ResolvedSkin resolved(net.skinsrestorer.api.property.InputDataResult result) { // VERIFY: type name
        return new ResolvedSkin(String.valueOf(result.getIdentifier()), result); // the text is for humans; it is never parsed back
    }

    private static SkinException failure(String what, Throwable e) {
        // SkinsRestorer reports MineSkin errors (bad image, rate limit, service down) as exceptions. The message is
        // for the console; the player gets the generic text.
        SkinStatus status = e instanceof LinkageError || e instanceof IllegalStateException
                ? SkinStatus.SKINSRESTORER_UNAVAILABLE : SkinStatus.PROCESSING_FAILED;
        return new SkinException(status, "SkinsRestorer could not " + what + " the skin: " + e, e);
    }
}
