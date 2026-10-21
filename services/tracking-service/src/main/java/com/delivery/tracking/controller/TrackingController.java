package com.delivery.tracking.controller;

import com.delivery.tracking.model.CourierState;
import com.delivery.tracking.model.FraudFlag;
import com.delivery.tracking.model.TelemetryIngestResult;
import com.delivery.tracking.model.TelemetryPing;
import com.delivery.tracking.service.TrackingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class TrackingController {

    private final TrackingService trackingService;

    public TrackingController(TrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @PostMapping({
            "/api/v1/tracking/couriers/{courierId}/telemetry",
            "/v1/couriers/{courierId}/telemetry"
    })
    public ResponseEntity<TelemetryIngestResult> ingestTelemetry(
            @PathVariable UUID courierId,
            @Valid @RequestBody TelemetryPing ping) {

        TelemetryIngestResult result = trackingService.ingestTelemetry(courierId, ping);

        if (!result.accepted()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping({
            "/api/v1/tracking/couriers/{courierId}/telemetry",
            "/v1/couriers/{courierId}/telemetry"
    })
    public ResponseEntity<CourierState> getCourierTelemetry(@PathVariable UUID courierId) {
        CourierState state = trackingService.getCourierState(courierId);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(state);
    }

    @GetMapping({
            "/api/v1/tracking/couriers/{courierId}/fraud-status",
            "/v1/couriers/{courierId}/fraud-status"
    })
    public ResponseEntity<?> getFraudStatus(@PathVariable UUID courierId) {
        FraudFlag fraudFlag = trackingService.getFraudStatus(courierId);
        if (fraudFlag == null) {
            return ResponseEntity.ok(Map.of(
                    "courierId", courierId,
                    "isFlagged", false,
                    "message", "Courier is in good standing with zero velocity fraud flags."
            ));
        }
        return ResponseEntity.ok(Map.of(
                "courierId", courierId,
                "isFlagged", true,
                "fraudFlag", fraudFlag
        ));
    }

    @GetMapping({
            "/api/v1/tracking/couriers/nearby",
            "/v1/couriers/nearby"
    })
    public ResponseEntity<List<CourierState>> getNearbyCouriers(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(defaultValue = "5.0") double radiusKm) {

        List<CourierState> couriers = trackingService.findNearbyCouriers(latitude, longitude, radiusKm);
        return ResponseEntity.ok(couriers);
    }
}
