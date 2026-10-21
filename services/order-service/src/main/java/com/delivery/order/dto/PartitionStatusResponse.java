package com.delivery.order.dto;

import java.util.List;

public record PartitionStatusResponse(
        int totalPartitions,
        List<String> activePartitions,
        long deadTuplesCount
) {}
