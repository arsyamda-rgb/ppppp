package com.veyrion.wardrobe.skin;

import com.veyrion.wardrobe.model.Outfit;
import org.jetbrains.annotations.Nullable;

/** How a skin request ended. {@code outfit} is set on success. {@code retryAfterSeconds} is only for COOLDOWN. */
public record SkinResult(SkinStatus status, @Nullable Outfit outfit, int retryAfterSeconds) {

    public boolean ok() {
        return status == SkinStatus.OK;
    }

    /** Plain text for the player. */
    public String message() {
        return status.message(retryAfterSeconds);
    }

    static SkinResult ok(Outfit outfit) {
        return new SkinResult(SkinStatus.OK, outfit, 0);
    }

    static SkinResult fail(SkinStatus status) {
        return new SkinResult(status, null, 0);
    }
}
