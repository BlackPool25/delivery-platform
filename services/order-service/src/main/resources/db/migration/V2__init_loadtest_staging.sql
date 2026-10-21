-- Flyway Migration: V2__init_loadtest_staging.sql
-- Database: orders_db / delivery_db (Order Service)
-- Synthetic Test Data Isolation & Fast Teardown staging table for k6 Workstation Load Testing.

CREATE TABLE IF NOT EXISTS orders_loadtest_staging (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    restaurant_id UUID NOT NULL,
    courier_id UUID,
    status VARCHAR(32) NOT NULL,
    
    subtotal NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    delivery_fee NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    tax NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    total_amount NUMERIC(10, 2) NOT NULL,
    
    dropoff_location GEOGRAPHY(Point, 4326) NOT NULL,
    dropoff_building VARCHAR(128) NOT NULL,
    dropoff_floor VARCHAR(32),
    dropoff_room VARCHAR(32),
    dropoff_gate_code VARCHAR(32),
    dropoff_notes TEXT,
    
    verification_pin VARCHAR(6) NOT NULL,
    dispatch_lease_expires_at TIMESTAMP WITH TIME ZONE,
    
    city_id VARCHAR(64) NOT NULL DEFAULT 'default-city',
    idempotency_key UUID,
    cancellation_reason TEXT,
    
    version BIGINT NOT NULL DEFAULT 0,
    test_tenant VARCHAR(64) NOT NULL DEFAULT 'LOADTEST_LOCAL',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_loadtest_orders_status ON orders_loadtest_staging (status);
CREATE INDEX IF NOT EXISTS idx_loadtest_orders_tenant ON orders_loadtest_staging (test_tenant);
