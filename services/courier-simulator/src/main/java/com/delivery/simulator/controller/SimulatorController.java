package com.delivery.simulator.controller;

import com.delivery.simulator.client.OrderServiceClient;
import com.delivery.simulator.dto.OrderDto;
import com.delivery.simulator.model.ActiveDeliveryJob;
import com.delivery.simulator.model.CourierProfile;
import com.delivery.simulator.service.CourierManager;
import com.delivery.simulator.service.SimulationRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/simulator")
public class SimulatorController {

    private final CourierManager courierManager;
    private final SimulationRunner simulationRunner;
    private final OrderServiceClient orderServiceClient;

    public SimulatorController(CourierManager courierManager,
                               SimulationRunner simulationRunner,
                               OrderServiceClient orderServiceClient) {
        this.courierManager = courierManager;
        this.simulationRunner = simulationRunner;
        this.orderServiceClient = orderServiceClient;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSimulatorStatus() {
        return ResponseEntity.ok(Map.of(
                "status", "ONLINE",
                "totalCouriers", courierManager.getAllCouriers().size(),
                "activeDeliveries", simulationRunner.getActiveJobs().size()
        ));
    }

    @GetMapping("/couriers")
    public ResponseEntity<List<CourierProfile>> getAllCouriers() {
        return ResponseEntity.ok(courierManager.getAllCouriers());
    }

    @GetMapping("/couriers/{id}")
    public ResponseEntity<CourierProfile> getCourier(@PathVariable UUID id) {
        return courierManager.getCourier(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks")
    public ResponseEntity<List<ActiveDeliveryJob>> getActiveTasks() {
        return ResponseEntity.ok(simulationRunner.getActiveJobs());
    }

    @PostMapping("/dispatch/{orderId}")
    public ResponseEntity<Map<String, Object>> triggerDispatch(@PathVariable UUID orderId) {
        OrderDto order = orderServiceClient.getOrder(orderId);
        if (order == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Order not found in order-service: " + orderId
            ));
        }

        boolean started = simulationRunner.startOrderDeliverySimulation(order);
        return ResponseEntity.ok(Map.of(
                "success", started,
                "orderId", orderId,
                "message", started ? "Dispatched courier simulation successfully" : "Failed to dispatch courier"
        ));
    }

    @PostMapping("/tick")
    public ResponseEntity<Map<String, Object>> triggerTick() {
        simulationRunner.advanceActiveDeliveries();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "activeJobs", simulationRunner.getActiveJobs().size()
        ));
    }

    @PostMapping("/deliver/{orderId}")
    public ResponseEntity<Map<String, Object>> triggerDelivery(@PathVariable UUID orderId) {
        OrderDto order = orderServiceClient.getOrder(orderId);
        if (order == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Order not found: " + orderId
            ));
        }

        String pin = order.verificationPin() != null ? order.verificationPin() : "123456";
        boolean delivered = orderServiceClient.deliverOrder(orderId, pin);
        return ResponseEntity.ok(Map.of(
                "success", delivered,
                "orderId", orderId,
                "verificationPin", pin
        ));
    }
}
