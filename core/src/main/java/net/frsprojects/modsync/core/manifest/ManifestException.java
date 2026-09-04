package net.frsprojects.modsync.core.manifest;

/** Raised when a manifest is syntactically valid JSON but semantically unusable. */
public class ManifestException extends Exception {
    private static final long serialVersionUID = 1L;

    public ManifestException(String message) {
        super(message);
    }

    public ManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
