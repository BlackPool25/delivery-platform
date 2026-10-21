package com.delivery.catalog.exception;

import java.util.UUID;

public class DishNotFoundException extends RuntimeException {
    public DishNotFoundException(UUID id) {
        super("Dish not found with id: " + id);
    }
}
