# REST API & Gateway Reference

> **Unified Single-Port Ingress Gateway**: `http://localhost:8080`  
> *All services are accessible through standardized path-based routing.*

---

## Table of Contents
- [Catalog Service (`/api/v1/catalog/*`)](#1-catalog-service)
- [Order Service (`/api/v1/orders/*`)](#2-order-service)
- [Local Payment Ledger Service (`/api/v1/payments/*`)](#3-local-payment-ledger-service)
- [Local SMS Gateway (`/api/v1/sms/*`)](#4-local-sms-gateway)
- [Push Notification Broker (`/push/*`)](#5-push-notification-broker)

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
