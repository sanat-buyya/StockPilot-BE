package com.stockpilot.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Uniform error envelope: { "error": { "code", "message", "details"? } }. */
public record ApiError(Body error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(String code, String message, Object details) {}

    public static ApiError of(ErrorCode code, String message, Object details) {
        return new ApiError(new Body(code.name(), message, details));
    }
}
