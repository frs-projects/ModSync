package net.frsprojects.modsync.core.apply;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The list of file operations to perform once Minecraft has exited.
 *
 * <p>Stored as tab-separated lines rather than JSON on purpose: the applier runs in a bare
 * JVM after the game is gone, and Gson is only available because Minecraft bundles it. A
 * format the applier can parse with {@code String.split} keeps that dependency at zero.
 *
 * <p>The path sandbox rejects tabs, newlines and every other control character, so no
 * escaping is needed for the paths that reach here.
 */
public final class Journal {

    private static final String HEADER = "#modsync-journal";
    private static final int VERSION = 1;

    private final List<JournalOp> ops;

    public Journal(List<JournalOp> ops) {
        this.ops = List.copyOf(ops);
    }

    public List<JournalOp> ops() {
        return ops;
    }

    public boolean isEmpty() {
        return ops.isEmpty();
    }

    /**
     * Writes the journal, then moves it into place, so a crash mid-write can never leave a
     * half-parsed journal that the applier would act on.
     */
    public void writeTo(Path journalFile) throws IOException {
        Files.createDirectories(journalFile.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\t').append(VERSION).append('\n');
        for (JournalOp op : ops) {
            sb.append(op.encode()).append('\n');
        }
        Path tmp = journalFile.resolveSibling(journalFile.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, journalFile,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tmp, journalFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** @return the journal at this path, or an empty journal when there is none */
    public static Journal readFrom(Path journalFile) throws IOException {
        if (!Files.isRegularFile(journalFile)) {
            return new Journal(List.of());
        }
        List<String> lines = Files.readAllLines(journalFile, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return new Journal(List.of());
        }
        String[] header = lines.get(0).split("\t", -1);
        if (header.length != 2 || !header[0].equals(HEADER)) {
            throw new JournalException("Not a ModSync journal: " + journalFile);
        }
        int version;
        try {
            version = Integer.parseInt(header[1]);
        } catch (NumberFormatException e) {
            throw new JournalException("Malformed journal version in " + journalFile);
        }
        if (version > VERSION) {
            throw new JournalException(
                "Journal version " + version + " was written by a newer ModSync; "
                    + "update ModSync or delete " + journalFile);
        }

        List<JournalOp> ops = new ArrayList<>(lines.size() - 1);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                ops.add(JournalOp.decode(line));
            } catch (JournalException e) {
                throw new JournalException(
                    journalFile + " line " + (i + 1) + ": " + e.getMessage(), e);
            }
        }
        return new Journal(ops);
    }
}
