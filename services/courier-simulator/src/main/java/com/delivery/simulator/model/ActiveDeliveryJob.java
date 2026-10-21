package com.delivery.simulator.model;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class ActiveDeliveryJob {
    public enum DeliveryPhase {
        TO_RESTAURANT,
        AT_RESTAURANT,
        TO_CUSTOMER,
        COMPLETED
    }

    private final UUID orderId;
    private final UUID courierId;
    private final UUID restaurantId;
    private final double restaurantLat;
    private final double restaurantLon;
    private final double customerLat;
    private final double customerLon;
    private final String verificationPin;
    private List<Waypoint> waypoints;
    private int currentWaypointIndex;
    private DeliveryPhase phase;

    public ActiveDeliveryJob(UUID orderId, UUID courierId, UUID restaurantId,
                             double restaurantLat, double restaurantLon,
                             double customerLat, double customerLon,
                             String verificationPin, List<Waypoint> waypoints) {
        this.orderId = orderId;
        this.courierId = courierId;
        this.restaurantId = restaurantId;
        this.restaurantLat = restaurantLat;
        this.restaurantLon = restaurantLon;
        this.customerLat = customerLat;
        this.customerLon = customerLon;
        this.verificationPin = verificationPin;
        this.waypoints = waypoints != null ? waypoints : Collections.emptyList();
        this.currentWaypointIndex = 0;
        this.phase = DeliveryPhase.TO_RESTAURANT;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getCourierId() {
        return courierId;
    }

    public UUID getRestaurantId() {
        return restaurantId;
    }

    public double getRestaurantLat() {
        return restaurantLat;
    }

    public double getRestaurantLon() {
        return restaurantLon;
    }

    public double getCustomerLat() {
        return customerLat;
    }

    public double getCustomerLon() {
        return customerLon;
    }

    public String getVerificationPin() {
        return verificationPin;
    }

    public synchronized List<Waypoint> getWaypoints() {
        return waypoints;
    }

    public synchronized void setWaypoints(List<Waypoint> waypoints) {
        this.waypoints = waypoints != null ? waypoints : Collections.emptyList();
        this.currentWaypointIndex = 0;
    }

    public synchronized int getCurrentWaypointIndex() {
        return currentWaypointIndex;
    }

    public synchronized void advanceWaypoint() {
        if (currentWaypointIndex < waypoints.size() - 1) {
            currentWaypointIndex++;
        }
    }

    public synchronized Waypoint getCurrentWaypoint() {
        if (waypoints.isEmpty()) {
            return new Waypoint(restaurantLat, restaurantLon);
        }
        int idx = Math.min(currentWaypointIndex, waypoints.size() - 1);
        return waypoints.get(idx);
    }

    public synchronized boolean hasReachedEndOfWaypoints() {
        return waypoints.isEmpty() || currentWaypointIndex >= waypoints.size() - 1;
    }

    public synchronized DeliveryPhase getPhase() {
        return phase;
    }

    public synchronized void setPhase(DeliveryPhase phase) {
        this.phase = phase;
    }
}
