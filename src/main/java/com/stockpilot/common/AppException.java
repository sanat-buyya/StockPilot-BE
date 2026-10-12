package com.stockpilot.common;

/** Business/authorization failure that maps to a well-formed API error response. */
public class AppException extends RuntimeException {
    private final ErrorCode code;
    private final transient Object details;

    public AppException(ErrorCode code, String message) {
        this(code, message, null);
    }

    public AppException(ErrorCode code, String message, Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public Object details() {
        return details;
    }

    public static AppException notFound(String what) {
        return new AppException(ErrorCode.NOT_FOUND, what + " not found");
    }

    public static AppException forbidden() {
        return new AppException(ErrorCode.FORBIDDEN, "You do not have permission to perform this action");
    }

    public static AppException validation(String message, Object details) {
        return new AppException(ErrorCode.VALIDATION_ERROR, message, details);
    }
}
