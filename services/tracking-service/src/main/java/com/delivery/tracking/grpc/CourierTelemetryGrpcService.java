package com.delivery.tracking.grpc;

import com.delivery.tracking.model.CourierState;
import com.delivery.tracking.model.TelemetryIngestResult;
import com.delivery.tracking.model.TelemetryPing;
import com.delivery.tracking.service.TrackingService;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-performance gRPC implementation for courier telemetry streaming and querying.
 * Processes high-frequency binary Protobuf messages on Java 21 Virtual Threads.
 */
@Service
public class CourierTelemetryGrpcService extends CourierTelemetryServiceGrpc.CourierTelemetryServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(CourierTelemetryGrpcService.class);

    private final TrackingService trackingService;

    public CourierTelemetryGrpcService(TrackingService trackingService) {
        this.trackingService = trackingService;
    }

    @Override
    public void ingestTelemetry(TelemetryPingRequest request, StreamObserver<TelemetryResponse> responseObserver) {
        try {
            UUID courierId = UUID.fromString(request.getCourierId());
            TelemetryPing ping = toDomainPing(request);
            TelemetryIngestResult result = trackingService.ingestTelemetry(courierId, ping);

            TelemetryResponse response = toProtoResponse(result);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid courier UUID received over gRPC: {}", request.getCourierId());
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("Invalid courier UUID: " + e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            log.error("Error processing gRPC telemetry ingest", e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Internal error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public StreamObserver<TelemetryPingRequest> streamTelemetry(StreamObserver<TelemetryStreamSummary> responseObserver) {
        long startTime = System.currentTimeMillis();
        AtomicInteger totalReceived = new AtomicInteger();
        AtomicInteger totalAccepted = new AtomicInteger();
        AtomicInteger totalRejected = new AtomicInteger();

        return new StreamObserver<>() {
            @Override
            public void onNext(TelemetryPingRequest request) {
                totalReceived.incrementAndGet();
                try {
                    UUID courierId = UUID.fromString(request.getCourierId());
                    TelemetryPing ping = toDomainPing(request);
                    TelemetryIngestResult result = trackingService.ingestTelemetry(courierId, ping);
                    if (result.accepted()) {
                        totalAccepted.incrementAndGet();
                    } else {
                        totalRejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    totalRejected.incrementAndGet();
                    log.warn("Failed processing streamed telemetry ping: {}", e.getMessage());
                }
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Telemetry gRPC stream aborted by client: {}", t.getMessage());
            }

            @Override
            public void onCompleted() {
                long duration = System.currentTimeMillis() - startTime;
                TelemetryStreamSummary summary = TelemetryStreamSummary.newBuilder()
                        .setTotalReceived(totalReceived.get())
                        .setTotalAccepted(totalAccepted.get())
                        .setTotalRejected(totalRejected.get())
                        .setDurationMs(duration)
                        .build();
                responseObserver.onNext(summary);
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void getCourierLocation(CourierLocationRequest request, StreamObserver<CourierLocationResponse> responseObserver) {
        try {
            UUID courierId = UUID.fromString(request.getCourierId());
            CourierState state = trackingService.getCourierState(courierId);
            com.delivery.tracking.model.FraudFlag fraudFlag = trackingService.getFraudStatus(courierId);

            if (state == null && fraudFlag == null) {
                responseObserver.onNext(CourierLocationResponse.newBuilder()
                        .setCourierId(request.getCourierId())
                        .setFound(false)
                        .build());
            } else {
                boolean isFlagged = (fraudFlag != null) || (state != null && state.isFlagged());
                String fraudReason = fraudFlag != null ? fraudFlag.reason() : (state != null ? state.fraudReason() : "");

                CourierLocationResponse.Builder builder = CourierLocationResponse.newBuilder()
                        .setCourierId(courierId.toString())
                        .setLatitude(state != null && state.latitude() != null ? state.latitude() : 0.0)
                        .setLongitude(state != null && state.longitude() != null ? state.longitude() : 0.0)
                        .setSpeedKmh(state != null && state.speedKmh() != null ? state.speedKmh() : 0.0)
                        .setHeading(state != null && state.heading() != null ? state.heading() : 0.0)
                        .setTimestampEpochMs(state != null && state.timestamp() != null ? state.timestamp().toEpochMilli() : 0)
                        .setH3Index(state != null && state.h3Index() != null ? state.h3Index() : "")
                        .setIsFlagged(isFlagged)
                        .setFraudReason(fraudReason != null ? fraudReason : "")
                        .setFound(true);
                responseObserver.onNext(builder.build());
            }
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("Invalid courier UUID: " + e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            log.error("Error retrieving courier location via gRPC", e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Internal error: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    private TelemetryPing toDomainPing(TelemetryPingRequest request) {
        Instant ts = request.getTimestampEpochMs() > 0
                ? Instant.ofEpochMilli(request.getTimestampEpochMs())
                : Instant.now();

        return new TelemetryPing(
                request.getLatitude(),
                request.getLongitude(),
                ts,
                request.getSpeedKmh(),
                request.getHeading(),
                request.getAltitude(),
                request.getAccuracy()
        );
    }

    private TelemetryResponse toProtoResponse(TelemetryIngestResult result) {
        return TelemetryResponse.newBuilder()
                .setStatus(result.status() != null ? result.status() : "")
                .setAccepted(result.accepted())
                .setCalculatedSpeedKmh(result.calculatedSpeedKmh())
                .setMaxAllowedSpeedKmh(result.maxAllowedSpeedKmh())
                .setMessage(result.message() != null ? result.message() : "")
                .setTimestampEpochMs(result.timestamp() != null ? result.timestamp().toEpochMilli() : Instant.now().toEpochMilli())
                .build();
    }
}
