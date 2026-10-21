package com.delivery.order.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class OutboxPartitionScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPartitionScheduler.class);
    private static final int DEFAULT_DAYS_AHEAD = 14;
    private static final int DEFAULT_RETENTION_DAYS = 7;

    private final OutboxPartitionService partitionService;

    public OutboxPartitionScheduler(OutboxPartitionService partitionService) {
        this.partitionService = partitionService;
    }

    /**
     * Initializes partitions on application boot so the current date and next 14 days
     * always have designated physical tables ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("Bootstrapping outbox partitions on application startup...");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<String> partitions = partitionService.ensurePartitionsExist(today, DEFAULT_DAYS_AHEAD);
        log.info("Startup outbox partitions verified: {}", partitions);
    }

    /**
     * Scheduled daily at 00:05 UTC: pre-create upcoming daily partitions for the next 14 days.
     */
    @Scheduled(cron = "${outbox.partition.create-cron:0 5 0 * * *}", zone = "UTC")
    public void preCreateUpcomingPartitions() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        log.info("Running daily scheduled partition pre-creation starting from {}", today);
        partitionService.ensurePartitionsExist(today, DEFAULT_DAYS_AHEAD);
    }

    /**
     * Scheduled daily at 02:00 UTC: execute zero-WAL partition pruning via DROP TABLE
     * for partitions older than 7 days.
     */
    @Scheduled(cron = "${outbox.partition.prune-cron:0 0 2 * * *}", zone = "UTC")
    public void scheduledPartitionPruning() {
        log.info("Running scheduled zero-WAL outbox partition pruning (older than {} days)", DEFAULT_RETENTION_DAYS);
        List<String> dropped = partitionService.prunePartitionsOlderThan(DEFAULT_RETENTION_DAYS);
        log.info("Scheduled pruning dropped {} partitions: {}", dropped.size(), dropped);
    }
}
