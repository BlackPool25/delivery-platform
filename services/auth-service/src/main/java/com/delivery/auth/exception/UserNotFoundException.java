package com.delivery.auth.exception;

import java.util.UUID;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(UUID userId) {
        super("User not found with ID: " + userId);
    }

    public UserNotFoundException(String message) {
        super(message);
    }
}
