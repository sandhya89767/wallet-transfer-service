package com.paytm.wallet.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.code(), exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiError> handleInvalidRequest(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("invalid_request", "Request validation failed", Instant.now()));
    }

    @ExceptionHandler({TransientDataAccessException.class, TransactionTimedOutException.class,
            CannotCreateTransactionException.class, org.springframework.jdbc.CannotGetJdbcConnectionException.class})
    public ResponseEntity<ApiError> handleUnavailable(Exception exception) {
        // Exception messages/stack traces can contain credentials from malformed JDBC URLs.
        // Log only the exception type for safe pool/transaction failure diagnosis.
        log.atWarn().addKeyValue("event", "request.retryable_failure")
            .addKeyValue("exception_type", exception.getClass().getSimpleName())
            .log("Database operation unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
                .body(new ApiError("temporarily_unavailable", "Retry with the same idempotency key", Instant.now()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleMissing(NoResourceFoundException exception) {
        return ResponseEntity.status(404).body(new ApiError("not_found", "Endpoint not found", Instant.now()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(405).body(new ApiError("method_not_allowed", "Method not allowed", Instant.now()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMedia(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(415).body(new ApiError("unsupported_media_type", "Use application/json", Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        log.error("Unhandled request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("internal_error", "An unexpected error occurred", Instant.now()));
    }

    public record ApiError(String code, String message, Instant timestamp, String correlationId) {
        public ApiError(String code, String message, Instant timestamp) {
            this(code, message, timestamp, MDC.get("correlation_id"));
        }
    }
}