package com.veyrion.wardrobe.skin;

import com.veyrion.character.api.CharacterPreview;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Knows which {@link CharacterPreview} (VeyrionCharacter) shows which player, so a new skin can be redrawn on it
 * with {@code refreshSkin()}. It makes no preview itself and no entity: the mirror (Stage 4) creates the preview
 * with {@code VeyrionCharacterAPI.previews()} and hands it over with {@link #track}.
 * <ul>
 *   <li>One preview per player: tracking a second one removes the first, so two copies never stand side by side.</li>
 *   <li>Safe on disconnect and when the mirror closes: {@code CharacterPreview} methods are idempotent, and an
 *       inactive preview is dropped instead of refreshed.</li>
 * </ul>
 * Main thread only.
 */
public final class SkinPreviews {

    private final Map<UUID, CharacterPreview> byPlayer = new HashMap<>();

    /** Starts tracking. A different preview already tracked for the same player is removed first. */
    public void track(CharacterPreview preview) {
        CharacterPreview old = byPlayer.put(preview.subject(), preview);
        if (old != null && old != preview) {
            old.remove();
        }
    }

    /** Stops tracking (the mirror closed). Does NOT remove the preview: whoever made it removes it. */
    public void release(UUID player, CharacterPreview preview) {
        byPlayer.remove(player, preview);
    }

    /** The player left: removes their preview and forgets it. */
    public void onQuit(UUID player) {
        CharacterPreview preview = byPlayer.remove(player);
        if (preview != null) {
            preview.remove();
        }
    }

    /** Redraws the player's preview with the skin they wear now. Does nothing if there is none. */
    public void refresh(UUID player) {
        CharacterPreview preview = byPlayer.get(player);
        if (preview == null) {
            return;
        }
        if (!preview.isActive()) {
            byPlayer.remove(player, preview);
            return;
        }
        try {
            preview.refreshSkin(); // removes itself if the subject went offline
        } catch (RuntimeException e) {
            byPlayer.remove(player, preview);
            throw e;
        }
        if (!preview.isActive()) {
            byPlayer.remove(player, preview);
        }
    }

    public boolean hasPreview(UUID player) {
        return byPlayer.containsKey(player);
    }

    /** Plugin disable: removes every preview. */
    public void removeAll() {
        List<CharacterPreview> all = new ArrayList<>(byPlayer.values());
        byPlayer.clear();
        all.forEach(CharacterPreview::remove);
    }
}
