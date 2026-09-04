package net.frsprojects.modsync.core.net;

import java.io.IOException;

/** A download that failed on every mirror, or produced content that did not verify. */
public class DownloadException extends IOException {
    private static final long serialVersionUID = 1L;

    public DownloadException(String message) {
        super(message);
    }

    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
