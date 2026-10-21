package com.delivery.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RequestOtpRequest(
        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$", message = "Phone number must be a valid E.164 formatted number")
        String phone
) {}
