package com.delivery.catalog.exception;

public class InvalidSpatialDataException extends RuntimeException {
    public InvalidSpatialDataException(String message) {
        super(message);
    }

    public InvalidSpatialDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
