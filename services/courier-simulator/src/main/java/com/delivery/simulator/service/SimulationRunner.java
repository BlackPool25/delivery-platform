package com.delivery.simulator.service;

import com.delivery.simulator.client.OrderServiceClient;
import com.delivery.simulator.client.TrackingServiceClient;
import com.delivery.simulator.client.ValhallaRoutingClient;
import com.delivery.simulator.config.SimulatorProperties;
import com.delivery.simulator.dto.OrderDto;
import com.delivery.simulator.dto.TelemetryPingRequest;
import com.delivery.simulator.model.ActiveDeliveryJob;
import com.delivery.simulator.model.CourierProfile;
import com.delivery.simulator.model.CourierStatus;
import com.delivery.simulator.model.Waypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SimulationRunner {

    private static final Logger log = LoggerFactory.getLogger(SimulationRunner.class);

    private final CourierManager courierManager;
    private final OrderServiceClient orderServiceClient;
    private final TrackingServiceClient trackingServiceClient;
    private final ValhallaRoutingClient routingClient;
    private final SimulatorProperties properties;

    // Map of Order ID -> ActiveDeliveryJob
    private final Map<UUID, ActiveDeliveryJob> activeJobs = new ConcurrentHashMap<>();

    // Default restaurant location if not set in order (Mission Taqueria: 37.7749, -122.4194)
    private static final double DEFAULT_RESTAURANT_LAT = 37.7749;
    private static final double DEFAULT_RESTAURANT_LON = -122.4194;

    public SimulationRunner(CourierManager courierManager,
                            OrderServiceClient orderServiceClient,
                            TrackingServiceClient trackingServiceClient,
                            ValhallaRoutingClient routingClient,
                            SimulatorProperties properties) {
        this.courierManager = courierManager;
        this.orderServiceClient = orderServiceClient;
        this.trackingServiceClient = trackingServiceClient;
        this.routingClient = routingClient;
        this.properties = properties;
    }

    @Scheduled(fixedRateString = "${simulator.tick-rate-ms:3000}")
    public void scheduledTick() {
        if (!properties.isAutoDispatchEnabled()) {
            return;
        }

        // 1. Check for orders awaiting dispatch and auto-claim
        pollAndClaimOffers();

        // 2. Advance active deliveries and stream live GPS telemetry
        advanceActiveDeliveries();
    }

    public synchronized void pollAndClaimOffers() {
        List<OrderDto> pendingOrders = orderServiceClient.getPendingDispatchOrders();
        for (OrderDto order : pendingOrders) {
            if (activeJobs.containsKey(order.id())) {
                continue;
            }
            startOrderDeliverySimulation(order);
        }
    }

    public synchronized boolean startOrderDeliverySimulation(OrderDto order) {
        if (order == null || order.id() == null) {
            return false;
        }

        if (activeJobs.containsKey(order.id())) {
            log.info("Order {} is already being simulated", order.id());
            return true;
        }

        Optional<CourierProfile> courierOpt = courierManager.assignCourierToOrder(order.id());
        if (courierOpt.isEmpty()) {
            log.warn("No courier available to simulate order {}", order.id());
            return false;
        }

        CourierProfile courier = courierOpt.get();

        // Step 1: Claim dispatch lease
        boolean claimed = orderServiceClient.claimDispatch(order.id(), courier.getId());
        if (!claimed) {
            courierManager.releaseCourier(courier.getId());
            return false;
        }

        double restaurantLat = DEFAULT_RESTAURANT_LAT;
        double restaurantLon = DEFAULT_RESTAURANT_LON;

        double customerLat = order.dropoffLatitude() != null ? order.dropoffLatitude() : 37.7650;
        double customerLon = order.dropoffLongitude() != null ? order.dropoffLongitude() : -122.4150;

        // Step 2: Query Valhalla for full route waypoints:
        // Courier position -> Restaurant -> Customer drop-off
        List<Waypoint> leg1 = routingClient.getRouteWaypoints(
                courier.getCurrentLatitude(), courier.getCurrentLongitude(),
                restaurantLat, restaurantLon
        );
        List<Waypoint> leg2 = routingClient.getRouteWaypoints(
                restaurantLat, restaurantLon,
                customerLat, customerLon
        );

        List<Waypoint> fullRoute = new ArrayList<>(leg1);
        fullRoute.addAll(leg2);

        String pin = order.verificationPin() != null ? order.verificationPin() : "123456";

        ActiveDeliveryJob job = new ActiveDeliveryJob(
                order.id(), courier.getId(), order.restaurantId(),
                restaurantLat, restaurantLon, customerLat, customerLon,
                pin, fullRoute
        );

        // Step 3: Transition order to OUT_FOR_DELIVERY
        orderServiceClient.outForDelivery(order.id(), courier.getId());
        courier.setStatus(CourierStatus.OUT_FOR_DELIVERY);

        activeJobs.put(order.id(), job);
        log.info("Started digital twin delivery for order {} with courier {} ({} waypoints)",
                order.id(), courier.getId(), fullRoute.size());
        return true;
    }

    public synchronized void advanceActiveDeliveries() {
        List<UUID> completedOrders = new ArrayList<>();

        for (Map.Entry<UUID, ActiveDeliveryJob> entry : activeJobs.entrySet()) {
            UUID orderId = entry.getKey();
            ActiveDeliveryJob job = entry.getValue();

            Optional<CourierProfile> courierOpt = courierManager.getCourier(job.getCourierId());
            if (courierOpt.isEmpty()) {
                completedOrders.add(orderId);
                continue;
            }

            CourierProfile courier = courierOpt.get();
            Waypoint currentWp = job.getCurrentWaypoint();

            // Advance to next waypoint
            job.advanceWaypoint();
            Waypoint nextWp = job.getCurrentWaypoint();

            double heading = ValhallaRoutingClient.calculateBearingDegrees(
                    currentWp.latitude(), currentWp.longitude(),
                    nextWp.latitude(), nextWp.longitude()
            );

            double speedKmh = properties.getSpeedKmh(); // default ~28 km/h, well below 120 km/h

            // Update courier position
            courier.updateLocation(nextWp.latitude(), nextWp.longitude(), speedKmh, heading);

            // Stream live GPS telemetry to tracking-service
            TelemetryPingRequest ping = new TelemetryPingRequest(
                    nextWp.latitude(),
                    nextWp.longitude(),
                    Instant.now(),
                    speedKmh,
                    heading,
                    15.0, // altitude
                    4.0   // accuracy
            );
            trackingServiceClient.emitTelemetry(courier.getId(), ping);

            // Check if arrived at customer dropoff destination
            if (job.hasReachedEndOfWaypoints()) {
                log.info("Courier {} arrived at destination for order {}. Executing PIN handshake ({})",
                        courier.getId(), orderId, job.getVerificationPin());

                boolean delivered = orderServiceClient.deliverOrder(orderId, job.getVerificationPin());
                if (delivered) {
                    courier.setStatus(CourierStatus.DELIVERED);
                    courierManager.releaseCourier(courier.getId());
                    completedOrders.add(orderId);
                }
            }
        }

        for (UUID orderId : completedOrders) {
            activeJobs.remove(orderId);
        }
    }

    public List<ActiveDeliveryJob> getActiveJobs() {
        return new ArrayList<>(activeJobs.values());
    }

    public Optional<ActiveDeliveryJob> getJobForOrder(UUID orderId) {
        return Optional.ofNullable(activeJobs.get(orderId));
    }
}
