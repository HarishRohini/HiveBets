package dev.hivebets.common.web;

/** An error with an HTTP status and a stable code, rendered as {"error", "message"}. */
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static ApiException validation(String m) { return new ApiException(400, "VALIDATION", m); }
    public static ApiException unauthenticated() { return new ApiException(401, "UNAUTHENTICATED", "Sign in first."); }
    public static ApiException forbidden(String m) { return new ApiException(403, "FORBIDDEN", m); }
    public static ApiException notFound(String m) { return new ApiException(404, "NOT_FOUND", m); }
    public static ApiException conflict(String code, String m) { return new ApiException(409, code, m); }
}
