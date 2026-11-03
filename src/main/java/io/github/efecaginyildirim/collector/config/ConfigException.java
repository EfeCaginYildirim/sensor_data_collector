package io.github.efecaginyildirim.collector.config;

import java.util.List;

/**
 * Thrown when the configuration cannot be loaded or is invalid.
 *
 * <p>All problems found are reported at once, so the operator can fix the file in one pass
 * instead of discovering errors one restart at a time.
 */
public final class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<String> problems;

    /**
     * Creates an exception listing every configuration problem found.
     *
     * @param problems human-readable descriptions, one per invalid or missing key
     */
    public ConfigException(List<String> problems) {
        super("Invalid configuration:\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    /**
     * Creates an exception for a configuration source that could not be read.
     *
     * @param message description of the failure
     * @param cause   underlying I/O error
     */
    public ConfigException(String message, Throwable cause) {
        super(message, cause);
        this.problems = List.of(message);
    }

    /** @return the individual problems, in the order they were detected */
    public List<String> problems() {
        return problems;
    }
}
