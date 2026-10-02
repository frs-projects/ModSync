package net.frsprojects.modsync.core.apply;

import net.frsprojects.modsync.core.profile.ModSyncPaths;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Starts {@link JournalApplier} as a separate process that waits for this one to exit.
 *
 * <p>The game cannot swap its own jars (Windows keeps them locked while it runs), so the
 * applier runs in a fresh JVM: the same {@code java} binary as the game, with only ModSync's
 * own jar on the classpath, which carries every class the applier needs.
 */
public final class ApplierLauncher {

    /** How long the helper waits for the game to exit before leaving the journal alone. */
    static final long TIMEOUT_SECONDS = 300;

    private ApplierLauncher() {}

    /**
     * @param classpath ModSync's jar, or its output directories in a dev run
     * @param pid the process to wait for, normally the running game
     */
    public static List<String> command(Path javaBinary, List<Path> classpath, ModSyncPaths paths,
            long pid) {
        return List.of(
            javaBinary.toString(),
            "-cp", classpath.stream().map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator)),
            JournalApplier.class.getName(),
            paths.gameDir().toString(),
            "--wait-for-pid", Long.toString(pid),
            "--timeout-seconds", Long.toString(TIMEOUT_SECONDS));
    }

    /**
     * Launches the helper for this process, logging to {@code modsync/applier.log}. Meant to be
     * called as the game shuts down, so the helper's wait is short.
     */
    public static Process launch(List<Path> classpath, ModSyncPaths paths) throws IOException {
        Path log = paths.root().resolve("applier.log");
        Files.createDirectories(log.getParent());
        ProcessBuilder pb = new ProcessBuilder(
            command(currentJava(), classpath, paths, ProcessHandle.current().pid()));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        return pb.start();
    }

    /** The java binary running this process, falling back to {@code java.home}. */
    static Path currentJava() {
        return ProcessHandle.current().info().command()
            .map(Path::of)
            .filter(Files::isExecutable)
            .orElseGet(() -> {
                Path bin = Path.of(System.getProperty("java.home"), "bin");
                Path exe = bin.resolve("java.exe");
                return Files.isRegularFile(exe) ? exe : bin.resolve("java");
            });
    }
}
