package com.stockpilot.common;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private ResponseEntity<ApiError> respond(ErrorCode code, String message, Object details) {
        return ResponseEntity.status(code.status()).body(ApiError.of(code, message, details));
    }

    @ExceptionHandler(AppException.class)
    ResponseEntity<ApiError> app(AppException e) {
        return respond(e.code(), e.getMessage(), e.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        List<Map<String, String>> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
                .toList();
        return respond(ErrorCode.VALIDATION_ERROR, "Validation failed", details);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> invalidParams(HandlerMethodValidationException e) {
        List<Map<String, String>> details = e.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(err -> Map.of("field", r.getMethodParameter().getParameterName() == null ? "param"
                                : r.getMethodParameter().getParameterName(), "message", String.valueOf(err.getDefaultMessage()))))
                .toList();
        return respond(ErrorCode.VALIDATION_ERROR, "Validation failed", details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraint(ConstraintViolationException e) {
        List<Map<String, String>> details = e.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList();
        return respond(ErrorCode.VALIDATION_ERROR, "Validation failed", details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class})
    ResponseEntity<ApiError> unreadable(Exception e) {
        return respond(ErrorCode.VALIDATION_ERROR, "Malformed or missing request data", null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(ErrorCode.VALIDATION_ERROR, "Invalid value for '" + e.getName() + "'",
                List.of(Map.of("field", e.getName(), "message", "Invalid value")));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(AccessDeniedException e) {
        return respond(ErrorCode.FORBIDDEN, "You do not have permission to perform this action", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute(NoResourceFoundException e) {
        return respond(ErrorCode.NOT_FOUND, "Route not found", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405).body(ApiError.of(ErrorCode.VALIDATION_ERROR, "Method not allowed", null));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> media(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(415).body(ApiError.of(ErrorCode.VALIDATION_ERROR, "Unsupported content type", null));
    }

    /** Unique-constraint violations that survive to the edge become friendly, specific conflicts. */
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> duplicate(DuplicateKeyException e) {
        String constraint = DbErrors.uniqueViolation(e);
        if ("products_workspace_sku_uq".equals(constraint)) {
            return respond(ErrorCode.DUPLICATE_SKU, "A product with this SKU already exists in this workspace",
                    List.of(Map.of("field", "sku", "message", "SKU already exists in this workspace")));
        }
        if ("users_email_uq".equals(constraint)) {
            return respond(ErrorCode.EMAIL_TAKEN, "An account with this email already exists", null);
        }
        log.warn("Unhandled unique violation on constraint {}", constraint);
        return respond(ErrorCode.CONFLICT, "The request conflicts with existing data", null);
    }

    /** Last line of defence: a DB constraint tripped that business code did not translate. Never leak SQL. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException e) {
        log.warn("Unhandled data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return respond(ErrorCode.CONFLICT, "The request conflicts with existing data", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return respond(ErrorCode.INTERNAL_ERROR, "Something went wrong. Please try again.", null);
    }
}
