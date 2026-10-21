package com.delivery.simulator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeliverOrderRequest(
        @NotBlank(message = "Verification PIN is mandatory")
        @Size(min = 6, max = 6, message = "Verification PIN must be exactly 6 characters")
        String verificationPin
) {}
