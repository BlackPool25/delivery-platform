package com.delivery.tracking.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages the embedded gRPC Netty server lifecycle within the Spring context.
 * Binds to the configured port (default: 9090) and processes calls on Virtual Threads.
 */
@Component
public class GrpcServerLifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycleManager.class);

    private final CourierTelemetryGrpcService telemetryGrpcService;
    private final int port;
    private final boolean enabled;
    private Server server;

    public GrpcServerLifecycleManager(
            CourierTelemetryGrpcService telemetryGrpcService,
            @Value("${grpc.server.port:9090}") int port,
            @Value("${grpc.server.enabled:true}") boolean enabled) {
        this.telemetryGrpcService = telemetryGrpcService;
        this.port = port;
        this.enabled = enabled;
    }

    @PostConstruct
    public void start() throws IOException {
        if (!enabled) {
            log.info("gRPC server is disabled by configuration");
            return;
        }

        this.server = ServerBuilder.forPort(port)
                .addService(telemetryGrpcService)
                // Use Java 21 Virtual Threads per task executor
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build()
                .start();

        log.info("gRPC CourierTelemetryService started successfully on port {}", server.getPort());
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            log.info("Shutting down gRPC CourierTelemetryService on port {}", server.getPort());
            server.shutdown();
            try {
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("gRPC server did not terminate within 5 seconds, forcing shutdown");
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                server.shutdownNow();
            }
        }
    }

    public int getPort() {
        return server != null ? server.getPort() : port;
    }

    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }
}
