package com.veyrion.wardrobe.storage;

/** The wardrobe database could not be opened or used. The message is meant for the server console. */
public final class StorageException extends Exception {

    private static final long serialVersionUID = 1L;

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
