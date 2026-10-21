package com.delivery.sms.dto;

import com.delivery.sms.model.SmsMessage;
import java.time.Instant;
import java.util.UUID;

public record SmsResponse(
        UUID messageId,
        String phone,
        String message,
        String otp,
        String status,
        Instant sentAt
) {
    public static SmsResponse from(SmsMessage msg) {
        return new SmsResponse(
                msg.getId(),
                msg.getPhone(),
                msg.getMessage(),
                msg.getOtp(),
                msg.getStatus(),
                msg.getSentAt()
        );
    }
}
