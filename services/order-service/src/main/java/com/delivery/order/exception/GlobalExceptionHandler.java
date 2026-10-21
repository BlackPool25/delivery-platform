package com.delivery.order.exception;

import com.delivery.order.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFound(OrderNotFoundException ex) {
        log.warn("Order not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(DuplicateOrderException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateOrder(DuplicateOrderException ex) {
        log.warn("Duplicate order: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(HttpStatus.CONFLICT.value(), "Conflict", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(LeaseExpiredOrClaimedException.class)
    public ResponseEntity<ErrorResponse> handleLeaseExpiredOrClaimed(LeaseExpiredOrClaimedException ex) {
        log.warn("Lease conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(HttpStatus.CONFLICT.value(), "Conflict", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(InvalidVerificationPinException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPin(InvalidVerificationPinException ex) {
        log.warn("Invalid verification PIN: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(IllegalOrderStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalOrderStateException ex) {
        log.warn("Illegal order state transition: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(HttpStatus.CONFLICT.value(), "Conflict", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        String errorMsg = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        log.warn("Validation error: {}", errorMsg);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(HttpStatus.BAD_REQUEST.value(), "Bad Request", errorMsg, Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
        log.error("Unhandled server exception: ", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal Server Error",
                        "An unexpected error occurred", Instant.now()));
    }
}
