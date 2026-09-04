package net.frsprojects.modsync.core.apply;

import java.util.Locale;

/**
 * One filesystem operation, deferred until after the game has exited.
 *
 * <p>Deliberately only three verbs, and none of them destroys anything: {@link Kind#MOVE}
 * relocates a file (quarantine and restore), {@link Kind#LINK} materialises content that is
 * already safe in the cache, {@link Kind#MKDIR} prepares a directory. There is no delete —
 * anything displaced goes to quarantine, so a wrong plan is always recoverable.
 *
 * <p>Paths are game-directory-relative so the journal survives the game directory moving.
 */
public record JournalOp(Kind kind, String a, String b) {

    public enum Kind {
        /** {@code a} = relative source, {@code b} = relative destination. */
        MOVE,
        /** {@code a} = SHA-512 of cached content, {@code b} = relative destination. */
        LINK,
        /** {@code a} = relative directory. */
        MKDIR
    }

    public static JournalOp move(String from, String to) {
        return new JournalOp(Kind.MOVE, from, to);
    }

    public static JournalOp link(String sha512, String to) {
        return new JournalOp(Kind.LINK, sha512.toLowerCase(Locale.ROOT), to);
    }

    public static JournalOp mkdir(String dir) {
        return new JournalOp(Kind.MKDIR, dir, null);
    }

    String encode() {
        return b == null ? kind.name() + "\t" + a : kind.name() + "\t" + a + "\t" + b;
    }

    static JournalOp decode(String line) throws JournalException {
        String[] parts = line.split("\t", -1);
        Kind kind;
        try {
            kind = Kind.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new JournalException("Unknown journal verb '" + parts[0] + "'");
        }
        int expected = kind == Kind.MKDIR ? 2 : 3;
        if (parts.length != expected) {
            throw new JournalException(
                kind + " expects " + (expected - 1) + " arguments, got " + (parts.length - 1));
        }
        return new JournalOp(kind, parts[1], expected == 3 ? parts[2] : null);
    }
}
