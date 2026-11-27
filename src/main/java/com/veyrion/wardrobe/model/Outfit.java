package com.veyrion.wardrobe.model;

import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One saved outfit. {@code owner} is the player who owns and wears it, never the owner of a mirror.
 * {@code skinRef} is the SkinsRestorer identifier of the processed skin (null until it was processed once);
 * the skin data itself lives in SkinsRestorer's own storage.
 */
public record Outfit(long id, UUID owner, String name, String url, SkinModel model, @Nullable String skinRef,
                     long createdAt) {

    public Outfit withName(String newName) {
        return new Outfit(id, owner, newName, url, model, skinRef, createdAt);
    }

    public Outfit withSkinRef(@Nullable String newRef) {
        return new Outfit(id, owner, name, url, model, newRef, createdAt);
    }
}
