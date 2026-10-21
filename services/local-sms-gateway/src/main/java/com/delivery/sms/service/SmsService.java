package com.delivery.sms.service;

import com.delivery.sms.buffer.CircularSmsBuffer;
import com.delivery.sms.dto.BufferStatsResponse;
import com.delivery.sms.dto.SendSmsRequest;
import com.delivery.sms.dto.SmsResponse;
import com.delivery.sms.model.SmsMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    private final CircularSmsBuffer buffer;

    public SmsService(CircularSmsBuffer buffer) {
        this.buffer = buffer;
    }

    public SmsResponse sendSms(SendSmsRequest request) {
        SmsMessage message = new SmsMessage(request.phone(), request.message());
        buffer.add(message);
        log.info("SMS delivered to {} (OTP={}): '{}'", message.getPhone(), message.getOtp(), message.getMessage());
        return SmsResponse.from(message);
    }

    public Optional<SmsResponse> getLatestByPhone(String phone) {
        return buffer.getLatest(phone).map(SmsResponse::from);
    }

    public List<SmsResponse> getAllMessages() {
        return buffer.getAll().stream().map(SmsResponse::from).toList();
    }

    public BufferStatsResponse getStats() {
        return new BufferStatsResponse(
                buffer.getTotalReceived(),
                buffer.size(),
                buffer.getCapacity(),
                buffer.getDistinctPhonesCount()
        );
    }

    public void clear() {
        buffer.clear();
        log.info("Cleared all messages in SMS circular buffer");
    }
}
