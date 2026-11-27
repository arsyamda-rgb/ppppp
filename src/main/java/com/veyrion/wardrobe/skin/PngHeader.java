package com.veyrion.wardrobe.skin;

/**
 * Reads only the first bytes of a PNG (signature + IHDR). It never decodes pixels, so a "decompression bomb" image
 * cannot hurt it. A Minecraft skin is 64x64 (current) or 64x32 (old).
 */
public final class PngHeader {

    public enum Verdict { SKIN, NOT_PNG, WRONG_SIZE }

    /** Bytes needed: 8 signature + 4 length + 4 "IHDR" + 4 width + 4 height. */
    public static final int NEEDED = 24;

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private PngHeader() {}

    public static Verdict check(byte[] data) {
        if (data == null || data.length < NEEDED) {
            return Verdict.NOT_PNG;
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (data[i] != SIGNATURE[i]) {
                return Verdict.NOT_PNG;
            }
        }
        if (int32(data, 8) != 13 || data[12] != 'I' || data[13] != 'H' || data[14] != 'D' || data[15] != 'R') {
            return Verdict.NOT_PNG;
        }
        int width = int32(data, 16);
        int height = int32(data, 20);
        return width == 64 && (height == 64 || height == 32) ? Verdict.SKIN : Verdict.WRONG_SIZE;
    }

    private static int int32(byte[] d, int at) {
        return ((d[at] & 0xFF) << 24) | ((d[at + 1] & 0xFF) << 16) | ((d[at + 2] & 0xFF) << 8) | (d[at + 3] & 0xFF);
    }
}
