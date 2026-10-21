-- Flyway Migration: V1__init_orders_and_outbox.sql
-- Database: orders_db (Owned by order-service)

-- 1. Enable Required Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "postgis";

-- 2. Declarative Range Partitioned Orders Table (Partitioned by Month)
CREATE TABLE IF NOT EXISTS orders (
    id UUID NOT NULL DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    restaurant_id UUID NOT NULL,
    courier_id UUID,
    
    status VARCHAR(32) NOT NULL,
    -- Status Enum: PLACED, ACCEPTED, PREPARING, READY_FOR_PICKUP, COURIER_ASSIGNED, OUT_FOR_DELIVERY, DELIVERED, CANCELLED
    
    subtotal NUMERIC(10, 2) NOT NULL,
    delivery_fee NUMERIC(10, 2) NOT NULL,
    tax NUMERIC(10, 2) NOT NULL,
    total_amount NUMERIC(10, 2) NOT NULL,
    
    -- Spatial: Exact Dropoff GPS Coordinate
    dropoff_location geography(Point, 4326) NOT NULL,
    delivery_instructions TEXT,
    
    city_id VARCHAR(64) NOT NULL,
    
    -- MVCC Optimistic Locking Version Field (Spring Data JPA @Version)
    version BIGINT NOT NULL DEFAULT 0,
    
    idempotency_key UUID UNIQUE,
    cancellation_reason TEXT,
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- 3. Initial Partitions (Rolling Monthly Storage)
CREATE TABLE IF NOT EXISTS orders_2026_10 PARTITION OF orders
    FOR VALUES FROM ('2026-10-01 00:00:00+00') TO ('2026-11-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS orders_2026_11 PARTITION OF orders
    FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');

CREATE TABLE IF NOT EXISTS orders_2026_12 PARTITION OF orders
    FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');

-- Default fallback partition to prevent runtime insert errors
CREATE TABLE IF NOT EXISTS orders_default PARTITION OF orders DEFAULT;

-- 4. Order Line Items Table
CREATE TABLE IF NOT EXISTS order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL,
    order_created_at TIMESTAMPTZ NOT NULL,
    dish_id UUID NOT NULL,
    dish_name VARCHAR(255) NOT NULL,
    unit_price NUMERIC(10, 2) NOT NULL,
    quantity INT NOT NULL CHECK (quantity > 0),
    customization_notes TEXT,
    
    FOREIGN KEY (order_id, order_created_at) REFERENCES orders(id, created_at) ON DELETE CASCADE
);

-- 5. Transactional Outbox Table (Debezium CDC to Kafka)
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(64) NOT NULL, -- e.g., 'ORDER'
    aggregate_id VARCHAR(255) NOT NULL,  -- e.g., order_id
    event_type VARCHAR(64) NOT NULL,      -- e.g., 'OrderPlaced', 'OrderAccepted'
    payload JSONB NOT NULL,
    tracing_headers JSONB,               -- W3C TraceContext (traceparent)
    processed BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 6. Indexes on Partitions and Outbox
CREATE INDEX IF NOT EXISTS idx_orders_user 
    ON orders (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_orders_restaurant_status 
    ON orders (restaurant_id, status);

CREATE INDEX IF NOT EXISTS idx_orders_courier 
    ON orders (courier_id) WHERE courier_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_orders_dropoff_gist 
    ON orders USING GIST (dropoff_location);

-- Index for Outbox Poller / CDC streaming
CREATE INDEX IF NOT EXISTS idx_outbox_unprocessed 
    ON outbox_events (created_at ASC) WHERE processed = false;
