package net.frsprojects.modsync.core.security;

/** Raised when a manifest asks for a path the client refuses to write to. */
public class SandboxException extends Exception {
    private static final long serialVersionUID = 1L;

    public SandboxException(String message) {
        super(message);
    }
}
