package com.delivery.auth.dto;

import com.delivery.auth.entity.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpRequest(
        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^\\+?[1-9]\\d{1,14}$", message = "Phone number must be a valid E.164 formatted number")
        String phone,

        @NotBlank(message = "OTP code is required")
        @Pattern(regexp = "^\\d{6}$", message = "OTP code must be a 6-digit number")
        String code,

        UserRole role
) {}
