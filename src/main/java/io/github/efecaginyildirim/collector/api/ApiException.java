package io.github.efecaginyildirim.collector.api;

/**
 * A failed call to the vendor API.
 *
 * <p>Carries enough information for the caller to decide what to do: the HTTP status (or
 * {@code 0} for transport failures), whether retrying could help, and how many attempts were
 * made before giving up.
 */
public final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String endpoint;
    private final int statusCode;
    private final boolean retryable;
    private final int attempts;

    ApiException(String endpoint, int statusCode, boolean retryable, int attempts, String message, Throwable cause) {
        super(endpoint + ": " + message + " (after " + attempts + " attempt" + (attempts == 1 ? "" : "s") + ")", cause);
        this.endpoint = endpoint;
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.attempts = attempts;
    }

    /** @return endpoint path without query, e.g. {@code /trend-history} */
    public String endpoint() {
        return endpoint;
    }

    /** @return HTTP status, or {@code 0} if no response was received or the body was unusable */
    public int statusCode() {
        return statusCode;
    }

    /** @return whether the failure is transient, i.e. a later run may succeed */
    public boolean retryable() {
        return retryable;
    }

    /** @return number of HTTP attempts made */
    public int attempts() {
        return attempts;
    }
}
