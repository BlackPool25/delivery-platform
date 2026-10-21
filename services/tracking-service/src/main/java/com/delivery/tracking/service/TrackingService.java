package com.delivery.tracking.service;

import com.delivery.tracking.model.CourierState;
import com.delivery.tracking.model.FraudFlag;
import com.delivery.tracking.model.TelemetryIngestResult;
import com.delivery.tracking.model.TelemetryPing;

import java.util.List;
import java.util.UUID;

public interface TrackingService {

    TelemetryIngestResult ingestTelemetry(UUID courierId, TelemetryPing ping);

    CourierState getCourierState(UUID courierId);

    FraudFlag getFraudStatus(UUID courierId);

    List<CourierState> findNearbyCouriers(double latitude, double longitude, double radiusKm);
}
