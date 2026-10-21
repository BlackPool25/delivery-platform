# System Architecture Specification & Model-Driven Architecture (MDA)

> **Sovereign Hyper-Local Video & Food Delivery Platform**  
> *Production-Grade Architecture Specification with Zero External SaaS Runtime Dependencies*

---

## 1. Executive Summary & Core Engineering Tenets

The delivery platform is an enterprise-scale distributed system built for high-concurrency hyper-local commerce, short-form food discovery video streaming, real-time spatial routing, and offline-first sovereign deployment.

### Non-Negotiable Architectural Tenets

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                ARCHITECTURAL PILLARS                                  │
├────────────────────────────┬─────────────────────────────┬─────────────────────────────┤
│   100% Offline & Sovereign │  Single Host Port (8080)    │  Geodesic CRS Precision     │
│   Zero runtime cloud SaaS  │  Strict CNI encapsulation   │  EPSG:4326 PostGIS only     │
│   Internal boundary mocks  │  Path-based L7 gateway      │  ST_Covers on WGS 84        │
├────────────────────────────┼─────────────────────────────┼─────────────────────────────┤
│   Deterministic Dispatch   │  Zero-WAL Outbox Hardening  │  Hardware GPU Acceleration  │
│   1-to-1 30s lease locks   │  Daily table partitioning   │  AMD Radeon RX 7900 GRE     │
│   Anti-race conditional DB │  Pruning via DROP TABLE     │  Mesa VAAPI HLS transcoding │
└────────────────────────────┴─────────────────────────────┴─────────────────────────────┘
```

1. **100% Offline & Sovereign (Zero Cloud SaaS):** Operates entirely disconnected from public internet at runtime. All external services (Stripe, Twilio, Google FCM, Apple APNs) are substituted by dedicated local containerized boundary services (`local-payment-service`, `local-sms-gateway`, `ntfy`).
2. **Single Host Port Ingress (Port 8080):** Exactly one host port (`8080`) is exposed on the host machine. All inter-service traffic flows over the internal Kubernetes Container Network Interface (CNI) via ClusterIPs and CoreDNS.
3. **Geodesic Spatial Precision (EPSG:4326 PostGIS):** Flawed Web Mercator (EPSG:3857) planar projections are strictly prohibited. Delivery zones and coordinates use `geography(Polygon, 4326)` and `geography(Point, 4326)` evaluated with native `ST_Covers`.
4. **Deterministic Concurrency & Hardened CDC Outbox:** Order dispatch is governed by server-authoritative 30-second 1-to-1 leases. The Transactional Outbox table is declaratively partitioned by day and pruned via `DROP TABLE` to guarantee **0 dead row WAL churn**.

---

## 2. Model-Driven Architecture (MDA) Realization

The system decouples core business logic from infrastructure using OMG's Model-Driven Architecture (MDA) layers:

```
┌───────────────────────────────────────────────────────────────────────────┐
│              CIM: Computation-Independent Model                           │
│  Domain Actors, Business Capabilities & Real-World Food Delivery Workflows│
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │
                                      ▼
┌───────────────────────────────────────────────────────────────────────────┐
│              PIM: Platform-Independent Model                              │
│  Logical Class Models, State Progression & Concurrency Contracts           │
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │
                                      ▼
┌───────────────────────────────────────────────────────────────────────────┐
│              PSM: Platform-Specific Model                                 │
│  PostgreSQL/PostGIS 16 Relational Schema, Partitioning, CNI Networking    │
└───────────────────────────────────────────────────────────────────────────┘
```

---

### 2.1 Computation-Independent Model (CIM): Business Capabilities & Actors

The CIM captures the real-world actors, business capabilities, and organizational workflows without technical implementation details.

```mermaid
flowchart TD
    subgraph Actors ["Domain Actors"]
        Customer["👤 Customer / Consumer"]
        Merchant["🏪 Restaurant Merchant"]
        Courier["🛵 Delivery Courier (or Digital Twin)"]
    end

    subgraph CoreCapabilities ["Core Business Capabilities"]
        BrowseDiscovery["📹 Discover Food via Video Reels<br/>(Location-Gated H3 Cell)"]
        VerifyCoverage["📍 Geofenced Cart & Checkout<br/>(ST_Covers Geodesic Evaluation)"]
        LedgerAuthorize["💳 Local Double-Entry Hold<br/>(Immutable Journal Hold)"]
        KitchenPrepare["🍳 Kitchen Preparation<br/>(Order Acceptance & Cooking)"]
        AutoDispatch["⚡ Targeted Dispatch Engine<br/>(1-to-1 30s Leased Offers)"]
        LiveTracking["🗺️ Live Telemetry Navigation<br/>(Valhalla OSM Street Routing)"]
        SecureHandshake["🤝 Delivery Handshake<br/>(Cryptographic PIN Verification)"]
    end

    Merchant -->|Publishes promo reels| BrowseDiscovery
    Customer -->|Streams nearby reels| BrowseDiscovery
    Customer -->|Places order with dropoff| VerifyCoverage
    VerifyCoverage -->|Reserves funds| LedgerAuthorize
    LedgerAuthorize -->|Alerts kitchen| KitchenPrepare
    KitchenPrepare -->|Dispatches pickup| AutoDispatch
    Courier -->|Accepts 30s lease| AutoDispatch
    Courier -->|Streams kinematics| LiveTracking
    Customer -->|Tracks driver on map| LiveTracking
    Courier -->|Verifies PIN at door| SecureHandshake
    SecureHandshake -->|Settles escrow funds| LedgerAuthorize
```

---

### 2.2 Platform-Independent Model (PIM): Structural & Behavioral Realization

#### A. Logical Domain Class Architecture

The PIM formalizes the structural relationships, attributes, and cardinality among core domain concepts.

```mermaid
classDiagram
    direction TB

    class Customer {
        +UUID id
        +String phone
        +GeoPoint homeLocation
        +String building
        +String floor
        +String room
        +placeOrder(cart)
    }

    class Restaurant {
        +UUID id
        +String name
        +GeoPoint location
        +Polygon deliveryZone
        +Long h3Res8
        +Boolean isActive
        +Boolean isAcceptingOrders
        +checkCoverage(dropoff) Boolean
        +acceptOrder(orderId)
    }

    class Dish {
        +UUID id
        +UUID restaurantId
        +String name
        +Decimal price
        +String cuisineType
        +Boolean isAvailable
        +toggleAvailability(status)
    }

    class VideoReel {
        +UUID id
        +UUID restaurantId
        +UUID dishId
        +String masterM3u8Url
        +Long h3Res8
        +Int durationSec
    }

    class Order {
        +UUID id
        +UUID userId
        +UUID restaurantId
        +UUID courierId
        +OrderStatus status
        +Decimal subtotal
        +Decimal deliveryFee
        +Decimal tax
        +Decimal totalAmount
        +GeoPoint dropoffLocation
        +String dropoffBuilding
        +String dropoffFloor
        +String dropoffRoom
        +String dropoffGateCode
        +String verificationPin
        +Instant dispatchLeaseExpiresAt
        +Long version
        +claimDispatch(courierId) Boolean
    }

    class OrderItem {
        +UUID id
        +UUID orderId
        +UUID dishId
        +String dishName
        +Decimal unitPrice
        +Int quantity
        +String notes
    }

    class Courier {
        +UUID id
        +String phone
        +CourierStatus status
        +GeoPoint currentLocation
        +Long currentH3Res9
        +Boolean isOnline
        +Long version
    }

    class LedgerAccount {
        +UUID id
        +UUID userId
        +Decimal balance
        +Decimal heldBalance
        +String currency
        +Long version
        +hold(amount)
        +capture(amount)
        +refund(amount)
    }

    class JournalEntry {
        +UUID id
        +UUID orderId
        +UUID sourceAccountId
        +UUID destAccountId
        +Decimal amount
        +EntryType entryType
        +EntryStatus status
        +Instant createdAt
    }

    Customer "1" --> "0..*" Order : places
    Restaurant "1" --> "1..*" Dish : offers
    Restaurant "1" --> "0..*" VideoReel : publishes
    Dish "1" --> "0..1" VideoReel : featured_in
    Restaurant "1" --> "0..*" Order : fulfills
    Order "1" *-- "1..*" OrderItem : contains
    Order "1" --> "0..1" Courier : assigned_to
    Customer "1" --> "1" LedgerAccount : owns
    LedgerAccount "1" --> "0..*" JournalEntry : records
```

---

#### B. Deterministic Order Lifecycle State Machine (Targeted 1-to-1 Lease)

Eliminates dispatch race conditions and broadcast noise by strictly issuing targeted 30-second leases to the optimal courier candidate:

```mermaid
stateDiagram-v2
    [*] --> Placed: Checkout submitted (Idempotency Key)
    Placed --> PaymentPending: Reserve funds on Local Ledger
    
    PaymentPending --> PaymentConfirmed: Ledger HOLD successful
    PaymentPending --> Cancelled: Insufficient funds / Timeout (90s Daemon)
    
    PaymentConfirmed --> Accepted: Merchant accepts order
    PaymentConfirmed --> Cancelled: Kitchen rejects (86-d / Closed)
    
    Accepted --> Preparing: Kitchen cooking starts
    Preparing --> ReadyForPickup: Food packaged & ready
    
    state "Targeted Dispatch Engine" as Dispatch {
        [*] --> CandidateSelection: H3 Res 9 Lookup + Valhalla ETA
        CandidateSelection --> OfferLeased: Issue 30s exclusive lease to Courier
        OfferLeased --> CandidateSelection: Lease expired / Courier rejected
        OfferLeased --> CourierAssigned: Courier accepts within 30s window
    }
    
    Preparing --> Dispatch: Background dispatch triggers
    ReadyForPickup --> Dispatched: Courier arrives at restaurant
    CourierAssigned --> Dispatched: Order collected by courier
    
    Dispatched --> OutForDelivery: Courier en route to dropoff
    OutForDelivery --> Delivered: 6-digit PIN handshake verified at doorstep
    
    Delivered --> Settled: Escrow HOLD captured to Restaurant
    Cancelled --> Refunded: Escrow HOLD released to Customer
    
    Settled --> [*]
    Refunded --> [*]
```

---

### 2.3 Platform-Specific Model (PSM): Relational, PostGIS & Partitioned Schema

The PSM translates the platform-independent concepts into optimized physical tables, spatial geometries, indexes, and range partitions:

```mermaid
erDiagram
    USERS ||--o{ ORDERS : places
    USERS ||--|| LEDGER_ACCOUNTS : owns
    LEDGER_ACCOUNTS ||--o{ JOURNAL_ENTRIES : logs
    RESTAURANTS ||--o{ DISHES : offers
    RESTAURANTS ||--o{ VIDEO_REELS : publishes
    RESTAURANTS ||--o{ ORDERS : fulfills
    ORDERS ||--|{ ORDER_ITEMS : contains
    ORDERS ||--|| OUTBOX_EVENTS : emits

    RESTAURANTS {
        uuid id PK "uuid_generate_v4()"
        varchar name "NOT NULL"
        text description
        varchar phone
        geography location "geography(Point, 4326)"
        geography delivery_zone "geography(Polygon, 4326)"
        bigint h3_res8 "NOT NULL (Spatial Index)"
        varchar city_id "NOT NULL"
        boolean is_active "NOT NULL DEFAULT true"
        boolean is_accepting_orders "NOT NULL DEFAULT true"
        int average_prep_time_minutes "DEFAULT 20"
        numeric rating "NUMERIC(2, 1) DEFAULT 5.0"
        timestamptz created_at "DEFAULT NOW()"
        timestamptz updated_at "DEFAULT NOW()"
    }

    DISHES {
        uuid id PK "uuid_generate_v4()"
        uuid restaurant_id FK "REFERENCES restaurants(id)"
        varchar name "NOT NULL"
        text description
        numeric price "NUMERIC(10, 2) CHECK (price >= 0)"
        varchar cuisine_type
        boolean is_available "NOT NULL DEFAULT true"
        timestamptz created_at "DEFAULT NOW()"
        timestamptz updated_at "DEFAULT NOW()"
    }

    ORDERS {
        uuid id PK "uuid_generate_v4()"
        uuid user_id "NOT NULL"
        uuid restaurant_id "NOT NULL"
        uuid courier_id "NULLABLE"
        varchar status "NOT NULL"
        numeric subtotal "NUMERIC(10, 2)"
        numeric delivery_fee "NUMERIC(10, 2)"
        numeric tax "NUMERIC(10, 2)"
        numeric total_amount "NUMERIC(10, 2)"
        geography dropoff_location "geography(Point, 4326)"
        varchar dropoff_building "VARCHAR(128) NOT NULL"
        varchar dropoff_floor "VARCHAR(32)"
        varchar dropoff_room "VARCHAR(32)"
        varchar dropoff_gate_code "VARCHAR(32)"
        text dropoff_notes
        varchar verification_pin "VARCHAR(6) NOT NULL"
        timestamptz dispatch_lease_expires_at "NULLABLE"
        uuid idempotency_key "UNIQUE"
        bigint version "NOT NULL DEFAULT 0 (@Version)"
        timestamptz created_at "DEFAULT NOW()"
        timestamptz updated_at "DEFAULT NOW()"
    }

    ORDER_ITEMS {
        uuid id PK "uuid_generate_v4()"
        uuid order_id FK "REFERENCES orders(id)"
        uuid dish_id "NOT NULL"
        varchar dish_name "NOT NULL"
        numeric unit_price "NUMERIC(10, 2)"
        int quantity "CHECK (quantity > 0)"
        text customization_notes
        timestamptz created_at "DEFAULT NOW()"
    }

    OUTBOX_EVENTS {
        uuid id PK "NOT NULL"
        timestamptz created_at PK "PARTITION BY RANGE"
        varchar aggregate_type "NOT NULL"
        uuid aggregate_id "NOT NULL"
        varchar event_type "NOT NULL"
        jsonb payload "NOT NULL"
        jsonb tracing_headers
        boolean processed "NOT NULL DEFAULT false"
    }

    LEDGER_ACCOUNTS {
        uuid id PK "uuid_generate_v4()"
        uuid user_id UK "NOT NULL"
        numeric balance "CHECK (balance >= 0)"
        numeric held_balance "CHECK (held_balance >= 0)"
        varchar currency "DEFAULT 'USD'"
        bigint version "NOT NULL DEFAULT 0"
        timestamptz created_at "DEFAULT NOW()"
    }

    JOURNAL_ENTRIES {
        uuid id PK "uuid_generate_v4()"
        uuid order_id "NOT NULL"
        uuid source_account_id FK "REFERENCES ledger_accounts(id)"
        uuid destination_account_id FK "REFERENCES ledger_accounts(id)"
        numeric amount "CHECK (amount > 0)"
        varchar entry_type "HOLD, CAPTURE, REFUND"
        varchar status "SUCCESS, FAILED"
        uuid idempotency_key "UNIQUE"
        timestamptz created_at "DEFAULT NOW()"
    }
```

---

## 3. High-Fidelity Sequence Interactions

### 3.1 Order Checkout, Spatial Geofence Verification & Transactional Outbox

```mermaid
sequenceDiagram
    autonumber
    actor Customer as 👤 Customer
    participant Gateway as 🚪 Ingress Gateway (:8080)
    participant Catalog as 🍽️ Catalog Service
    participant OrderSvc as 📦 Order Service
    participant Database as 🗄️ PostgreSQL (PostGIS 16)
    participant Ledger as 💳 Payment Ledger

    Customer->>Gateway: POST /api/v1/orders (dropoff: lat, lng, building, floor, room)
    Gateway->>OrderSvc: Forward Checkout Request
    
    OrderSvc->>Catalog: GET /api/v1/catalog/restaurants/{id}/coverage?lat=...&lng=...
    Catalog->>Database: SELECT ST_Covers(delivery_zone, ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography)
    Database-->>Catalog: Return TRUE (native WGS 84 ellipsoid evaluation)
    Catalog-->>OrderSvc: 200 OK { covered: true, distanceMeters: 850.4 }

    OrderSvc->>Ledger: POST /v1/payments/hold (userId, amount: $38.45, orderId)
    Ledger->>Database: UPDATE ledger_accounts SET held_balance = held_balance + 38.45
    Database-->>Ledger: 1 row affected
    Ledger-->>OrderSvc: 200 OK (Transaction HOLD success)

    critical Database Atomic Commit
        OrderSvc->>Database: INSERT INTO orders (..., dropoff_location, building, floor, room, PIN)
        OrderSvc->>Database: INSERT INTO outbox_events (..., event_type: 'OrderPlaced', partition: today)
    end
    Database-->>OrderSvc: Commit Successful
    OrderSvc-->>Gateway: 201 Created { orderId, status: "PLACED" }
    Gateway-->>Customer: 201 Created (Order Confirmed)
```

---

### 3.2 Targeted 1-to-1 Dispatch Lease State Progression

```mermaid
sequenceDiagram
    autonumber
    participant OrderSvc as 📦 Order Service
    participant Database as 🗄️ PostgreSQL
    participant Valhalla as 🗺️ Valhalla Routing Engine
    participant CourierA as 🛵 Courier A (Optimal Candidate)
    participant CourierB as 🛵 Courier B (Secondary Candidate)

    OrderSvc->>Valhalla: GET /sources_to_targets (H3 Candidate Couriers -> Restaurant)
    Valhalla-->>OrderSvc: Return Ranked Candidates (Courier A: 3.2 min, Courier B: 7.1 min)

    Note over OrderSvc,Database: Issue 30-Second Targeted Exclusive Lease
    OrderSvc->>Database: UPDATE orders SET dispatch_lease_expires_at = NOW() + INTERVAL '30 seconds'<br/>WHERE id = :orderId AND courier_id IS NULL
    Database-->>OrderSvc: 1 row affected

    OrderSvc->>CourierA: Push 30s Lease Offer (Order #123, Pay: $8.50, ETA: 3.2 min)
    
    alt Courier A Accepts within 30s
        CourierA->>OrderSvc: POST /api/v1/orders/{id}/claim (courierId: A)
        OrderSvc->>Database: UPDATE orders SET courier_id = 'A', status = 'DISPATCHED', version = version + 1<br/>WHERE id = :id AND courier_id IS NULL AND dispatch_lease_expires_at > NOW()
        Database-->>OrderSvc: 1 row affected (Claim Succeeded)
        OrderSvc-->>CourierA: 200 OK (Dispatched)
    else Lease Expires (30s Timeout)
        Note over OrderSvc: 30s elapsed without acceptance
        OrderSvc->>Database: UPDATE orders SET dispatch_lease_expires_at = NOW() + INTERVAL '30 seconds'<br/>WHERE id = :orderId AND courier_id IS NULL
        OrderSvc->>CourierB: Fallback: Push 30s Lease Offer to Courier B
    end
```

---

### 3.3 Zero-WAL Outbox Partition Pruning via `DROP TABLE`

```mermaid
sequenceDiagram
    autonumber
    participant Scheduler as ⏰ Spring Outbox Scheduler (02:00 UTC)
    participant PartService as 🧹 Outbox Partition Service
    participant Database as 🗄️ PostgreSQL Catalog/Orders DB
    participant WAL as 📝 PostgreSQL WAL Engine

    Scheduler->>PartService: scheduledPartitionPruning()
    PartService->>Database: SELECT c.relname FROM pg_inherits ... WHERE parent = 'outbox_events'
    Database-->>PartService: [outbox_events_y2026m10d10, outbox_events_y2026m10d21, outbox_events_default]

    Note over PartService: Evaluate cutoff: NOW() - 7 days (2026-10-14)
    Note over PartService: Partition outbox_events_y2026m10d10 is 11 days old (< cutoff)

    PartService->>Database: DROP TABLE IF EXISTS outbox_events_y2026m10d10;
    Database->>WAL: Unlink filesystem segment (zero dead-tuple churn)
    WAL-->>Database: 0 dead tuples generated, instant disk space reclaimed
    Database-->>PartService: DROP TABLE successful

    PartService->>Database: SELECT COALESCE(SUM(n_dead_tup), 0) FROM pg_stat_user_tables WHERE relname LIKE 'outbox_events%'
    Database-->>PartService: 0
    PartService-->>Scheduler: Pruning completed: 1 partition dropped with 0 WAL overhead
```

---

## 4. Kubernetes Single-Port Ingress Topology

Only **host port 8080** is bound. All internal services communicate over the Kubernetes CNI pod network:

```
                          [ Host Workstation Browser / Test Harness ]
                                              │
                                              │ HTTP / WebSockets (Port 8080 Only)
                                              ▼
        ┌───────────────────────────────────────────────────────────────────────────┐
        │             Kubernetes Ingress Controller (Host Port 8080)                │
        └─────────────────────────────────────┬─────────────────────────────────────┘
                                              │
   ┌──────────────────────────────────────────┴──────────────────────────────────────────┐
   │                       Kubernetes CNI Pod Network (Pod CIDR)                         │
   │                                                                                     │
   │  ├──> /api/v1/catalog/*     ──> catalog-service.delivery-prod.svc:8080              │
   │  ├──> /api/v1/orders/*      ──> order-service.delivery-prod.svc:8080                │
   │  ├──> /api/v1/payments/*    ──> local-payment-service.delivery-prod.svc:8080        │
   │  ├──> /api/v1/sms/*         ──> local-sms-gateway.delivery-prod.svc:8080            │
   │  ├──> /admin/sms-inbox      ──> local-sms-gateway.delivery-prod.svc:8080            │
   │  └──> /push/*               ──> ntfy.delivery-prod.svc:80 (WebSockets/SSE)         │
   │                                                                                     │
   │  Datastores (Internal ClusterIP):                                                   │
   │  ├── postgres.delivery-prod.svc:5432 (PostGIS 16 Hardened CDC)                      │
   │  ├── redis.delivery-prod.svc:6379                                                   │
   │  └── kafka.delivery-prod.svc:9092                                                   │
   └─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Workstation Resource Budget & Mathematical Safety Proof

All pods declare strict requests and limits matching Section 4.2 of `plan.md`:

| Component | Pod Name | CPU Request | CPU Limit | RAM Request | RAM Limit | Quality of Service |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Datastores** | `postgres` (PostGIS 16) | `500m` | `2000m` | `1536Mi` | `3072Mi` | Burstable |
| **Core Service** | `catalog-service` | `200m` | `1000m` | `384Mi` | `768Mi` | Burstable |
| **Core Service** | `order-service` | `300m` | `1500m` | `512Mi` | `1024Mi` | Burstable |
| **Local Boundary**| `local-payment-service` | `100m` | `500m` | `256Mi` | `384Mi` | Burstable |
| **Local Boundary**| `local-sms-gateway` | `50m` | `200m` | `64Mi` | `128Mi` | Burstable |
| **Local Boundary**| `ntfy` | `50m` | `200m` | `64Mi` | `128Mi` | Burstable |
| **Ingress** | `ingress-nginx` | `100m` | `500m` | `128Mi` | `256Mi` | Burstable |

### Mathematical Proof of Workstation Stability
1. **Guaranteed Scheduling (`Requests = 2.9 GB RAM / 1.3 vCPUs`):** A standard 16GB–32GB developer machine satisfies requests instantaneously with zero Kubelet node eviction pressure.
2. **Hard Burst Ceiling (`Limits = 6.7 GB RAM / 6.7 vCPUs`):** During maximum load, cgroup limits clamp container resource consumption, reserving 9GB to 25GB of host RAM for the OS, IDE, and developer tools. Kernel OOM panics are prevented.
