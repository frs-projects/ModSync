package net.frsprojects.modsync.core.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalTest {

    @TempDir
    Path dir;

    @Test
    void roundTripsThroughDisk() throws IOException {
        Journal journal = new Journal(List.of(
            JournalOp.mkdir("mods"),
            JournalOp.move("mods/a.jar", "q/a.jar"),
            JournalOp.link("a".repeat(128), "mods/b.jar")));

        Path file = dir.resolve("pending.tsv");
        journal.writeTo(file);
        assertEquals(journal.ops(), Journal.readFrom(file).ops());
    }

    /** The applier runs in a bare JVM, so the format must stay parseable without a library. */
    @Test
    void isPlainTabSeparatedText() throws IOException {
        Path file = dir.resolve("pending.tsv");
        new Journal(List.of(JournalOp.mkdir("mods"))).writeTo(file);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals("#modsync-journal\t1", lines.get(0));
        assertEquals("MKDIR\tmods", lines.get(1));
    }

    @Test
    void missingFileReadsAsEmpty() throws IOException {
        assertTrue(Journal.readFrom(dir.resolve("nope.tsv")).isEmpty());
    }

    @Test
    void rejectsAForeignFile() throws IOException {
        Path file = dir.resolve("pending.tsv");
        Files.writeString(file, "not a journal\n");
        assertThrows(JournalException.class, () -> Journal.readFrom(file));
    }

    @Test
    void rejectsAJournalFromANewerModSync() throws IOException {
        Path file = dir.resolve("pending.tsv");
        Files.writeString(file, "#modsync-journal\t99\nMKDIR\tmods\n");
        JournalException e = assertThrows(JournalException.class, () -> Journal.readFrom(file));
        assertTrue(e.getMessage().contains("newer ModSync"));
    }

    @Test
    void reportsTheLineNumberOfABadOperation() throws IOException {
        Path file = dir.resolve("pending.tsv");
        Files.writeString(file, "#modsync-journal\t1\nMKDIR\tmods\nFROBNICATE\tx\n");
        JournalException e = assertThrows(JournalException.class, () -> Journal.readFrom(file));
        assertTrue(e.getMessage().contains("line 3"), e.getMessage());
    }
}
