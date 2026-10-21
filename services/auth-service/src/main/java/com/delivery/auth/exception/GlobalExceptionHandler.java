package com.delivery.auth.exception;

import com.delivery.auth.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex) {
        log.warn("Unauthorized token access attempt: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("UNAUTHORIZED", ex.getMessage()));
    }

    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<ErrorResponse> handleInvalidOtp(InvalidOtpException ex) {
        log.warn("Invalid OTP supplied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_OTP", ex.getMessage()));
    }

    @ExceptionHandler(OtpExpiredException.class)
    public ResponseEntity<ErrorResponse> handleOtpExpired(OtpExpiredException ex) {
        log.warn("Expired OTP supplied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.GONE)
                .body(new ErrorResponse("OTP_EXPIRED", ex.getMessage()));
    }

    @ExceptionHandler(OtpMaxAttemptsExceededException.class)
    public ResponseEntity<ErrorResponse> handleOtpMaxAttempts(OtpMaxAttemptsExceededException ex) {
        log.warn("Max OTP attempts exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorResponse("MAX_ATTEMPTS_EXCEEDED", ex.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        log.warn("User not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        String errorMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Validation failure: {}", errorMessage);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("VALIDATION_ERROR", errorMessage));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled internal server error in auth-service", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_SERVER_ERROR", ex.getMessage()));
    }
}
