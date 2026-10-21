package com.delivery.sms.controller;

import com.delivery.sms.dto.BufferStatsResponse;
import com.delivery.sms.dto.SendSmsRequest;
import com.delivery.sms.dto.SmsResponse;
import com.delivery.sms.service.SmsService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/v1/sms", "/api/v1/sms"})
public class SmsApiController {

    private final SmsService smsService;

    public SmsApiController(SmsService smsService) {
        this.smsService = smsService;
    }

    @PostMapping("/send")
    public ResponseEntity<SmsResponse> sendSms(@Valid @RequestBody SendSmsRequest request) {
        SmsResponse response = smsService.sendSms(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/latest")
    public ResponseEntity<?> getLatestSms(@RequestParam(name = "phone") String phone) {
        return smsService.getLatestByPhone(phone)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of(
                                "error", "NOT_FOUND",
                                "message", "No SMS found for phone: " + phone,
                                "phone", phone
                        )));
    }

    @GetMapping("/all")
    public ResponseEntity<List<SmsResponse>> getAllMessages() {
        return ResponseEntity.ok(smsService.getAllMessages());
    }

    @GetMapping("/stats")
    public ResponseEntity<BufferStatsResponse> getStats() {
        return ResponseEntity.ok(smsService.getStats());
    }

    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, String>> clearMessages() {
        smsService.clear();
        return ResponseEntity.ok(Map.of("status", "CLEARED", "message", "Circular SMS buffer cleared successfully"));
    }
}
