-- Flyway Migration: V1__init_orders_and_outbox.sql
-- Database: orders_db / delivery_db (Order Service)
-- Architecture Constraint: dropoff_location MUST use geography(Point, 4326).
-- Building, floor, room number, and gate code must be explicitly captured.
-- outbox_events MUST be declaratively partitioned by RANGE (created_at) for zero-WAL DROP TABLE pruning.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "postgis";

-- 1. Orders Table
CREATE TABLE IF NOT EXISTS orders (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    restaurant_id UUID NOT NULL,
    courier_id UUID,
    status VARCHAR(32) NOT NULL,
    
    subtotal NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    delivery_fee NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    tax NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    total_amount NUMERIC(10, 2) NOT NULL,
    
    -- Spatial: Dropoff Location on WGS 84 Ellipsoid (EPSG:4326)
    dropoff_location GEOGRAPHY(Point, 4326) NOT NULL,
    
    -- Hyper-local delivery destination coordinates (Phase 2 Deliverable 3)
    dropoff_building VARCHAR(128) NOT NULL,
    dropoff_floor VARCHAR(32),
    dropoff_room VARCHAR(32),
    dropoff_gate_code VARCHAR(32),
    dropoff_notes TEXT,
    
    -- Handshake delivery PIN
    verification_pin VARCHAR(6) NOT NULL,
    
    -- 1-to-1 Targeted Lease Timestamp (Dispatch State Machine)
    dispatch_lease_expires_at TIMESTAMP WITH TIME ZONE,
    
    city_id VARCHAR(64) NOT NULL DEFAULT 'default-city',
    idempotency_key UUID UNIQUE,
    cancellation_reason TEXT,
    
    -- Optimistic Locking Version (@Version)
    version BIGINT NOT NULL DEFAULT 0,
    
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_orders_status ON orders (status);
CREATE INDEX IF NOT EXISTS idx_orders_user ON orders (user_id);
CREATE INDEX IF NOT EXISTS idx_orders_courier ON orders (courier_id);
CREATE INDEX IF NOT EXISTS idx_orders_restaurant ON orders (restaurant_id);
CREATE INDEX IF NOT EXISTS idx_orders_dropoff ON orders USING GIST (dropoff_location);

-- 2. Order Line Items Table
CREATE TABLE IF NOT EXISTS order_items (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    dish_id UUID NOT NULL,
    dish_name VARCHAR(255) NOT NULL,
    unit_price NUMERIC(10, 2) NOT NULL CHECK (unit_price >= 0),
    quantity INT NOT NULL CHECK (quantity > 0),
    customization_notes TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items (order_id);

-- 3. Declarative Partitioned Outbox Table (Partitioned by Day)
CREATE TABLE IF NOT EXISTS outbox_events (
    id UUID NOT NULL DEFAULT uuid_generate_v4(),
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    tracing_headers JSONB,
    processed BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Fallback Default Partition to prevent insertion failures on unpartitioned edge dates
CREATE TABLE IF NOT EXISTS outbox_events_default PARTITION OF outbox_events DEFAULT;

CREATE INDEX IF NOT EXISTS idx_outbox_events_unprocessed ON outbox_events (created_at ASC) WHERE processed = false;
CREATE INDEX IF NOT EXISTS idx_outbox_events_aggregate ON outbox_events (aggregate_type, aggregate_id);
