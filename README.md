<div align="center">

# Sovereign Hyper-Local Video & Food Delivery Platform

**Production-Grade, 100% Offline, Geodesic-Accurate Food & Video Delivery Engine**

[![Java](https://img.shields.io/badge/Java_21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot_3.4.3-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostGIS](https://img.shields.io/badge/PostGIS_16-336791?style=for-the-badge&logo=postgresql&logoColor=white)](https://postgis.net/)
[![Kubernetes](https://img.shields.io/badge/Kubernetes-326CE5?style=for-the-badge&logo=kubernetes&logoColor=white)](https://kubernetes.io/)
[![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](LICENSE)

<br/>

[Architecture & MDA Specification](docs/ARCHITECTURE.md) • [System Internals & Deep-Dives](docs/SYSTEM_INTERNALS.md) • [REST API Reference](docs/API_REFERENCE.md) • [Operations Runbook](docs/RUNBOOK.md) • [Contributing](CONTRIBUTING.md)

</div>

---

## 📌 Executive Summary & Architectural Philosophy

Modern on-demand food delivery and dispatch platforms commonly fail when deployed into sovereign, air-gapped, or resource-constrained local development environments. These failures stem from three acute distributed systems anti-patterns:

1. **Latitude Area Distortion in Geospatial Engines:** Most commercial tutorials store delivery boundaries in Web Mercator (`EPSG:3857`). Because Web Mercator projects the Earth onto a flat cylinder, scale distortion accelerates with latitude ($k = \sec\phi$). At $37.77^\circ\text{N}$ (San Francisco), areas are distorted by **$+59.6\%$**; at $51.5^\circ\text{N}$ (London), distortion exceeds **$+158\%$**, falsely rejecting valid customer addresses or including unreachable ocean locations.
2. **Thundering Herd Dispatch Contention:** Broadcasting orders to all couriers simultaneously causes severe lock contention, cellular connection churn, and driver rejection fatigue.
3. **Transactional Outbox WAL Bloat:** High-throughput transactional outbox tables that clean up old events using standard SQL `DELETE` queries trigger massive PostgreSQL MVCC dead-tuple churn, bloat Write-Ahead Logging (WAL), and invalidate downstream Change Data Capture (CDC) replication slots.

This platform resolves these challenges by strictly enforcing six non-negotiable architectural contracts:

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                NON-NEGOTIABLE CONTRACTS                                │
├────────────────────────────┬────────────────────────────┬──────────────────────────────┤
│  1. 100% Sovereign         │  2. Single Host Ingress    │  3. Geodesic CRS Precision   │
│  Zero public cloud SaaS    │  Port 8080 host-bound only │  EPSG:4326 PostGIS only      │
│  Local boundary mocks      │  CNI encapsulated routing  │  ST_Covers on WGS 84 Spheroid│
├────────────────────────────┼────────────────────────────┼──────────────────────────────┤
│  4. Targeted 1-to-1 Lease  │  5. Zero-WAL Outbox Prune  │  6. Hardware Acceleration    │
│  30-second server lock     │  Daily range partitions    │  AMD RX 7900 GRE VCN 4.0     │
│  Optimistic @Version DB    │  DROP TABLE retention jobs │  Mesa VAAPI HLS Transcoder   │
└────────────────────────────┴────────────────────────────┴──────────────────────────────┘
```

---

## 🏛️ System Topology (C4 Level 2: Container Network Interface)

All microservices, databases, and message brokers communicate over the internal Kubernetes **Container Network Interface (CNI)**. Exactly one single port (`8080`) is bound to the host workstation:

```mermaid
%%{init: {'theme': 'base', 'themeVariables': {'primaryColor': '#1e293b', 'edgeColor': '#64748b', 'textColor': '#f8fafc', 'primaryTextColor': '#f8fafc'}}}%%
flowchart TD
    Client["👤 Client / Browser / Mobile App / Test Runner"]
    
    subgraph HostGateway ["Single Host Ingress Gateway (Port 8080)"]
        Ingress["🚪 Kubernetes Ingress Controller (Ingress-Nginx)<br/>• Host Port: 8080<br/>• Path Routing, CORS, WebSockets, HTTP/2"]
    end

    subgraph CNI ["Internal Kubernetes CNI Network (ClusterIP & CoreDNS)"]
        subgraph MapsAndTwin ["Maps, Routing & Digital Twin (Phase 5)"]
            Martin["🗺️ martin-service:3000<br/>• PMTiles Vector Tile Server<br/>• Offline /tiles/city/{z}/{x}/{y}"]
            Valhalla["🚗 valhalla-service:8002<br/>• OSM Dynamic Routing Engine<br/>• Turn-by-Turn Maneuvers & ETAs"]
            Simulator["🛵 courier-simulator:8080<br/>• 20 Virtual Couriers Online<br/>• Auto-Claim & 3s Telemetry Emitter"]
            UI["🖥️ digital-twin-ui:80<br/>• MapLibre GL Offline Map Client<br/>• Live WebSocket Marker & PIN Handshake"]
        end

        subgraph CoreServices ["Core Domain Microservices (Java 21 / Project Loom Virtual Threads)"]
            Auth["🔐 auth-service:8080<br/>• Passwordless Phone OTP<br/>• Asymmetric Ed25519 JWT Signing<br/>• In-Memory JWKS Discovery"]
            Catalog["🍽️ catalog-service:8080<br/>• PostGIS 4326 Spheroid ST_Covers<br/>• Uber H3 Res 8 Coarse Binning<br/>• Menu & 86-ing Kitchen Catalog"]
            Order["📦 order-service:8080<br/>• Idempotent Checkout<br/>• 1-to-1 Dispatch Lease State Machine<br/>• Saga Timeout Daemon & Daily Outbox"]
            Tracking["📡 tracking-service:8080<br/>• Geodesic Kinematic Anti-Spoofing<br/>• Redis Geospatial Sharding<br/>• /ws/tracking Gateway"]
            Media["🎥 media-service:8080<br/>• Chunked TUS Protocol & MinIO S3<br/>• Transcode Job Dispatch"]
        end

        subgraph LocalBoundary ["Sovereign Boundary Mocks (Zero Runtime SaaS)"]
            Payment["💳 local-payment-service:8080<br/>• Double-Entry Balance Ledger<br/>• Zero SaaS Runtime Escrow"]
            SMS["📱 local-sms-gateway:8080<br/>• Circular Buffer Memory Storage<br/>• Instant OTP Inspection API"]
            Push["🔔 ntfy:80<br/>• WebSocket & SSE Push Broker"]
        end

        subgraph Datastores ["Internal Datastores & Replication"]
            Postgres[("🗄️ PostgreSQL 16 (PostGIS 3.4)<br/>• Hardened CDC: max_slot_wal_keep_size=-1<br/>• wal_level=logical<br/>• GiST Spatial R-Tree Indexes")]
            Redis[("⚡ Redis 7 (In-Memory Telemetry)<br/>• GeoSpatial Clustered Sharding<br/>• Telemetry Breadcrumb TTLs")]
        end
    end

    Client -->|HTTP / WebSockets on Port 8080| Ingress
    Ingress -->|/| UI
    Ingress -->|/tiles/*| Martin
    Ingress -->|/routing/*| Valhalla
    Ingress -->|/api/v1/simulator/*| Simulator
    Ingress -->|/api/v1/tracking/* & /ws/tracking/*| Tracking
    Ingress -->|/api/v1/media/*| Media
    Ingress -->|/api/v1/auth/* & /.well-known/*| Auth
    Ingress -->|/api/v1/catalog/*| Catalog
    Ingress -->|/api/v1/orders/*| Order
    Ingress -->|/api/v1/payments/*| Payment
    Ingress -->|/api/v1/sms/* & /admin/sms-inbox| SMS
    Ingress -->|/push/*| Push

    Simulator -->|Claim lease & out-for-delivery| Order
    Simulator -->|Query road waypoints| Valhalla
    Simulator -->|Emit 3s GPS telemetry| Tracking
    Tracking -->|State & breadcrumbs| Redis
    UI -->|Render offline vector tiles| Martin
    UI -->|Stream live courier markers| Tracking

    Auth -->|User credentials & OTP storage| Postgres
    Auth -.->|OTP dispatch API| SMS
    Catalog -->|ST_Covers queries| Postgres
    Order -->|Orders & Partitioned Outbox| Postgres
    Payment -->|Ledger accounts & Journal entries| Postgres
    Order -.->|Geofence verification| Catalog
    Order -.->|Escrow HOLD request| Payment

    classDef clientNode fill:#0f172a,stroke:#38bdf8,stroke-width:2px,color:#f8fafc;
    classDef gatewayNode fill:#0f172a,stroke:#818cf8,stroke-width:2px,color:#f8fafc;
    classDef serviceNode fill:#0f172a,stroke:#34d399,stroke-width:2px,color:#f8fafc;
    classDef boundaryNode fill:#0f172a,stroke:#f59e0b,stroke-width:2px,color:#f8fafc;
    classDef storeNode fill:#1e1b4b,stroke:#a78bfa,stroke-width:2px,color:#f8fafc;

    class Client clientNode;
    class Ingress gatewayNode;
    class Auth,Catalog,Order serviceNode;
    class Payment,SMS,Push boundaryNode;
    class Postgres storeNode;
```

---

## 🔬 Core Architectural Breakthroughs

### 1. Geodesic CRS Precision (`geography(Polygon, 4326)` vs `geometry(3857)`)
* Storing service zones in Web Mercator (`EPSG:3857`) introduces up to **$+60\%$** scale distortion at non-equatorial latitudes.
* All service zones and customer coordinates are strictly typed as **`geography(Polygon, 4326)`** and **`geography(Point, 4326)`**.
* Containment queries execute natively on the WGS 84 ellipsoid via `ST_Covers`:
  ```sql
  SELECT r.id, r.name FROM restaurants r
  WHERE r.is_active = true
    AND ST_Covers(r.delivery_zone, ST_SetSRID(ST_MakePoint(-122.4194, 37.7749), 4326)::geography);
  ```
* Unlike `ST_Contains` (which excludes perimeter edges), `ST_Covers` satisfies boundary closure: points lying directly on the delivery border evaluate to `TRUE`.

### 2. Two-Stage Spatial Resolution (Uber H3 Res 8 + PostGIS Fine)
* **Stage 1 (Coarse In-Memory Filter):** Customer coordinates map to a 64-bit integer H3 cell in $O(1)$ CPU time. A 1-ring expansion (`kRing(H3, 1)`) retrieves candidate restaurants within 7 discrete hexagons.
* **Stage 2 (Fine Geodesic Containment):** Only the filtered candidate restaurants are evaluated with PostGIS `ST_Covers`, reducing spatial query latency from $O(N)$ polygon intersections to $O(1)$ indexed integer lookups.

### 3. Zero-WAL Transactional Outbox Partition Pruning
* High-volume order processing writes outbox events within the same ACID transaction as the order.
* Conventional systems run `DELETE FROM outbox_events WHERE created_at < ...`, causing massive PostgreSQL dead-tuple bloat (`n_dead_tup`), autovacuum page-locking, and WAL stream saturation.
* This platform declaratively partitions `outbox_events` by daily range:
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
* A scheduled daemon drops partitions older than 7 days via **`DROP TABLE <partition_name>;`**. This triggers an OS filesystem `unlink()` on the physical storage file, generating **`n_dead_tup == 0`** and zero WAL amplification.

---

## 📊 Workstation Resource Allocation Budget (Section 4.2)

Every container declares strict CPU and memory bounds matching the resource budget in Section 4.2 of [`plan.md`](file:///home/shreyas/plan.md):

| Container / Pod Name | CPU Request | CPU Limit | Memory Request | Memory Limit | QoS Class |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `ingress-nginx-controller` | `100m` | `500m` | `128Mi` | `256Mi` | Burstable |
| `auth-service` | `200m` | `1000m` | `384Mi` | `768Mi` | Burstable |
| `catalog-service` | `200m` | `1000m` | `384Mi` | `768Mi` | Burstable |
| `order-service` | `300m` | `1500m` | `512Mi` | `1024Mi` | Burstable |
| `tracking-service` | `250m` | `1000m` | `384Mi` | `768Mi` | Burstable |
| `media-service` | `100m` | `500m` | `256Mi` | `512Mi` | Burstable |
| `local-payment-service` | `100m` | `500m` | `256Mi` | `384Mi` | Burstable |
| `local-sms-gateway` | `50m` | `200m` | `64Mi` | `128Mi` | Burstable |
| `valhalla` (OSM Engine) | `500m` | `2000m` | `1024Mi` | `2048Mi` | Burstable |
| `martin` (Vector Tiles) | `100m` | `500m` | `128Mi` | `256Mi` | Burstable |
| `courier-simulator` | `100m` | `500m` | `128Mi` | `256Mi` | Burstable |
| `digital-twin-ui` | `50m` | `200m` | `64Mi` | `128Mi` | Burstable |
| `transcode-worker` (AMD) | `500m` | `2000m` | `1024Mi` | `2048Mi` | Burstable |
| `postgres` (PostGIS 16) | `500m` | `2000m` | `1536Mi` | `3072Mi` | Burstable |
| `redis` | `100m` | `500m` | `256Mi` | `512Mi` | Burstable |

---

## ⚡ Quickstart & Verification

### 1. Build Reactor & Execute Test Suite

```bash
# Clone the repository
git clone https://github.com/BlackPool25/delivery-platform.git
cd delivery-platform

# Compile and run the complete test reactor (includes PostGIS & Redis Testcontainers)
./mvnw clean test
```

### 2. Run Phase Verification Harnesses

```bash
# Phase 6: Verify ArchUnit Architectural Fitness & Concurrency Race Condition Proofs
./mvnw -pl services/order-service test -Dtest=OrderArchitectureFitnessTest,OrderConcurrencyRaceConditionTest
./mvnw -pl services/catalog-service test -Dtest=CatalogArchitectureFitnessTest

# Phase 6: Verify High-Frequency gRPC Telemetry Service (Unary & Streaming)
./mvnw -pl services/tracking-service test -Dtest=CourierTelemetryGrpcVerificationTest

# Phase 6: Execute k6 Workstation Load Benchmark & Native gRPC Tests
k6 run --vus 50 --duration 15s load-tests/workstation-benchmark.js
k6 run --vus 10 --duration 10s load-tests/grpc-benchmark.js

# Phase 6: Run Fast Synthetic Staging Teardown (< 100ms Target) & Chaos Suite
bash load-tests/fast-teardown.sh 8085
bash scripts/run-chaos-suite.sh

# Phase 5: Verify Courier Simulator Runner, Waypoints, Telemetry & PIN Handshake
./mvnw -pl services/courier-simulator test -Dtest=CourierSimulatorVerificationTest
./mvnw -pl services/courier-simulator test -Dtest=SimulatorControllerIntegrationTest

# Phase 4: Verify Kinematic Anti-Spoofing Geodesic Filter (V < 120 km/h)
./mvnw -pl services/tracking-service test -Dtest=KinematicAntiSpoofingVerificationTest

# Phase 4: Verify TUS Resumable Video Upload Ingestion
./mvnw -pl services/media-service test -Dtest=MediaUploadTusIntegrationTest

# Phase 3: Verify Passwordless Phone OTP & Asymmetric Ed25519 JWKS
./mvnw -pl services/auth-service test -Dtest=AuthVerificationGateTest

# Phase 3: Verify Two-Stage H3 Res 8 + PostGIS Geodesic ST_Covers & 86-ing Menu
./mvnw -pl services/catalog-service test -Dtest=CatalogSpatialVerificationTest

# Phase 3: Verify Order FSM, 1-to-1 Dispatch Claim Race, & Saga Timeout Coordinator
./mvnw -pl services/order-service test -Dtest=OrderLifecycleAndSagaVerificationTest

# Phase 2: Verify Daily Range Outbox Partitioning & Zero-WAL DROP TABLE Pruning
./mvnw -pl services/order-service test -Dtest=OrderOutboxPartitionVerificationTest
```

---

## 📁 Repository Structure

```text
delivery-platform/
├── .github/
│   └── workflows/
│       ├── ci.yml                     # Continuous Integration pipeline
│       └── docs.yml                   # MkDocs Material automated deploy
├── docker/
│   ├── postgresql.conf                # Hardened CDC & WAL Postgres config
│   ├── Dockerfile.service             # Multi-stage Java 21 containerfile
│   ├── docker-compose-phase2.yaml     # Phase 2 local container composition
│   └── docker-compose-phase3.yaml     # Phase 3 local container composition
├── docs/
│   ├── ARCHITECTURE.md                # arc42 & OMG MDA formal specification
│   ├── SYSTEM_INTERNALS.md            # Deep math, H3 bitfields & storage internals
│   ├── API_REFERENCE.md               # Complete REST & gRPC API reference
│   └── RUNBOOK.md                     # Production operations & chaos runbook
├── k8s/
│   ├── chaos/                         # Chaos Mesh injection manifests
│   ├── ingress/                       # Single-port ingress manifests (port 8080/8085)
│   ├── postgres/                      # PostGIS 16 StatefulSet with strict cgroups
│   ├── redis/                         # Redis 7 in-memory spatial cache
│   ├── auth-service/                  # Auth deployment & ClusterIP service
│   ├── catalog-service/               # Catalog deployment & ClusterIP service
│   ├── order-service/                 # Order deployment & ClusterIP service
│   └── tracking-service/              # Tracking REST & gRPC (port 9090)
├── load-tests/
│   ├── workstation-benchmark.js       # k6 4-hub Pareto load benchmark (850+ RPS)
│   ├── grpc-benchmark.js              # Native k6 binary gRPC telemetry benchmark
│   ├── pareto-clusters.json           # 70/30 San Francisco dining hub coordinates
│   ├── toxiproxy-config.json          # 4G LTE / 3G cellular impairment profiles
│   └── fast-teardown.sh               # Sub-100ms staging truncation & Redis unlink
├── scripts/
│   ├── run-load-test.sh               # Automated load runner wrapper
│   └── run-chaos-suite.sh             # Automated pod kill & self-healing test suite
├── migrations/
│   ├── auth-service/                  # Flyway V1 DDL: Users, OTPs, Refresh tokens
│   ├── catalog-service/               # Flyway V1 DDL: PostGIS geography types
│   ├── order-service/                 # Flyway V1 DDL: Partitioned outbox & orders
│   └── payment-service/               # Flyway V1 DDL: Double-entry ledger
├── services/
│   ├── auth-service/                  # Ed25519 JWT, JWKS, and passwordless OTP
│   ├── catalog-service/               # Uber H3 Res 8, 86-ing items, PostGIS 4326
│   ├── order-service/                 # 1-to-1 lease FSM, Saga daemon, Outbox
│   ├── local-payment-service/         # Sovereign double-entry ledger mock
│   └── local-sms-gateway/             # Circular-buffer mock SMS gateway
├── mkdocs.yml                         # Material for MkDocs documentation configuration
├── pom.xml                            # Root Maven multi-module reactor
└── README.md                          # Repository storefront & architectural brief
```

---

## 📜 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
