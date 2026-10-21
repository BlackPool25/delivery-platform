# Production Operations & Runbook Guide

> **Sovereign Hyper-Local Video & Food Delivery Platform**  
> *Operations, Troubleshooting, Failure Modes & Local Kubernetes Cluster Orchestration*

---

## 1. Local Workstation Requirements & Sizing

```text
Host Architecture : Linux x86_64 / ARM64 (AMD Ryzen / Intel Core 8+ Cores)
Memory Capacity   : 24GB - 32GB RAM Recommended (16GB Minimum)
Host Ingress Port : 8080 Only (No other host ports bound)
Container Runtime : containerd / Docker Engine 26+ (k3s, Kind, or Minikube)
Java Development  : OpenJDK 21 (Temurin / GraalVM)
Maven Version     : Apache Maven 3.9+ (Wrapper included: ./mvnw)
```

---

## 2. Hardened PostgreSQL & PostGIS Configuration

To permanently eliminate replication slot invalidation and CDC pipeline stalls during heavy local load bursts:

### Configuration Verification (`postgresql.conf`)
Ensure the following parameters are active in your PostgreSQL pod or container:

```ini
# Replication & CDC Outbox Hardening (Section 5.2)
wal_level = logical
max_replication_slots = 10
max_slot_wal_keep_size = -1
wal_keep_size = 4GB
max_wal_size = 8GB
checkpoint_timeout = 15min
```

Run this command inside the database container to verify:
```bash
docker exec -it delivery-postgres-phase2 psql -U postgres -d delivery_db -c "
SELECT name, setting, unit FROM pg_settings 
WHERE name IN ('wal_level', 'max_slot_wal_keep_size', 'wal_keep_size', 'max_replication_slots');
"
```

---

## 3. Outbox Table Maintenance & Monitoring

### Why `DROP TABLE` instead of `DELETE`?
Standard SQL `DELETE FROM outbox_events WHERE created_at < ...` queries generate massive amounts of MVCC dead-tuple churn, forcing the PostgreSQL autovacuum daemon into aggressive cycles and generating redundant WAL records.

In this architecture, `outbox_events` is partitioned daily:
```sql
CREATE TABLE outbox_events (...) PARTITION BY RANGE (created_at);
```

When partitions exceed the 7-day retention window, the Spring scheduler issues:
```sql
DROP TABLE outbox_events_y2026m10d14;
```
This reclaims disk blocks instantly on the underlying filesystem with **zero dead rows** and minimal WAL write volume.

### Monitoring Dead Tuples
```bash
docker exec -it delivery-postgres-phase2 psql -U postgres -d delivery_db -c "
SELECT schemaname, relname, n_dead_tup, n_live_tup 
FROM pg_stat_user_tables 
WHERE relname LIKE 'outbox_events%'
ORDER BY relname;
"
```
*Acceptance Criteria:* `n_dead_tup` must remain **`0`** across all partitions.

---

## 4. Failure Modes & Automated Recovery Runbook

| Failure Mode | Root Cause / Trigger | Automated Mitigation | Manual Operator Action |
| :--- | :--- | :--- | :--- |
| **Replication Slot Invalidation** | Outbox CDC consumer falls behind under load bursts. | `max_slot_wal_keep_size = -1` allows WAL accumulation up to available disk rather than dropping replication slot. | Ensure disk volume has at least 15GB free storage. |
| **Dispatch Double-Claim Race** | Multiple couriers attempt to claim the same order simultaneously. | Conditional update: `UPDATE orders SET courier_id = :id ... WHERE courier_id IS NULL AND dispatch_lease_expires_at > NOW()`. Exactly one courier succeeds; others receive HTTP 409 Conflict. | None required; self-healing state machine. |
| **Payment Hang in PENDING_PAYMENT** | Client network disconnection during checkout hold. | `SagaTimeoutCoordinator` polls every 15s; orders older than 90s are transitioned to `CANCELLED` and funds released. | Check payment journal logs: `SELECT * FROM journal_entries WHERE status = 'FAILED';`. |
| **Outbox Partition Missing** | High volume clock-skew or insertion before pre-creation job. | Inserts fall cleanly into `outbox_events_default` partition; no transaction aborts. | Trigger manual partition generation: `POST /api/v1/orders/admin/outbox/partitions?daysAhead=14`. |
| **Kinematic GPS Spoofing** | Courier submits simulated or teleported GPS coordinates. | Ingestion pipeline checks velocity $V = \frac{\Delta d}{\Delta t}$; if $V > 120\text{ km/h}$, telemetry is dropped and account flagged. | Review flagged courier accounts in administrative console. |

---

## 5. Kubernetes Single Ingress Verification

Verify all traffic terminates at host port 8080:

```bash
# Check Ingress Controller Pod
kubectl get pods -n ingress-nginx

# Check Ingress Routing Resource
kubectl describe ingress delivery-platform-ingress -n delivery-prod
```
