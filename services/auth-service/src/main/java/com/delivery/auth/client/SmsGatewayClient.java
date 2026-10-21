package com.delivery.auth.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Component
public class SmsGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(SmsGatewayClient.class);

    private final RestClient restClient;
    private final String smsGatewayUrl;

    public SmsGatewayClient(@Value("${sms-gateway.url:http://localhost:8083}") String smsGatewayUrl) {
        this.smsGatewayUrl = smsGatewayUrl;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        this.restClient = RestClient.builder()
                .baseUrl(smsGatewayUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public boolean sendOtpSms(String phone, String code) {
        String message = "Your verification code is: " + code;
        try {
            log.info("Sending OTP SMS to {} via local-sms-gateway at {}", phone, smsGatewayUrl);
            restClient.post()
                    .uri("/v1/sms/send")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "phone", phone,
                            "message", message
                    ))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Successfully dispatched OTP SMS to {}", phone);
            return true;
        } catch (Exception e) {
            log.error("Failed to dispatch OTP SMS to {} via gateway {}: {}", phone, smsGatewayUrl, e.getMessage());
            // In sovereign offline development, allow system to continue even if mock gateway is temporarily unavailable
            return false;
        }
    }
}
