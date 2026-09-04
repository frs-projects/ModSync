package net.frsprojects.modsync.core.export;

import java.nio.file.Path;

/** Progress callbacks for an export. Implementations must be thread-safe. */
public interface ExportProgress {

    /** No-op sink for tests and headless runs. */
    ExportProgress NONE = new ExportProgress() {};

    /** A human-readable step, safe to print straight to chat. */
    default void message(String text) {}

    /**
     * The export wrote {@code output}. {@code unresolved} counts files whose download URL
     * could not be determined, which is the notice an admin has to act on before publishing.
     */
    default void finished(Path output, int total, int resolved, int unresolved) {}

    /** The export produced nothing. */
    default void failed(String reason) {}
}
