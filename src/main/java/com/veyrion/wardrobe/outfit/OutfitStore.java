package com.veyrion.wardrobe.outfit;

import com.veyrion.wardrobe.model.Outfit;
import com.veyrion.wardrobe.model.SkinModel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.Nullable;

/**
 * Where outfits are saved. Every method may run on another thread and completes its future on that thread; the
 * caller moves back to the main thread. The limit, the unique name and the owner check are enforced here, in one
 * step each, so two requests can never break them.
 */
public interface OutfitStore {

    /** All outfits of a player plus the id of the active one (null = none). */
    record Wardrobe(List<Outfit> outfits, @Nullable Long activeId) {}

    enum InsertStatus { CREATED, LIMIT_REACHED, NAME_TAKEN }

    record Inserted(InsertStatus status, @Nullable Outfit outfit) {}

    enum RenameStatus { RENAMED, NOT_FOUND, NAME_TAKEN }

    CompletableFuture<Wardrobe> load(UUID owner);

    CompletableFuture<Inserted> insert(UUID owner, String name, String url, SkinModel model,
                                       @Nullable String skinRef, int maxOutfits);

    CompletableFuture<RenameStatus> rename(UUID owner, long outfitId, String newName);

    /** @return false if the player has no such outfit. Deleting the active outfit leaves the player with none active. */
    CompletableFuture<Boolean> delete(UUID owner, long outfitId);

    /** @param outfitId null = no active outfit; @return false if the id is not an outfit of this player */
    CompletableFuture<Boolean> setActive(UUID owner, @Nullable Long outfitId);

    /** Remembers the SkinsRestorer identifier of a processed skin. @return false if the outfit is not theirs */
    CompletableFuture<Boolean> setSkinRef(UUID owner, long outfitId, String skinRef);
}
