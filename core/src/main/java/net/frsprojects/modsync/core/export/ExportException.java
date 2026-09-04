package net.frsprojects.modsync.core.export;

/** An export could not be produced. The message is shown to the player who ran the command. */
public class ExportException extends Exception {

    private static final long serialVersionUID = 1L;

    public ExportException(String message) {
        super(message);
    }

    public ExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
