package com.delivery.simulator;

import com.delivery.simulator.client.OrderServiceClient;
import com.delivery.simulator.client.TrackingServiceClient;
import com.delivery.simulator.dto.OrderDto;
import com.delivery.simulator.dto.TelemetryPingRequest;
import com.delivery.simulator.model.CourierProfile;
import com.delivery.simulator.model.CourierStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class SimulatorControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderServiceClient orderServiceClient;

    @MockitoBean
    private TrackingServiceClient trackingServiceClient;

    @Test
    @DisplayName("GET /api/v1/simulator/status returns online state with 20 couriers")
    void testGetSimulatorStatus() throws Exception {
        mockMvc.perform(get("/api/v1/simulator/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ONLINE")))
                .andExpect(jsonPath("$.totalCouriers", is(20)))
                .andExpect(jsonPath("$.activeDeliveries", greaterThanOrEqualTo(0)));
    }

    @Test
    @DisplayName("GET /api/v1/simulator/couriers returns list of 20 online couriers")
    void testGetAllCouriers() throws Exception {
        mockMvc.perform(get("/api/v1/simulator/couriers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(20)))
                .andExpect(jsonPath("$[0].status", is("ONLINE")))
                .andExpect(jsonPath("$[0].name").exists());
    }

    @Test
    @DisplayName("POST /api/v1/simulator/dispatch/{orderId} triggers courier dispatch")
    void testTriggerDispatchEndpoint() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderDto mockOrder = new OrderDto(
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "READY_FOR_PICKUP",
                37.7650,
                -122.4150,
                "482915",
                Instant.now().plusSeconds(30)
        );

        when(orderServiceClient.getOrder(orderId)).thenReturn(mockOrder);
        when(orderServiceClient.claimDispatch(eq(orderId), any(UUID.class))).thenReturn(true);
        when(orderServiceClient.outForDelivery(eq(orderId), any(UUID.class))).thenReturn(true);
        when(trackingServiceClient.emitTelemetry(any(UUID.class), any(TelemetryPingRequest.class))).thenReturn(true);

        mockMvc.perform(post("/api/v1/simulator/dispatch/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.orderId", is(orderId.toString())));
    }

    @Test
    @DisplayName("POST /api/v1/simulator/deliver/{orderId} verifies doorstep PIN handshake")
    void testTriggerDeliverEndpoint() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderDto mockOrder = new OrderDto(
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "OUT_FOR_DELIVERY",
                37.7650,
                -122.4150,
                "482915",
                Instant.now().plusSeconds(30)
        );

        when(orderServiceClient.getOrder(orderId)).thenReturn(mockOrder);
        when(orderServiceClient.deliverOrder(orderId, "482915")).thenReturn(true);

        mockMvc.perform(post("/api/v1/simulator/deliver/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.orderId", is(orderId.toString())))
                .andExpect(jsonPath("$.verificationPin", is("482915")));
    }
}
