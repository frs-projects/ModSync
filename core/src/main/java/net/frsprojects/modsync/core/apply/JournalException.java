package net.frsprojects.modsync.core.apply;

import java.io.IOException;

/** Raised when the pending-operations journal is unreadable or an operation cannot run. */
public class JournalException extends IOException {
    private static final long serialVersionUID = 1L;

    public JournalException(String message) {
        super(message);
    }

    public JournalException(String message, Throwable cause) {
        super(message, cause);
    }
}
