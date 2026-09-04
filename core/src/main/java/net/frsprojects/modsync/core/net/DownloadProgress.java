package net.frsprojects.modsync.core.net;

/** Progress callbacks for the download UI. Implementations must be thread-safe. */
public interface DownloadProgress {

    /** No-op sink for tests and headless runs. */
    DownloadProgress NONE = new DownloadProgress() {};

    /** A file has started downloading. */
    default void started(String label, long expectedBytes) {}

    /** More bytes have arrived for {@code label}. */
    default void advanced(String label, long bytesSoFar, long expectedBytes) {}

    /** A file finished and verified. */
    default void finished(String label) {}

    /** A file failed on one mirror and another will be tried. */
    default void retrying(String label, String failedUrl, String reason, int attempt) {}

    /** A file failed on every mirror. */
    default void failed(String label, String reason) {}
}
