package com.veyrion.wardrobe.skin;

/** Every way a skin request can end. {@link #OK} is the only success. */
public enum SkinStatus {
    OK,
    // the URL text (SkinUrlValidator)
    URL_EMPTY, URL_TOO_LONG, URL_MALFORMED, URL_NOT_HTTPS, URL_HAS_CREDENTIALS, URL_PRIVATE_HOST, URL_HOST_NOT_ALLOWED,
    // the image behind the URL
    IMAGE_UNREACHABLE, IMAGE_NOT_A_SKIN, IMAGE_TOO_LARGE,
    // SkinsRestorer
    PROCESSING_FAILED, SKINSRESTORER_UNAVAILABLE, APPLY_FAILED,
    // limits
    TIMEOUT, COOLDOWN, BUSY, ALREADY_PROCESSING,
    // the player and the wardrobe
    PLAYER_OFFLINE, NOT_LOADED, INVALID_NAME, NAME_TAKEN, LIMIT_REACHED, NOT_FOUND, SAVE_FAILED, SHUTTING_DOWN;

    /** Plain text for the player. {@code retryAfterSeconds} is only used by COOLDOWN. */
    public String message(int retryAfterSeconds) {
        return switch (this) {
            case OK -> "Skin applied.";
            case URL_EMPTY -> "Type the link of a skin image.";
            case URL_TOO_LONG -> "That link is too long.";
            case URL_MALFORMED -> "That is not a valid link.";
            case URL_NOT_HTTPS -> "The link must start with https://";
            case URL_HAS_CREDENTIALS -> "The link must not contain a user name or password.";
            case URL_PRIVATE_HOST -> "That address cannot be used. Use a public image link.";
            case URL_HOST_NOT_ALLOWED -> "Skins from that website are not allowed on this server.";
            case IMAGE_UNREACHABLE -> "The image could not be downloaded. Check that the link opens a PNG file.";
            case IMAGE_NOT_A_SKIN -> "That image is not a Minecraft skin. It must be a PNG of 64x64 or 64x32 pixels.";
            case IMAGE_TOO_LARGE -> "That image file is too big for a skin.";
            case PROCESSING_FAILED -> "The skin could not be processed. Try again later or use another image.";
            case SKINSRESTORER_UNAVAILABLE -> "The skin service is not available right now. Try again later.";
            case APPLY_FAILED -> "The skin was processed but could not be put on you. Nothing was saved.";
            case TIMEOUT -> "Processing took too long. Try again in a moment; it may be ready by then.";
            case COOLDOWN -> "Wait " + Math.max(1, retryAfterSeconds) + " more second(s) before a new skin.";
            case BUSY -> "Too many skins are being processed. Try again in a moment.";
            case ALREADY_PROCESSING -> "Your last skin request is still running.";
            case PLAYER_OFFLINE -> "You are not online.";
            case NOT_LOADED -> "Your wardrobe is still loading. Try again in a moment.";
            case INVALID_NAME -> "That outfit name is not allowed.";
            case NAME_TAKEN -> "You already have an outfit with that name.";
            case LIMIT_REACHED -> "You have no room for more outfits.";
            case NOT_FOUND -> "That outfit does not exist.";
            case SAVE_FAILED -> "The skin is on, but your outfit could not be saved. Tell an admin.";
            case SHUTTING_DOWN -> "The server is stopping.";
        };
    }
}
