package com.veyrion.wardrobe.skin;

/**
 * A skin SkinsRestorer has processed. {@code ref} is its identifier as text (stored in the outfit and the cache).
 * {@code handle} belongs to the backend (for SkinsRestorer: what it needs to apply the skin); nothing else reads it.
 */
public record ResolvedSkin(String ref, Object handle) {}
