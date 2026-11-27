package com.veyrion.wardrobe.skin;

/** A skin step failed for a known reason. Thrown by the backend and the image check, caught by SkinManager. */
public final class SkinException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final SkinStatus status;

    public SkinException(SkinStatus status, String detail) {
        super(status + ": " + detail, null, false, false); // no stack trace: these are expected
        this.status = status;
    }

    public SkinException(SkinStatus status, String detail, Throwable cause) {
        super(status + ": " + detail, cause);
        this.status = status;
    }

    public SkinStatus status() {
        return status;
    }
}
