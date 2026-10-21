package com.delivery.order.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OutboxPartitionService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPartitionService.class);
    private static final Pattern PARTITION_PATTERN = Pattern.compile("^outbox_events_y(\\d{4})m(\\d{2})d(\\d{2})$");

    private final JdbcTemplate jdbcTemplate;

    public OutboxPartitionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Creates a daily range partition for a specific date if it does not already exist.
     * Table name format: outbox_events_yYYYYmMMdDD
     * Range: [date 00:00:00+00, nextDate 00:00:00+00)
     */
    public String createDailyPartition(LocalDate date) {
        String partitionName = formatPartitionName(date);
        LocalDate nextDate = date.plusDays(1);

        String sql = String.format("""
            CREATE TABLE IF NOT EXISTS %s PARTITION OF outbox_events
            FOR VALUES FROM ('%s 00:00:00+00') TO ('%s 00:00:00+00');
            """, partitionName, date, nextDate);

        try {
            jdbcTemplate.execute(sql);
            log.info("Ensured daily outbox partition: {} for range [{} to {})", partitionName, date, nextDate);
            return partitionName;
        } catch (DataAccessException ex) {
            log.error("Failed to create outbox partition: {}", partitionName, ex);
            throw ex;
        }
    }

    /**
     * Pre-creates daily partitions from a start date for the specified number of days ahead.
     */
    public List<String> ensurePartitionsExist(LocalDate startDate, int daysAhead) {
        List<String> created = new ArrayList<>();
        for (int i = 0; i <= daysAhead; i++) {
            LocalDate targetDate = startDate.plusDays(i);
            String name = createDailyPartition(targetDate);
            created.add(name);
        }
        return created;
    }

    /**
     * Lists all child partitions attached to outbox_events.
     */
    public List<String> listPartitions() {
        String sql = """
            SELECT c.relname
            FROM pg_inherits i
            JOIN pg_class p ON i.inhparent = p.oid
            JOIN pg_class c ON i.inhrelid = c.oid
            WHERE p.relname = 'outbox_events'
            ORDER BY c.relname;
            """;
        return jdbcTemplate.queryForList(sql, String.class);
    }

    /**
     * Zero-WAL Outbox Partition Pruning:
     * Identifies partitions older than retentionDays and executes DROP TABLE.
     * Unlike DELETE queries which churn MVCC dead rows and bloat WAL, DROP TABLE
     * instantly reclaims disk space with 0 dead rows and 0 WAL bloat.
     */
    public List<String> prunePartitionsOlderThan(int retentionDays) {
        LocalDate cutoffDate = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays);
        log.info("Executing zero-WAL outbox partition pruning for partitions strictly before cutoff: {} (retention: {} days)",
                cutoffDate, retentionDays);

        List<String> allPartitions = listPartitions();
        List<String> droppedPartitions = new ArrayList<>();

        for (String partitionName : allPartitions) {
            Matcher matcher = PARTITION_PATTERN.matcher(partitionName);
            if (matcher.matches()) {
                int year = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                int day = Integer.parseInt(matcher.group(3));
                LocalDate partitionDate = LocalDate.of(year, month, day);

                if (partitionDate.isBefore(cutoffDate)) {
                    log.info("Dropping expired outbox partition: {} (dated {}, cutoff {})",
                            partitionName, partitionDate, cutoffDate);
                    String dropSql = "DROP TABLE IF EXISTS " + partitionName + ";";
                    jdbcTemplate.execute(dropSql);
                    droppedPartitions.add(partitionName);
                }
            }
        }

        log.info("Outbox partition pruning complete. Dropped {} partitions: {}",
                droppedPartitions.size(), droppedPartitions);
        return droppedPartitions;
    }

    /**
     * Returns total dead tuples count across all outbox_events tables.
     * Used in verification gates to confirm 0 dead row overhead.
     */
    public long getDeadTuplesCount() {
        String sql = """
            SELECT COALESCE(SUM(n_dead_tup), 0)
            FROM pg_stat_user_tables
            WHERE relname LIKE 'outbox_events%'
            """;
        Long deadTuples = jdbcTemplate.queryForObject(sql, Long.class);
        return deadTuples != null ? deadTuples : 0L;
    }

    public static String formatPartitionName(LocalDate date) {
        return String.format("outbox_events_y%04dm%02dd%02d",
                date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }
}
