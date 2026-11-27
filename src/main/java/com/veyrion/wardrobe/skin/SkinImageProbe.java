package com.veyrion.wardrobe.skin;

/** Looks at the image behind a skin URL before SkinsRestorer gets it. */
public interface SkinImageProbe {

    /**
     * Blocks (network): NOT on the main thread.
     *
     * @throws SkinException IMAGE_UNREACHABLE, IMAGE_NOT_A_SKIN, IMAGE_TOO_LARGE or TIMEOUT
     */
    void probe(String url) throws SkinException;
}
