package com.delivery.order.controller;

import com.delivery.order.dto.PartitionStatusResponse;
import com.delivery.order.outbox.OutboxPartitionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@RestController
@RequestMapping("/api/v1/orders/admin/outbox")
public class OutboxAdminController {

    private final OutboxPartitionService partitionService;

    public OutboxAdminController(OutboxPartitionService partitionService) {
        this.partitionService = partitionService;
    }

    @GetMapping("/partitions")
    public ResponseEntity<PartitionStatusResponse> getPartitionsStatus() {
        List<String> partitions = partitionService.listPartitions();
        long deadTuples = partitionService.getDeadTuplesCount();
        return ResponseEntity.ok(new PartitionStatusResponse(partitions.size(), partitions, deadTuples));
    }

    @PostMapping("/prune")
    public ResponseEntity<List<String>> prunePartitions(
            @RequestParam(value = "retentionDays", defaultValue = "7") int retentionDays) {
        List<String> dropped = partitionService.prunePartitionsOlderThan(retentionDays);
        return ResponseEntity.ok(dropped);
    }

    @PostMapping("/partitions")
    public ResponseEntity<List<String>> createUpcomingPartitions(
            @RequestParam(value = "daysAhead", defaultValue = "14") int daysAhead) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<String> created = partitionService.ensurePartitionsExist(today, daysAhead);
        return ResponseEntity.ok(created);
    }
}
