package com.delivery.simulator.client;

import com.delivery.simulator.config.SimulatorProperties;
import com.delivery.simulator.dto.DeliverOrderRequest;
import com.delivery.simulator.dto.OrderDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Component
public class OrderServiceClient {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceClient.class);

    private final RestClient restClient;

    public OrderServiceClient(SimulatorProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        this.restClient = RestClient.builder()
                .baseUrl(properties.getOrderServiceUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public List<OrderDto> getPendingDispatchOrders() {
        try {
            return restClient.get()
                    .uri("/api/v1/orders/pending-dispatch")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<OrderDto>>() {});
        } catch (Exception e) {
            log.debug("Unable to poll pending dispatch orders from order-service: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public OrderDto getOrder(UUID orderId) {
        try {
            return restClient.get()
                    .uri("/api/v1/orders/{id}", orderId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(OrderDto.class);
        } catch (Exception e) {
            log.warn("Failed to fetch order {}: {}", orderId, e.getMessage());
            return null;
        }
    }

    public boolean claimDispatch(UUID orderId, UUID courierId) {
        try {
            restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/orders/{id}/courier-accept")
                            .queryParam("courierId", courierId)
                            .build(orderId))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Successfully claimed dispatch lease for order {} with courier {}", orderId, courierId);
            return true;
        } catch (Exception e) {
            log.warn("Courier {} failed to claim dispatch lease for order {}: {}", courierId, orderId, e.getMessage());
            return false;
        }
    }

    public boolean outForDelivery(UUID orderId, UUID courierId) {
        try {
            restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/orders/{id}/out-for-delivery")
                            .queryParam("courierId", courierId)
                            .build(orderId))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Order {} transitioned to OUT_FOR_DELIVERY by courier {}", orderId, courierId);
            return true;
        } catch (Exception e) {
            log.warn("Failed to set order {} to OUT_FOR_DELIVERY: {}", orderId, e.getMessage());
            return false;
        }
    }

    public boolean deliverOrder(UUID orderId, String pin) {
        try {
            restClient.post()
                    .uri("/api/v1/orders/{id}/deliver", orderId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new DeliverOrderRequest(pin))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Order {} successfully DELIVERED and SETTLED via PIN handshake ({})", orderId, pin);
            return true;
        } catch (Exception e) {
            log.error("Failed to complete PIN handshake delivery for order {}: {}", orderId, e.getMessage());
            return false;
        }
    }
}
