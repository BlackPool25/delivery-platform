package com.delivery.simulator.client;

import com.delivery.simulator.config.SimulatorProperties;
import com.delivery.simulator.model.Waypoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class ValhallaRoutingClient {

    private static final Logger log = LoggerFactory.getLogger(ValhallaRoutingClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public ValhallaRoutingClient(SimulatorProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        this.restClient = RestClient.builder()
                .baseUrl(properties.getValhallaServiceUrl())
                .requestFactory(requestFactory)
                .build();
    }

    public List<Waypoint> getRouteWaypoints(double startLat, double startLon, double endLat, double endLon) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "locations", List.of(
                            Map.of("lat", startLat, "lon", startLon),
                            Map.of("lat", endLat, "lon", endLon)
                    ),
                    "costing", "auto"
            );

            String responseBody = restClient.post()
                    .uri("/route")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            if (responseBody != null) {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode legs = root.path("trip").path("legs");
                if (legs.isArray() && !legs.isEmpty()) {
                    List<Waypoint> waypoints = new ArrayList<>();
                    for (JsonNode leg : legs) {
                        JsonNode coords = leg.path("coordinates");
                        if (coords.isArray() && !coords.isEmpty()) {
                            for (JsonNode coord : coords) {
                                double lon = coord.get(0).asDouble();
                                double lat = coord.get(1).asDouble();
                                waypoints.add(new Waypoint(lat, lon));
                            }
                        }
                    }
                    if (!waypoints.isEmpty()) {
                        log.info("Valhalla returned {} waypoints for route ({}, {}) -> ({}, {})",
                                waypoints.size(), startLat, startLon, endLat, endLon);
                        return waypoints;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Valhalla routing service unreachable or returned error: {}. Using deterministic geodesic interpolation.", e.getMessage());
        }

        // Fallback: Deterministic urban street grid interpolation
        return generateFallbackStreetWaypoints(startLat, startLon, endLat, endLon);
    }

    public static List<Waypoint> generateFallbackStreetWaypoints(double lat1, double lon1, double lat2, double lon2) {
        List<Waypoint> waypoints = new ArrayList<>();
        waypoints.add(new Waypoint(lat1, lon1));

        double midLat = lat1 + (lat2 - lat1) * 0.5;
        double midLon = lon1;

        int steps = 10;
        for (int i = 1; i <= steps; i++) {
            double frac = (double) i / steps;
            waypoints.add(new Waypoint(lat1 + (midLat - lat1) * frac, lon1));
        }

        for (int i = 1; i <= steps; i++) {
            double frac = (double) i / steps;
            waypoints.add(new Waypoint(midLat, lon1 + (lon2 - lon1) * frac));
        }

        for (int i = 1; i <= steps; i++) {
            double frac = (double) i / steps;
            waypoints.add(new Waypoint(midLat + (lat2 - midLat) * frac, lon2));
        }

        return waypoints;
    }

    public static double calculateBearingDegrees(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double deltaLambda = Math.toRadians(lon2 - lon1);

        double y = Math.sin(deltaLambda) * Math.cos(phi2);
        double x = Math.cos(phi1) * Math.sin(phi2) -
                Math.sin(phi1) * Math.cos(phi2) * Math.cos(deltaLambda);

        double theta = Math.atan2(y, x);
        return (Math.toDegrees(theta) + 360.0) % 360.0;
    }
}
