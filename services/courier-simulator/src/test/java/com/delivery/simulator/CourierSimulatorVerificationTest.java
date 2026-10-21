package com.delivery.simulator;

import com.delivery.simulator.client.OrderServiceClient;
import com.delivery.simulator.client.TrackingServiceClient;
import com.delivery.simulator.client.ValhallaRoutingClient;
import com.delivery.simulator.dto.OrderDto;
import com.delivery.simulator.dto.TelemetryPingRequest;
import com.delivery.simulator.model.CourierProfile;
import com.delivery.simulator.model.CourierStatus;
import com.delivery.simulator.model.Waypoint;
import com.delivery.simulator.service.CourierManager;
import com.delivery.simulator.service.SimulationRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CourierSimulatorVerificationTest {

    @Autowired
    private CourierManager courierManager;

    @Autowired
    private SimulationRunner simulationRunner;

    @MockitoBean
    private OrderServiceClient orderServiceClient;

    @MockitoBean
    private TrackingServiceClient trackingServiceClient;

    @Test
    @Order(1)
    @DisplayName("Verification Gate: Provisioning of 20 virtual couriers in San Francisco dining hubs")
    void testCourierProvisioning() {
        List<CourierProfile> couriers = courierManager.getAllCouriers();
        assertThat(couriers).hasSize(20);

        for (CourierProfile courier : couriers) {
            assertThat(courier.getId()).isNotNull();
            assertThat(courier.getName()).startsWith("Virtual Courier #");
            assertThat(courier.getPhone()).startsWith("+1415555");
            assertThat(courier.getStatus()).isEqualTo(CourierStatus.ONLINE);
            assertThat(courier.getCurrentLatitude()).isBetween(37.75, 37.80);
            assertThat(courier.getCurrentLongitude()).isBetween(-122.45, -122.39);
        }
    }

    @Test
    @Order(2)
    @DisplayName("Verification Gate: Valhalla geodesic bearing and street interpolation")
    void testValhallaBearingAndInterpolation() {
        // True North: Lat increases, Lon constant -> bearing ~0°
        double bearingNorth = ValhallaRoutingClient.calculateBearingDegrees(37.7700, -122.4200, 37.7800, -122.4200);
        assertThat(bearingNorth).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.1));

        // True East: Lat constant, Lon increases (closer to 0) -> bearing ~90°
        double bearingEast = ValhallaRoutingClient.calculateBearingDegrees(37.7700, -122.4200, 37.7700, -122.4100);
        assertThat(bearingEast).isCloseTo(90.0, org.assertj.core.data.Offset.offset(0.1));

        // Street waypoints interpolation
        List<Waypoint> waypoints = ValhallaRoutingClient.generateFallbackStreetWaypoints(
                37.7749, -122.4194, 37.7650, -122.4150
        );
        assertThat(waypoints).isNotEmpty();
        assertThat(waypoints.getFirst().latitude()).isEqualTo(37.7749);
        assertThat(waypoints.getFirst().longitude()).isEqualTo(-122.4194);
        assertThat(waypoints.getLast().latitude()).isEqualTo(37.7650);
        assertThat(waypoints.getLast().longitude()).isEqualTo(-122.4150);
    }

    @Test
    @Order(3)
    @DisplayName("Verification Gate: Simulated Order Delivery with Real-Time Telemetry and PIN Handshake")
    void testSimulatedDeliveryLifecycleAndPinHandshake() {
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID restaurantId = UUID.randomUUID();
        String verificationPin = "749201";

        OrderDto mockOrder = new OrderDto(
                orderId,
                userId,
                restaurantId,
                null,
                "READY_FOR_PICKUP",
                37.7650, // customer dropoff lat
                -122.4150, // customer dropoff lon
                verificationPin,
                Instant.now().plusSeconds(30)
        );

        when(orderServiceClient.claimDispatch(eq(orderId), any(UUID.class))).thenReturn(true);
        when(orderServiceClient.outForDelivery(eq(orderId), any(UUID.class))).thenReturn(true);
        when(orderServiceClient.deliverOrder(eq(orderId), eq(verificationPin))).thenReturn(true);
        when(trackingServiceClient.emitTelemetry(any(UUID.class), any(TelemetryPingRequest.class))).thenReturn(true);

        // 1. Start order simulation
        boolean started = simulationRunner.startOrderDeliverySimulation(mockOrder);
        assertThat(started).isTrue();
        assertThat(simulationRunner.getActiveJobs()).hasSize(1);

        // Verify courier claimed
        ArgumentCaptor<UUID> courierCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(orderServiceClient).claimDispatch(eq(orderId), courierCaptor.capture());
        UUID assignedCourierId = courierCaptor.getValue();
        assertThat(assignedCourierId).isNotNull();

        Optional<CourierProfile> courierOpt = courierManager.getCourier(assignedCourierId);
        assertThat(courierOpt).isPresent();
        assertThat(courierOpt.get().getStatus()).isEqualTo(CourierStatus.OUT_FOR_DELIVERY);

        // 2. Advance ticks until delivery completes
        int maxTicks = 100;
        int tickCount = 0;
        while (!simulationRunner.getActiveJobs().isEmpty() && tickCount < maxTicks) {
            simulationRunner.advanceActiveDeliveries();
            tickCount++;
        }

        // Verify completed within reasonable ticks
        assertThat(simulationRunner.getActiveJobs()).isEmpty();
        assertThat(tickCount).isLessThan(maxTicks);

        // Verify telemetry was emitted at least once
        ArgumentCaptor<TelemetryPingRequest> pingCaptor = ArgumentCaptor.forClass(TelemetryPingRequest.class);
        verify(trackingServiceClient, atLeastOnce()).emitTelemetry(eq(assignedCourierId), pingCaptor.capture());

        // Verify kinematic constraints on all emitted pings: speed strictly < 120 km/h
        for (TelemetryPingRequest ping : pingCaptor.getAllValues()) {
            assertThat(ping.speedKmh()).isLessThan(120.0);
            assertThat(ping.latitude()).isBetween(37.75, 37.80);
            assertThat(ping.longitude()).isBetween(-122.45, -122.39);
        }

        // 3. Verify final PIN handshake completed
        verify(orderServiceClient).deliverOrder(orderId, verificationPin);

        // 4. Verify courier was released back to ONLINE status
        assertThat(courierOpt.get().getStatus()).isEqualTo(CourierStatus.ONLINE);
    }
}
