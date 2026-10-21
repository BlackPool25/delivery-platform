package com.delivery.tracking.service;

import com.delivery.tracking.calculator.KinematicCalculator;
import com.delivery.tracking.model.CourierState;
import com.delivery.tracking.model.FraudFlag;
import com.delivery.tracking.model.TelemetryIngestResult;
import com.delivery.tracking.model.TelemetryPing;
import com.delivery.tracking.model.WebSocketMessage;
import com.delivery.tracking.websocket.TrackingWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uber.h3core.H3Core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TrackingServiceImpl implements TrackingService {

    private static final Logger log = LoggerFactory.getLogger(TrackingServiceImpl.class);

    private static final String KEY_PREFIX_LATEST = "tracking:courier:%s:latest";
    private static final String KEY_PREFIX_FRAUD = "tracking:courier:%s:fraud";
    private static final String KEY_PREFIX_BREADCRUMBS = "tracking:courier:%s:breadcrumbs";
    private static final String KEY_FLAGGED_COURIERS = "tracking:flagged_couriers";
    private static final String KEY_GEO_ACTIVE = "geo:couriers:active";

    private final KinematicCalculator kinematicCalculator;
    private final StringRedisTemplate stringRedisTemplate;
    private final TrackingWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;
    private final H3Core h3;

    @Value("${tracking.velocity.max-kmh:120.0}")
    private double maxAllowedVelocityKmh;

    @Value("${tracking.telemetry.ttl-seconds:86400}")
    private long telemetryTtlSeconds;

    @Value("${tracking.telemetry.history-limit:1000}")
    private long historyLimit;

    @Value("${tracking.h3.resolution:8}")
    private int h3Resolution;

    public TrackingServiceImpl(KinematicCalculator kinematicCalculator,
                               StringRedisTemplate stringRedisTemplate,
                               TrackingWebSocketHandler webSocketHandler,
                               ObjectMapper objectMapper) {
        this.kinematicCalculator = kinematicCalculator;
        this.stringRedisTemplate = stringRedisTemplate;
        this.webSocketHandler = webSocketHandler;
        this.objectMapper = objectMapper;
        try {
            this.h3 = H3Core.newInstance();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize Uber H3 Core engine", e);
        }
    }

    @Override
    public TelemetryIngestResult ingestTelemetry(UUID courierId, TelemetryPing ping) {
        if (courierId == null || ping == null) {
            throw new IllegalArgumentException("CourierId and TelemetryPing must not be null");
        }

        String latestKey = String.format(KEY_PREFIX_LATEST, courierId);
        String lastTelemetryJson = stringRedisTemplate.opsForValue().get(latestKey);

        double calculatedVelocityKmh = 0.0;

        if (lastTelemetryJson != null && !lastTelemetryJson.isBlank()) {
            try {
                CourierState lastState = objectMapper.readValue(lastTelemetryJson, CourierState.class);
                calculatedVelocityKmh = kinematicCalculator.calculateVelocityKmh(
                        lastState.latitude(),
                        lastState.longitude(),
                        lastState.timestamp(),
                        ping.latitude(),
                        ping.longitude(),
                        ping.timestamp()
                );

                if (calculatedVelocityKmh > maxAllowedVelocityKmh) {
                    log.warn("Kinematic anti-spoofing alert: courierId={}, calculatedSpeed={} km/h, maxThreshold={} km/h",
                            courierId, calculatedVelocityKmh, maxAllowedVelocityKmh);

                    FraudFlag fraudFlag = new FraudFlag(
                            courierId,
                            calculatedVelocityKmh,
                            maxAllowedVelocityKmh,
                            String.format("Kinematic velocity threshold exceeded: %.2f km/h > %.2f km/h",
                                    calculatedVelocityKmh, maxAllowedVelocityKmh),
                            Instant.now()
                    );

                    String fraudKey = String.format(KEY_PREFIX_FRAUD, courierId);
                    stringRedisTemplate.opsForValue().set(fraudKey, objectMapper.writeValueAsString(fraudFlag), Duration.ofDays(30));
                    stringRedisTemplate.opsForSet().add(KEY_FLAGGED_COURIERS, courierId.toString());

                    // Drop the telemetry ping immediately and return rejection result
                    return TelemetryIngestResult.flaggedSpoofing(
                            calculatedVelocityKmh,
                            maxAllowedVelocityKmh,
                            "Kinematic velocity anomaly detected. Telemetry dropped and courier flagged for review."
                    );
                }
            } catch (Exception e) {
                log.error("Error reading or parsing prior telemetry for courier {}: {}", courierId, e.getMessage());
            }
        }

        // Telemetry is valid and within kinematic physical limits
        String h3Address = Long.toHexString(h3.latLngToCell(ping.latitude(), ping.longitude(), h3Resolution));

        CourierState currentState = new CourierState(
                courierId,
                ping.latitude(),
                ping.longitude(),
                ping.speedKmh() != null ? ping.speedKmh() : calculatedVelocityKmh,
                ping.heading() != null ? ping.heading() : 0.0,
                ping.timestamp(),
                h3Address,
                false,
                null,
                null
        );

        try {
            String jsonState = objectMapper.writeValueAsString(currentState);

            // 1. Update latest state hash / key
            stringRedisTemplate.opsForValue().set(latestKey, jsonState, Duration.ofSeconds(telemetryTtlSeconds));

            // 2. Add to Redis Geo index for regional spatial search
            stringRedisTemplate.opsForGeo().add(
                    KEY_GEO_ACTIVE,
                    new Point(ping.longitude(), ping.latitude()),
                    courierId.toString()
            );

            // 3. Append to breadcrumb trail
            String breadcrumbsKey = String.format(KEY_PREFIX_BREADCRUMBS, courierId);
            stringRedisTemplate.opsForList().rightPush(breadcrumbsKey, jsonState);
            stringRedisTemplate.opsForList().trim(breadcrumbsKey, -historyLimit, -1);

            // 4. Broadcast live update to connected WebSocket clients
            WebSocketMessage wsMessage = new WebSocketMessage(
                    "TELEMETRY_UPDATE",
                    courierId,
                    ping.latitude(),
                    ping.longitude(),
                    currentState.speedKmh(),
                    currentState.heading(),
                    currentState.timestamp(),
                    h3Address
            );
            webSocketHandler.broadcastTelemetry(wsMessage);

        } catch (Exception e) {
            log.error("Failed to persist telemetry to Redis for courier {}: {}", courierId, e.getMessage(), e);
            throw new RuntimeException("Telemetry persistence failure", e);
        }

        return TelemetryIngestResult.accepted(calculatedVelocityKmh, "Telemetry accepted.");
    }

    @Override
    public CourierState getCourierState(UUID courierId) {
        if (courierId == null) {
            return null;
        }
        String latestKey = String.format(KEY_PREFIX_LATEST, courierId);
        String json = stringRedisTemplate.opsForValue().get(latestKey);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, CourierState.class);
        } catch (Exception e) {
            log.error("Failed to parse courier state for {}: {}", courierId, e.getMessage());
            return null;
        }
    }

    @Override
    public FraudFlag getFraudStatus(UUID courierId) {
        if (courierId == null) {
            return null;
        }
        String fraudKey = String.format(KEY_PREFIX_FRAUD, courierId);
        String json = stringRedisTemplate.opsForValue().get(fraudKey);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, FraudFlag.class);
        } catch (Exception e) {
            log.error("Failed to parse fraud flag for {}: {}", courierId, e.getMessage());
            return null;
        }
    }

    @Override
    public List<CourierState> findNearbyCouriers(double latitude, double longitude, double radiusKm) {
        Circle circle = new Circle(new Point(longitude, latitude), new Distance(radiusKm, Metrics.KILOMETERS));
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                stringRedisTemplate.opsForGeo().radius(KEY_GEO_ACTIVE, circle);

        List<CourierState> nearby = new ArrayList<>();
        if (results != null) {
            results.forEach(geoResult -> {
                String courierIdStr = geoResult.getContent().getName();
                try {
                    UUID courierId = UUID.fromString(courierIdStr);
                    CourierState state = getCourierState(courierId);
                    if (state != null) {
                        nearby.add(state);
                    }
                } catch (Exception ignored) {
                }
            });
        }
        return nearby;
    }
}
