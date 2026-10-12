package com.stockpilot.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Uniform success envelope: { "data": ..., "meta": { page, pageSize, total, totalPages } }. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(T data, Meta meta) {

    public record Meta(int page, int pageSize, long total, int totalPages) {}

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data, null);
    }

    public static <T> ApiResponse<List<T>> page(List<T> rows, int page, int pageSize, long total) {
        int totalPages = (int) Math.max(1, (total + pageSize - 1) / pageSize);
        return new ApiResponse<>(rows, new Meta(page, pageSize, total, totalPages));
    }
}
