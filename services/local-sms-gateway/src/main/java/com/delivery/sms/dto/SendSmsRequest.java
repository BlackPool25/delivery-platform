package com.delivery.sms.dto;

import jakarta.validation.constraints.NotBlank;

public record SendSmsRequest(
        @NotBlank(message = "phone number must not be blank")
        String phone,

        @NotBlank(message = "message must not be blank")
        String message
) {}
