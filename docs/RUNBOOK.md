# Production Operations & Runbook Guide

> **Sovereign Hyper-Local Video & Food Delivery Platform**  
> *Operations, Troubleshooting, Failure Modes, Diagnostics & Local Kubernetes Cluster Runbook*

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

### Configuration Parameters (`postgresql.conf`)
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

### Verification Query
Run this command inside the database container to verify:
```bash
docker exec -it delivery-postgres-phase2 psql -U postgres -d delivery_db -c "
SELECT name, setting, unit FROM pg_settings 
WHERE name IN ('wal_level', 'max_slot_wal_keep_size', 'wal_keep_size', 'max_replication_slots');
"
```

Expected output:
```text
          name          | setting | unit 
------------------------+---------+------
 max_replication_slots  | 10      | 
 max_slot_wal_keep_size | -1      | MB
 wal_keep_size          | 4096    | MB
 wal_level              | logical | 
```

---

## 3. Outbox Table Maintenance & Monitoring

### Why `DROP TABLE` instead of `DELETE`?
Standard SQL `DELETE FROM outbox_events WHERE created_at < ...` queries generate massive amounts of MVCC dead-tuple churn, forcing the PostgreSQL autovacuum daemon into aggressive cycles and generating redundant WAL records.

In this architecture, `outbox_events` is partitioned daily:
```sql
CREATE TABLE outbox_events (
    id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);
```

When partitions exceed the 7-day retention window, the Spring scheduler issues:
```sql
DROP TABLE outbox_events_y2026m10d14;
```
This reclaims disk blocks instantly on the underlying filesystem with **zero dead rows** (`n_dead_tup == 0`) and minimal WAL write volume.

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

# Verify Port 8080 Binding on Host
ss -tulpn | grep 8080
```

---

## 6. Phase 5 Operations & Verification Gate Runbook

### 6.1 Starting Phase 5 Infrastructure
```bash
# Option A: Local Docker Compose Ingress Environment
docker compose -f docker/docker-compose-phase5.yaml up -d

# Option B: Kubernetes Deployment Bundle
kubectl apply -f k8s/postgres/postgres.yaml
kubectl apply -f k8s/redis/redis.yaml
kubectl apply -f k8s/rabbitmq/rabbitmq.yaml
kubectl apply -f k8s/minio/minio.yaml
kubectl apply -f k8s/ntfy/ntfy.yaml
kubectl apply -f k8s/local-payment-service/local-payment-service.yaml
kubectl apply -f k8s/local-sms-gateway/local-sms-gateway.yaml
kubectl apply -f k8s/auth-service/auth-service.yaml
kubectl apply -f k8s/catalog-service/catalog-service.yaml
kubectl apply -f k8s/order-service/order-service.yaml
kubectl apply -f k8s/tracking-service/tracking-service.yaml
kubectl apply -f k8s/media-service/media-service.yaml
kubectl apply -f k8s/transcoder/transcode-worker.yaml
kubectl apply -f k8s/martin/martin.yaml
kubectl apply -f k8s/valhalla/valhalla.yaml
kubectl apply -f k8s/courier-simulator/courier-simulator.yaml
kubectl apply -f k8s/digital-twin-ui/digital-twin-ui.yaml
kubectl apply -f k8s/ingress/delivery-ingress-phase5.yaml
```

### 6.2 Health & Status Verification Gates
```bash
# 1. Verify Ingress Gateway & Order Service
curl -s http://localhost:8080/api/v1/orders/actuator/health

# 2. Verify Martin Offline Vector Tile Server
curl -i http://localhost:8080/tiles/city/0/0/0
# Expected: HTTP 200 OK with Content-Type: application/x-protobuf

# 3. Verify Valhalla Dynamic Routing Engine
curl -s http://localhost:8080/routing/status
# Expected: {"status": "ready", ...}

# 4. Verify Virtual Courier Simulator Runner
curl -s http://localhost:8080/api/v1/simulator/status
# Expected: {"status": "ONLINE", "totalCouriers": 20, ...}
```

### 6.3 End-to-End Digital Twin Simulation Run
```bash
# 1. Open the MapLibre GL Digital Twin UI in your browser:
# http://localhost:8080/

# 2. Or execute via CLI:
# Place Order
ORDER_ID=$(curl -s -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "00000000-0000-0000-0000-000000000001",
    "restaurantId": "a1111111-1111-1111-1111-111111111111",
    "dropoffLatitude": 37.7650,
    "dropoffLongitude": -122.4150,
    "dropoffBuilding": "450 Valencia St",
    "dropoffFloor": "3",
    "dropoffRoom": "3B",
    "verificationPin": "482915",
    "items": [{"dishId": "b1111111-1111-1111-1111-111111111101", "dishName": "Birria Tacos", "unitPrice": 14.50, "quantity": 2}]
  }' | jq -r .id)

# Execute Ledger Hold
curl -s -X POST "http://localhost:8080/api/v1/orders/${ORDER_ID}/pay"

# Kitchen Accept & Prepare
curl -s -X POST "http://localhost:8080/api/v1/orders/${ORDER_ID}/accept"
curl -s -X POST "http://localhost:8080/api/v1/orders/${ORDER_ID}/prepare"
curl -s -X POST "http://localhost:8080/api/v1/orders/${ORDER_ID}/ready"

# Offer Targeted 30-Second Lease
curl -s -X POST "http://localhost:8080/api/v1/orders/${ORDER_ID}/lease" \
  -H "Content-Type: application/json" -d '{"leaseSeconds": 30}'

# Trigger Courier Simulator Dispatch
curl -s -X POST "http://localhost:8080/api/v1/simulator/dispatch/${ORDER_ID}"

# Complete Doorstep PIN Handshake Verification
curl -s -X POST "http://localhost:8080/api/v1/orders/${ORDER_ID}/deliver" \
  -H "Content-Type: application/json" \
  -d '{"verificationPin": "482915"}'
```
