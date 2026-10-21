package com.delivery.payment.exception;

import org.springframework.http.HttpStatus;

public class SimulatedChaosException extends RuntimeException {
    private final HttpStatus httpStatus;

    public SimulatedChaosException(HttpStatus httpStatus, String message) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
