package com.delivery.sms.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SmsMessage {

    private static final Pattern OTP_PATTERN = Pattern.compile("\\b\\d{4,8}\\b");

    private final UUID id;
    private final String phone;
    private final String message;
    private final String otp;
    private final String status;
    private final Instant sentAt;

    public SmsMessage(String phone, String message) {
        this.id = UUID.randomUUID();
        this.phone = normalizePhone(phone);
        this.message = Objects.requireNonNull(message, "message must not be null").trim();
        this.otp = extractOtp(this.message);
        this.status = "DELIVERED";
        this.sentAt = Instant.now();
    }

    public static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String trimmed = raw.trim();
        if (trimmed.startsWith("+")) {
            return "+" + trimmed.substring(1).replaceAll("[^0-9]", "");
        }
        String digits = trimmed.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? "" : "+" + digits;
    }

    public SmsMessage(UUID id, String phone, String message, String otp, String status, Instant sentAt) {
        this.id = id;
        this.phone = phone;
        this.message = message;
        this.otp = otp;
        this.status = status;
        this.sentAt = sentAt;
    }

    private static String extractOtp(String message) {
        if (message == null) return null;
        Matcher matcher = OTP_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    public UUID getId() {
        return id;
    }

    public String getPhone() {
        return phone;
    }

    public String getMessage() {
        return message;
    }

    public String getOtp() {
        return otp;
    }

    public String getStatus() {
        return status;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SmsMessage that = (SmsMessage) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
