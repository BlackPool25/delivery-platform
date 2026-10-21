package com.delivery.order.dto;

import java.util.UUID;

public record DispatchLeaseRequest(
        UUID courierCandidateId,
        Integer leaseSeconds
) {}
