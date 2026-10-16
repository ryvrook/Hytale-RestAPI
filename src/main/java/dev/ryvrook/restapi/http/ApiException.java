package dev.ryvrook.restapi.http;

/**
 * Thrown by a handler to answer with an error. The code is a short stable word scripts can test, such as
 * {@code player_not_found}, the message is a sentence for people.
 */
public final class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message, null, false, false);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(400, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(404, code, message);
    }
}
