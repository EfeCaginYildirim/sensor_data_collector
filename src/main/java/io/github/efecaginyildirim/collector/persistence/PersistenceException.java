package io.github.efecaginyildirim.collector.persistence;

/** A storage operation failed; any transaction in progress has been rolled back. */
public final class PersistenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what was being done
     * @param cause   underlying error
     */
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
