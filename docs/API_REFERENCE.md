# REST API & Gateway Reference

> **Unified Single-Port Ingress Gateway**: `http://localhost:8080`  
> *All services are accessible through standardized path-based routing.*

---

## Table of Contents
- [Catalog Service (`/api/v1/catalog/*`)](#1-catalog-service)
- [Order Service (`/api/v1/orders/*`)](#2-order-service)
- [Local Payment Ledger Service (`/api/v1/payments/*`)](#3-local-payment-ledger-service)
- [Local SMS Gateway (`/api/v1/sms/*`)](#4-local-sms-gateway)
- [Push Notification Broker (`/push/*`)](#5-push-notification-broker-ntfy)
- [Real-Time Telemetry & Tracking (`/api/v1/tracking/*` & `/ws/tracking`)](#6-real-time-telemetry--tracking)
- [Offline Vector Maps (Martin PMTiles) (`/tiles/*`)](#7-offline-vector-map-tiles-martin-pmtiles)
- [Valhalla Road Routing Engine (`/routing/*`)](#8-valhalla-dynamic-road-routing-engine)
- [Virtual Courier Simulator (`/api/v1/simulator/*`)](#9-virtual-courier-simulator)
- [Digital Twin MapLibre GL Web UI (`/`)](#10-digital-twin-maplibre-gl-web-client)
- [High-Frequency gRPC Telemetry Service (`localhost:9090`)](#11-high-frequency-grpc-telemetry-service)

---

## 1. Catalog Service

Provides restaurant onboarding, Geodesic WGS 84 ellipsoidal delivery boundary containment (`ST_Covers`), menu management, and location-gated video reels.

### 1.1 Register Restaurant
* **Endpoint:** `POST /api/v1/catalog/restaurants`
* **Headers:** `Content-Type: application/json`

```bash
curl -X POST http://localhost:8080/api/v1/catalog/restaurants \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Mission Taqueria",
    "description": "Artisanal street food",
    "phone": "+14155551234",
    "latitude": 37.7749,
    "longitude": -122.4194,
    "deliveryZoneWkt": "POLYGON((-122.4300 37.7600, -122.4000 37.7600, -122.4000 37.7900, -122.4300 37.7900, -122.4300 37.7600))",
    "h3Res8": 599814421671936000,
    "cityId": "san-francisco",
    "averagePrepTimeMinutes": 15
  }'
```

#### Response (`201 Created`):
```json
{
  "id": "7b8dbba2-e22e-4b68-98e3-fcf16f0d90d1",
  "name": "Mission Taqueria",
  "description": "Artisanal street food",
  "phone": "+14155551234",
  "latitude": 37.7749,
  "longitude": -122.4194,
  "deliveryZoneWkt": "POLYGON ((-122.43 37.76, -122.4 37.76, -122.4 37.79, -122.43 37.79, -122.43 37.76))",
  "h3Res8": 599814421671936000,
  "cityId": "san-francisco",
  "isActive": true,
  "isAcceptingOrders": true,
  "averagePrepTimeMinutes": 15,
  "rating": 5.0,
  "createdAt": "2026-10-21T07:42:14.152Z",
  "updatedAt": "2026-10-21T07:42:14.152Z"
}
```

---

### 1.2 Geodesic Boundary Coverage Check
Evaluates `ST_Covers(r.delivery_zone, :dropoff::geography)` natively on the WGS 84 ellipsoid without planar projection distortion.

* **Endpoint:** `GET /api/v1/catalog/restaurants/{id}/coverage?lat={latitude}&lng={longitude}`

```bash
curl -s "http://localhost:8080/api/v1/catalog/restaurants/7b8dbba2-e22e-4b68-98e3-fcf16f0d90d1/coverage?lat=37.7750&lng=-122.4150"
```

#### Response (`200 OK`):
```json
{
  "restaurantId": "7b8dbba2-e22e-4b68-98e3-fcf16f0d90d1",
  "restaurantName": "Mission Taqueria",
  "covered": true,
  "latitude": 37.775,
  "longitude": -122.415,
  "distanceMeters": 402.18
}
```

---

### 1.3 Add Menu Item (Dish)
* **Endpoint:** `POST /api/v1/catalog/restaurants/{restaurantId}/dishes`

```bash
curl -X POST http://localhost:8080/api/v1/catalog/restaurants/7b8dbba2-e22e-4b68-98e3-fcf16f0d90d1/dishes \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Mission Burrito",
    "description": "Carne asada, black beans, guacamole, pico de gallo",
    "price": 14.50,
    "cuisineType": "MEXICAN"
  }'
```

#### Response (`201 Created`):
```json
{
  "id": "e305e548-bc6b-4e89-8b83-b9be92e104e7",
  "restaurantId": "7b8dbba2-e22e-4b68-98e3-fcf16f0d90d1",
  "name": "Mission Burrito",
  "description": "Carne asada, black beans, guacamole, pico de gallo",
  "price": 14.50,
  "cuisineType": "MEXICAN",
  "isAvailable": true,
  "createdAt": "2026-10-21T07:42:15.000Z",
  "updatedAt": "2026-10-21T07:42:15.000Z"
}
```

---

## 2. Order Service

Handles idempotent checkout, building/room hyper-local coordinates, optimistic concurrency control (`@Version`), and daily range-partitioned CDC outbox emission.

### 2.1 Submit Order Checkout
* **Endpoint:** `POST /api/v1/orders`
* **Headers:** `Content-Type: application/json`

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "11111111-1111-1111-1111-111111111111",
    "restaurantId": "7b8dbba2-e22e-4b68-98e3-fcf16f0d90d1",
    "dropoffLatitude": 37.7891,
    "dropoffLongitude": -122.4014,
    "dropoffBuilding": "Salesforce Tower",
    "dropoffFloor": "Floor 42",
    "dropoffRoom": "Suite 4208",
    "dropoffGateCode": "4208#",
    "dropoffNotes": "Deliver to front reception",
    "verificationPin": "938104",
    "idempotencyKey": "a823b5d1-9238-4e89-9182-38b9192da104",
    "items": [
      {
        "dishId": "e305e548-bc6b-4e89-8b83-b9be92e104e7",
        "dishName": "Mission Burrito",
        "unitPrice": 14.50,
        "quantity": 2,
        "customizationNotes": "No onions, extra salsa"
      }
    ]
  }'
```

#### Response (`201 Created`):
```json
{
  "id": "a962d456-aee1-423d-bbdb-726c51d582d2",
  "userId": "11111111-1111-1111-1111-111111111111",
  "restaurantId": "7b8dbba2-e22e-4b68-98e3-fcf16f0d90d1",
  "courierId": null,
  "status": "PLACED",
  "subtotal": 29.00,
  "deliveryFee": 3.99,
  "tax": 2.47,
  "totalAmount": 35.46,
  "dropoffLatitude": 37.7891,
  "dropoffLongitude": -122.4014,
  "dropoffBuilding": "Salesforce Tower",
  "dropoffFloor": "Floor 42",
  "dropoffRoom": "Suite 4208",
  "dropoffGateCode": "4208#",
  "dropoffNotes": "Deliver to front reception",
  "verificationPin": "938104",
  "dispatchLeaseExpiresAt": null,
  "cityId": "default-city",
  "idempotencyKey": "a823b5d1-9238-4e89-9182-38b9192da104",
  "version": 0,
  "items": [
    {
      "id": "2d1f114e-b541-4829-9e8a-19192b0051e2",
      "dishId": "e305e548-bc6b-4e89-8b83-b9be92e104e7",
      "dishName": "Mission Burrito",
      "unitPrice": 14.50,
      "quantity": 2,
      "customizationNotes": "No onions, extra salsa",
      "createdAt": "2026-10-21T07:48:52.242Z"
    }
  ],
  "createdAt": "2026-10-21T07:48:52.242Z",
  "updatedAt": "2026-10-21T07:48:52.242Z"
}
```

---

### 2.2 Claim 30s Dispatch Offer
Atomic conditional update with server-authoritative timestamp lock (`dispatch_lease_expires_at > NOW()`).

* **Endpoint:** `POST /api/v1/orders/{id}/claim?courierId={courierId}`

```bash
curl -X POST "http://localhost:8080/api/v1/orders/a962d456-aee1-423d-bbdb-726c51d582d2/claim?courierId=33333333-3333-3333-3333-333333333333"
```
* **Status:** `200 OK` (Claimed) or `409 Conflict` (Lease expired or claimed by another driver).

---

### 2.3 Inspect Outbox Partitions & Trigger Pruning
* **Inspect Partitions:** `GET /api/v1/orders/admin/outbox/partitions`
* **Trigger Pruning:** `POST /api/v1/orders/admin/outbox/prune?retentionDays=7`

```bash
curl -s http://localhost:8080/api/v1/orders/admin/outbox/partitions
```

#### Response (`200 OK`):
```json
{
  "totalPartitions": 15,
  "activePartitions": [
    "outbox_events_default",
    "outbox_events_y2026m10d21",
    "outbox_events_y2026m10d22"
  ],
  "deadTuplesCount": 0
}
```

---

## 3. Local Payment Ledger Service

Double-entry ledger ensuring zero-cloud sovereign financial guarantees with balance check constraints (`balance >= 0`, `held_balance <= balance`).

* **Create Account:** `POST /v1/payments/accounts`
* **Get Account Balance:** `GET /v1/payments/accounts/{userId}`
* **Execute Escrow Hold:** `POST /v1/payments/hold`
* **Capture Held Funds:** `POST /v1/payments/capture`
* **Release / Refund Hold:** `POST /v1/payments/refund`

---

## 4. Local SMS Gateway

In-memory high-throughput circular buffer replacing external SMS providers.

* **Send Mock SMS:** `POST /v1/sms/send`
* **Inspect Latest SMS (Instant OTP Retrieval):** `GET /v1/sms/inspect/latest?to={phone}`
* **Inspect All Inbound/Outbound Messages:** `GET /v1/sms/inspect/all`
* **Web UI Dashboard:** `GET http://localhost:8080/admin/sms-inbox`

---

## 5. Push Notification Broker (`ntfy`)

High-performance internal notification broker for mobile apps and live tracking updates.

* **Publish Push Alert:** `POST /push/{topic}`
* **WebSocket Ingest / Streaming:** `ws://localhost:8080/push/{topic}/ws`

---

## 6. Real-Time Telemetry & Tracking

High-frequency GPS telemetry ingestion, regional Redis key sharding, and live WebSocket streaming.

### 6.1 Telemetry Ingestion
Enforces geodesic kinematic velocity constraint ($V = \frac{\Delta d}{\Delta t} < 120\text{ km/h}$).

* **Endpoint:** `POST /api/v1/tracking/couriers/{courierId}/telemetry`
* **Headers:** `Content-Type: application/json`

```bash
curl -X POST "http://localhost:8080/api/v1/tracking/couriers/c0000000-0000-0000-0000-000000000001/telemetry" \
  -H "Content-Type: application/json" \
  -d '{
    "latitude": 37.7749,
    "longitude": -122.4194,
    "timestamp": "2026-10-21T11:00:00Z",
    "speedKmh": 28.5,
    "heading": 45.0,
    "accuracy": 4.5
  }'
```

### 6.2 Live Tracking WebSocket Gateway
* **WebSocket URI:** `ws://localhost:8080/ws/tracking` (or `ws://localhost:8080/ws/tracking/{courierId}`)
* **Subscribed Message Format:**
```json
{
  "courierId": "c0000000-0000-0000-0000-000000000001",
  "latitude": 37.7749,
  "longitude": -122.4194,
  "speedKmh": 28.5,
  "headingDegrees": 45.0,
  "h3Index": "8828308281fffff",
  "status": "ONLINE"
}
```

---

## 7. Offline Vector Map Tiles (Martin PMTiles)

Serves pre-compiled offline vector tiles from `/data/tiles/city.pmtiles` using Martin on port 3000.

* **Verification Endpoint:** `GET /tiles/city/{z}/{x}/{y}` (or `/tiles/city/0/0/0`)
* **Tile Health:** `GET /tiles/health`

```bash
curl -i http://localhost:8080/tiles/city/0/0/0
```
#### Response (`200 OK`):
* `Content-Type: application/x-protobuf`
* Returns valid Mapbox Vector Tile (MVT) binary payload.

---

## 8. Valhalla Dynamic Road Routing Engine

Offline turn-by-turn road routing engine computing street vectors and ETAs across San Francisco.

### 8.1 Routing Status
* **Endpoint:** `GET /routing/status`
```bash
curl -s http://localhost:8080/routing/status
```
#### Response:
```json
{
  "status": "ready",
  "version": "3.4.0",
  "engine": "valhalla-sovereign-v3",
  "tileset": "san-francisco-osm"
}
```

### 8.2 Compute Road Route
* **Endpoint:** `POST /routing/route` or `GET /routing/route?json={...}`
```bash
curl -X POST http://localhost:8080/routing/route \
  -H "Content-Type: application/json" \
  -d '{
    "locations": [
      {"lat": 37.7749, "lon": -122.4194},
      {"lat": 37.7650, "lon": -122.4150}
    ],
    "costing": "auto"
  }'
```

---

## 9. Virtual Courier Simulator

Standalone test worker provisioning 20 virtual couriers in San Francisco dining hubs, claiming 30s dispatch offers, querying Valhalla routes, and emitting telemetry every 3 seconds.

* **Simulator Health & Status:** `GET /api/v1/simulator/status`
* **List All 20 Couriers:** `GET /api/v1/simulator/couriers`
* **Inspect Specific Courier:** `GET /api/v1/simulator/couriers/{id}`
* **Active Simulation Tasks:** `GET /api/v1/simulator/tasks`
* **Trigger Order Dispatch:** `POST /api/v1/simulator/dispatch/{orderId}`
* **Manual Simulation Tick:** `POST /api/v1/simulator/tick`
* **Trigger Doorstep Handshake:** `POST /api/v1/simulator/deliver/{orderId}`

---

## 10. Digital Twin MapLibre GL Web Client

* **URI:** `http://localhost:8080/`
* Renders offline vector tiles from `/tiles/city/{z}/{x}/{y}`.
* Connects to `/ws/tracking` to render live courier markers with heading rotation and breadcrumbs.
* Provides interactive order journey tracker from `PLACED` to `DELIVERED & SETTLED` via PIN handshake.

---

## 11. High-Frequency gRPC Telemetry Service

The tracking engine exposes a high-concurrency binary gRPC interface on dedicated port `9090` backed by Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).

* **Proto Definition:** `services/tracking-service/src/main/proto/telemetry.proto`
* **Service:** `com.delivery.tracking.grpc.CourierTelemetryService`
* **Port:** `9090` (Mapped to host port 9090 on the local cluster)

### 11.1 RPC IngestTelemetry (Unary)
Submits a single high-frequency kinematic GPS coordinate ping. Evaluates against the $\le 120\text{ km/h}$ anti-spoofing velocity filter and persists to regional Redis keys.

* **Method:** `CourierTelemetryService/IngestTelemetry`
* **Request Payload:**
```protobuf
message TelemetryPing {
  string courier_id = 1;
  double latitude = 2;
  double longitude = 3;
  int64 timestamp_epoch_ms = 4;
  double speed_kmh = 5;
  double heading = 6;
  double accuracy = 7;
}
```
* **Response Payload:**
```protobuf
message TelemetryAck {
  string courier_id = 1;
  bool accepted = 2;
  string status = 3;             // "ACCEPTED", "FLAGGED_SPOOFING", "OUT_OF_BOUNDS"
  double calculated_speed_kmh = 4;
  string message = 5;
}
```

### 11.2 RPC StreamTelemetry (Client-Streaming)
Allows high-throughput persistent multiplexed streams of kinematic telemetry points from active drivers.

* **Method:** `CourierTelemetryService/StreamTelemetry`
* **Input Stream:** `stream TelemetryPing`
* **Output Response:** `TelemetrySummary` (returns total points received, accepted count, and flagged count).

### 11.3 RPC GetCourierLocation (Unary)
Low-latency read endpoint querying courier position directly from in-memory Redis state.

* **Method:** `CourierTelemetryService/GetCourierLocation`
* **Request:** `{ "courier_id": "00000000-0000-4000-8000-000000000001" }`
* **Response:**
```protobuf
message CourierLocationResponse {
  string courier_id = 1;
  double latitude = 2;
  double longitude = 3;
  int64 timestamp_epoch_ms = 4;
  double speed_kmh = 5;
  double heading = 6;
  bool is_online = 7;
  bool found = 8;
}
```

### 11.4 Benchmarking with k6
```bash
# Execute native gRPC load test using k6
k6 run --vus 10 --duration 15s load-tests/grpc-benchmark.js
```
