-- Flyway Migration: V1__init_catalog.sql
-- Database: catalog_db / delivery_db (Catalog Service)
-- Architecture Constraint: Spatial boundaries MUST use geography(Polygon, 4326) and geography(Point, 4326).
-- EPSG:3857 is strictly prohibited to eliminate projection distortions.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "postgis";
CREATE EXTENSION IF NOT EXISTS "btree_gist";

-- 1. Restaurants Table
CREATE TABLE IF NOT EXISTS restaurants (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    phone VARCHAR(32),
    
    -- Exact GPS Coordinate on WGS 84 Ellipsoid (EPSG:4326)
    location GEOGRAPHY(Point, 4326) NOT NULL,
    
    -- Geodesic Delivery Polygon on WGS 84 Ellipsoid (EPSG:4326)
    -- ST_Covers evaluates natively on the spheroid without planar distortion
    delivery_zone GEOGRAPHY(Polygon, 4326) NOT NULL,
    
    -- Uber H3 Resolution 8 Spatial Index (~0.737 km² per cell) for O(1) coarse binning
    h3_res8 BIGINT NOT NULL,
    
    city_id VARCHAR(64) NOT NULL DEFAULT 'default-city',
    is_active BOOLEAN NOT NULL DEFAULT true,
    is_accepting_orders BOOLEAN NOT NULL DEFAULT true,
    average_prep_time_minutes INT NOT NULL DEFAULT 20,
    rating NUMERIC(2, 1) NOT NULL DEFAULT 5.0,
    
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Spatial GiST Indexes for native Geodesic containment queries
CREATE INDEX IF NOT EXISTS idx_restaurants_location ON restaurants USING GIST (location);
CREATE INDEX IF NOT EXISTS idx_restaurants_delivery_zone ON restaurants USING GIST (delivery_zone);
CREATE INDEX IF NOT EXISTS idx_restaurants_h3 ON restaurants (h3_res8);
CREATE INDEX IF NOT EXISTS idx_restaurants_active ON restaurants (is_active, is_accepting_orders);

-- 2. Dishes Table
CREATE TABLE IF NOT EXISTS dishes (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    cuisine_type VARCHAR(64),
    is_available BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dishes_restaurant ON dishes (restaurant_id);
CREATE INDEX IF NOT EXISTS idx_dishes_available ON dishes (restaurant_id, is_available);

-- 3. Video Reels Table
CREATE TABLE IF NOT EXISTS video_reels (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
    dish_id UUID REFERENCES dishes(id) ON DELETE SET NULL,
    master_m3u8_url VARCHAR(512),
    thumbnail_url VARCHAR(512),
    h3_res8 BIGINT NOT NULL,
    duration_sec INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_video_reels_restaurant ON video_reels (restaurant_id);
CREATE INDEX IF NOT EXISTS idx_video_reels_h3 ON video_reels (h3_res8);
