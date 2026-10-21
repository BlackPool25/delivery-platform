package com.delivery.simulator.model;

import java.time.Instant;
import java.util.UUID;

public class CourierProfile {
    private final UUID id;
    private final String name;
    private final String phone;
    private CourierStatus status;
    private double currentLatitude;
    private double currentLongitude;
    private double speedKmh;
    private double headingDegrees;
    private UUID activeOrderId;
    private Instant lastTelemetryAt;

    public CourierProfile(UUID id, String name, String phone, double initialLatitude, double initialLongitude) {
        this.id = id;
        this.name = name;
        this.phone = phone;
        this.currentLatitude = initialLatitude;
        this.currentLongitude = initialLongitude;
        this.status = CourierStatus.ONLINE;
        this.speedKmh = 0.0;
        this.headingDegrees = 0.0;
        this.activeOrderId = null;
        this.lastTelemetryAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPhone() {
        return phone;
    }

    public synchronized CourierStatus getStatus() {
        return status;
    }

    public synchronized void setStatus(CourierStatus status) {
        this.status = status;
    }

    public synchronized double getCurrentLatitude() {
        return currentLatitude;
    }

    public synchronized double getCurrentLongitude() {
        return currentLongitude;
    }

    public synchronized void updateLocation(double lat, double lon, double speedKmh, double headingDegrees) {
        this.currentLatitude = lat;
        this.currentLongitude = lon;
        this.speedKmh = speedKmh;
        this.headingDegrees = headingDegrees;
        this.lastTelemetryAt = Instant.now();
    }

    public synchronized double getSpeedKmh() {
        return speedKmh;
    }

    public synchronized double getHeadingDegrees() {
        return headingDegrees;
    }

    public synchronized UUID getActiveOrderId() {
        return activeOrderId;
    }

    public synchronized void setActiveOrderId(UUID activeOrderId) {
        this.activeOrderId = activeOrderId;
    }

    public synchronized Instant getLastTelemetryAt() {
        return lastTelemetryAt;
    }
}
