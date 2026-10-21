package com.delivery.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record DeliverOrderRequest(
        @NotBlank(message = "Verification PIN is required")
        @Pattern(regexp = "^\\d{6}$", message = "Verification PIN must be a 6-digit number")
        String verificationPin
) {}
