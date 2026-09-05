package com.qualityops.api.common;

import com.qualityops.api.common.ratelimit.RateLimitExceededException;
import com.qualityops.api.identity.exception.InvalidRefreshTokenException;
import com.qualityops.api.scm.exception.RepositoryHostNotAllowedException;
import com.qualityops.api.scm.exception.RepositoryRefUnresolvableException;
import com.qualityops.api.scm.exception.ScmAuthException;
import com.qualityops.api.scm.exception.ScmCredentialUnresolvedException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> new ApiError.FieldError(e.getField(), e.getDefaultMessage()))
            .toList();
        return new ApiResponse<>(null, null,
            new ApiError("VALIDATION_ERROR", "Validation failed", fieldErrors));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.error("VALIDATION_ERROR", ex.getMessage());
    }

    // Spring 6.1: a failed @Pattern/@Size on a @RequestHeader / @RequestParam in a
    // @Validated controller raises HandlerMethodValidationException.
    @ExceptionHandler(HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleHandlerValidation(HandlerMethodValidationException ex) {
        return ApiResponse.error("VALIDATION_ERROR", "Request parameter validation failed");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleMissingHeader(MissingRequestHeaderException ex) {
        return ApiResponse.error("VALIDATION_ERROR", "Missing required header: " + ex.getHeaderName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleMissingParam(MissingServletRequestParameterException ex) {
        return ApiResponse.error("VALIDATION_ERROR", "Missing required parameter: " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ApiResponse.error("VALIDATION_ERROR", "Invalid value for parameter: " + ex.getName());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleConstraintViolation(ConstraintViolationException ex) {
        return ApiResponse.error("VALIDATION_ERROR", "Validation failed");
    }

    @ExceptionHandler({BadCredentialsException.class, InvalidRefreshTokenException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<?> handleUnauthorized(RuntimeException ex) {
        // Generic message intentionally — do not reveal which credential was wrong
        return ApiResponse.error("UNAUTHORIZED", "Invalid credentials or token");
    }

    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<?> handleForbidden(AccessDeniedException ex) {
        return ApiResponse.error("FORBIDDEN", "Access denied");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<?> handleConflict(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMessage());
        return ApiResponse.error("CONFLICT", "Resource already exists");
    }

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<?> handleNotFound(NotFoundException ex) {
        return ApiResponse.error(ex.code(), ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<?> handleDomainConflict(ConflictException ex) {
        return ApiResponse.error(ex.code(), ex.getMessage());
    }

    // ADR-009 §4/§11 — SCM preflight failures.
    @ExceptionHandler(RepositoryRefUnresolvableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ApiResponse<?> handleRefUnresolvable(RepositoryRefUnresolvableException ex) {
        return ApiResponse.error("REPOSITORY_REF_UNRESOLVABLE", ex.getMessage());
    }

    @ExceptionHandler({RepositoryHostNotAllowedException.class, ScmAuthException.class,
        ScmCredentialUnresolvedException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleScmBadRequest(RuntimeException ex) {
        return ApiResponse.error("VALIDATION_ERROR", ex.getMessage());
    }

    // ADR-008 §6: rate limiting. ResponseEntity so Retry-After is re-asserted even
    // if the interceptor's header was dropped by the error dispatch.
    @ExceptionHandler(RateLimitExceededException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public ResponseEntity<ApiResponse<?>> handleRateLimited(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
            .body(ApiResponse.error("RATE_LIMITED", "Rate limit exceeded for " + ex.getOperation()));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred");
    }
}
