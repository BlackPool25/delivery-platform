package com.delivery.order.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class PaymentServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceClient.class);

    private final RestClient restClient;
    private final String paymentServiceUrl;

    public PaymentServiceClient(@Value("${payment.service.url:http://localhost:8082}") String paymentServiceUrl) {
        this.paymentServiceUrl = paymentServiceUrl;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));

        this.restClient = RestClient.builder()
                .baseUrl(paymentServiceUrl)
                .requestFactory(factory)
                .build();
    }

    public PaymentResult holdPayment(UUID orderId, UUID userId, BigDecimal amount, UUID idempotencyKey) {
        log.info("Requesting payment HOLD for order {} (user {}, amount {}) via {}",
                orderId, userId, amount, paymentServiceUrl);
        Map<String, Object> body = new HashMap<>();
        body.put("orderId", orderId);
        body.put("userId", userId);
        body.put("amount", amount);
        body.put("currency", "USD");
        if (idempotencyKey != null) {
            body.put("idempotencyKey", idempotencyKey);
        }

        try {
            var response = restClient.post()
                    .uri("/v1/payments/hold")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            log.info("Payment HOLD successful for order {}: response {}", orderId, response);
            return new PaymentResult(true, "HOLD_SUCCESSFUL", null);
        } catch (Exception e) {
            log.warn("Payment HOLD failed for order {}: {}", orderId, e.getMessage());
            return new PaymentResult(false, null, e.getMessage());
        }
    }

    public PaymentResult capturePayment(UUID orderId, UUID userId, BigDecimal amount, UUID destinationAccountId, UUID idempotencyKey) {
        log.info("Requesting payment CAPTURE for order {} (amount {})", orderId, amount);
        Map<String, Object> body = new HashMap<>();
        body.put("orderId", orderId);
        body.put("userId", userId);
        body.put("amount", amount);
        if (destinationAccountId != null) {
            body.put("destinationAccountId", destinationAccountId);
        }
        if (idempotencyKey != null) {
            body.put("idempotencyKey", idempotencyKey);
        }

        try {
            var response = restClient.post()
                    .uri("/v1/payments/capture")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            log.info("Payment CAPTURE successful for order {}: response {}", orderId, response);
            return new PaymentResult(true, "CAPTURE_SUCCESSFUL", null);
        } catch (Exception e) {
            log.error("Payment CAPTURE failed for order {}: {}", orderId, e.getMessage());
            return new PaymentResult(false, null, e.getMessage());
        }
    }

    public PaymentResult refundPayment(UUID orderId, UUID userId, BigDecimal amount, String reason, UUID idempotencyKey) {
        log.info("Requesting payment REFUND for order {} (amount {}, reason {})", orderId, amount, reason);
        Map<String, Object> body = new HashMap<>();
        body.put("orderId", orderId);
        body.put("userId", userId);
        body.put("amount", amount);
        body.put("reason", reason);
        if (idempotencyKey != null) {
            body.put("idempotencyKey", idempotencyKey);
        }

        try {
            var response = restClient.post()
                    .uri("/v1/payments/refund")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            log.info("Payment REFUND successful for order {}: response {}", orderId, response);
            return new PaymentResult(true, "REFUND_SUCCESSFUL", null);
        } catch (Exception e) {
            log.error("Payment REFUND failed for order {}: {}", orderId, e.getMessage());
            return new PaymentResult(false, null, e.getMessage());
        }
    }

    public record PaymentResult(boolean success, String status, String error) {}
}
