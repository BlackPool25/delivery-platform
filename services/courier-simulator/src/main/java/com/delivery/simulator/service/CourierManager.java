package com.delivery.simulator.service;

import com.delivery.simulator.model.CourierProfile;
import com.delivery.simulator.model.CourierStatus;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CourierManager {

    private static final Logger log = LoggerFactory.getLogger(CourierManager.class);

    // Map of Courier ID -> CourierProfile
    private final Map<UUID, CourierProfile> couriers = new ConcurrentHashMap<>();

    // 4 Pareto Commercial Dining Hubs in San Francisco (Section 13.2)
    private static final double[][] SF_COMMERCIAL_HUBS = {
            {37.7650, -122.4194}, // Hub 1: Mission Corridor (16th to 20th St)
            {37.7610, -122.4215}, // Hub 2: Valencia Dining District
            {37.7614, -122.4241}, // Hub 3: Dolores / Tartine Corridor
            {37.7850, -122.4060}  // Hub 4: SOMA / Downtown Hub
    };

    @PostConstruct
    public void initCouriers() {
        couriers.clear();
        for (int i = 1; i <= 20; i++) {
            // Deterministic UUID for virtual twin couriers: 00000000-0000-0000-0000-000000000001 to 0020
            String hexId = String.format("c0000000-0000-0000-0000-%012d", i);
            UUID courierId = UUID.fromString(hexId);

            int hubIdx = (i - 1) % SF_COMMERCIAL_HUBS.length;
            double baseLat = SF_COMMERCIAL_HUBS[hubIdx][0];
            double baseLon = SF_COMMERCIAL_HUBS[hubIdx][1];

            // Deterministic slight spatial spread (radius < 300m)
            double latOffset = ((i % 5) - 2) * 0.0015;
            double lonOffset = (((i / 5) % 5) - 2) * 0.0015;

            double initialLat = baseLat + latOffset;
            double initialLon = baseLon + lonOffset;

            String name = String.format("Virtual Courier #%02d (Hub %d)", i, hubIdx + 1);
            String phone = String.format("+1415555%04d", 1000 + i);

            CourierProfile profile = new CourierProfile(courierId, name, phone, initialLat, initialLon);
            couriers.put(courierId, profile);
        }
        log.info("Provisioned {} virtual couriers across 4 San Francisco dining hubs with status ONLINE", couriers.size());
    }

    public List<CourierProfile> getAllCouriers() {
        return new ArrayList<>(couriers.values());
    }

    public Optional<CourierProfile> getCourier(UUID id) {
        return Optional.ofNullable(couriers.get(id));
    }

    public synchronized Optional<CourierProfile> findAvailableCourier() {
        return couriers.values().stream()
                .filter(c -> c.getStatus() == CourierStatus.ONLINE)
                .findFirst();
    }

    public synchronized Optional<CourierProfile> assignCourierToOrder(UUID orderId) {
        Optional<CourierProfile> available = findAvailableCourier();
        if (available.isPresent()) {
            CourierProfile courier = available.get();
            courier.setStatus(CourierStatus.ASSIGNED);
            courier.setActiveOrderId(orderId);
            log.info("Assigned courier {} ({}) to order {}", courier.getId(), courier.getName(), orderId);
            return Optional.of(courier);
        }
        log.warn("No available ONLINE courier found for order {}", orderId);
        return Optional.empty();
    }

    public synchronized void releaseCourier(UUID courierId) {
        CourierProfile courier = couriers.get(courierId);
        if (courier != null) {
            courier.setStatus(CourierStatus.ONLINE);
            courier.setActiveOrderId(null);
            log.info("Released courier {} back to status ONLINE", courierId);
        }
    }
}
