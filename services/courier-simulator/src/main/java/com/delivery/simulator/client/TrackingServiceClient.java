package com.delivery.simulator.client;

import com.delivery.simulator.config.SimulatorProperties;
import com.delivery.simulator.dto.TelemetryPingRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;

@Component
public class TrackingServiceClient {

    private static final Logger log = LoggerFactory.getLogger(TrackingServiceClient.class);

    private final RestClient restClient;

    public TrackingServiceClient(SimulatorProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        this.restClient = RestClient.builder()
                .baseUrl(properties.getTrackingServiceUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public boolean emitTelemetry(UUID courierId, TelemetryPingRequest ping) {
        try {
            restClient.post()
                    .uri("/api/v1/tracking/couriers/{id}/telemetry", courierId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ping)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Emitted telemetry for courier {}: lat={}, lon={}, speed={} km/h, heading={}",
                    courierId, ping.latitude(), ping.longitude(), ping.speedKmh(), ping.heading());
            return true;
        } catch (Exception e) {
            log.warn("Failed to emit telemetry for courier {}: {}", courierId, e.getMessage());
            return false;
        }
    }
}
