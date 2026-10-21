-- Flyway Migration: V1__init_postgis_and_catalog.sql
-- Database: catalog_db (Owned by catalog-service)

-- 1. Enable Required Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "postgis";
CREATE EXTENSION IF NOT EXISTS "btree_gist";

-- 2. Create Restaurants Table
CREATE TABLE IF NOT EXISTS restaurants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    phone VARCHAR(32) NOT NULL,
    
    -- Spatial: Point in WGS84 for exact GPS coordinate
    location geography(Point, 4326) NOT NULL,
    
    -- Spatial: Delivery polygon boundary in Web Mercator (EPSG:3857) for fast planar containment.
    -- NOTE: Coordinates from geography(Point, 4326) must be explicitly projected via ST_Transform(point::geometry, 3857).
    -- Query with ST_Covers (not ST_Contains) to accept customers located on polygon boundaries.
    delivery_zone geometry(Polygon, 3857) NOT NULL,
    
    -- Uber H3 Index (Resolution 8: documented average area ~0.737 km², average edge ~461m) for O(1) coarse topological binning
    h3_res8 BIGINT NOT NULL,
    
    city_id VARCHAR(64) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    is_accepting_orders BOOLEAN NOT NULL DEFAULT true,
    average_prep_time_minutes INT NOT NULL DEFAULT 20,
    rating NUMERIC(2, 1) NOT NULL DEFAULT 5.0,
    
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. Create Dishes / Catalog Items Table
CREATE TABLE IF NOT EXISTS dishes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price NUMERIC(10, 2) NOT NULL,
    cuisine_type VARCHAR(64) NOT NULL,
    dietary_flags VARCHAR(64)[], -- e.g., {'VEGAN', 'GLUTEN_FREE'}
    is_available BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 4. Create Video Reels Table (Short-form Food Discovery)
CREATE TABLE IF NOT EXISTS video_reels (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE CASCADE,
    dish_id UUID REFERENCES dishes(id) ON DELETE SET NULL,
    
    -- Media URLs served through Edge CDN
    master_m3u8_url TEXT NOT NULL,
    thumbnail_url TEXT NOT NULL,
    preview_animation_url TEXT, -- 3s WebP / AVIF loop for zero-latency card hover
    
    duration_seconds INT NOT NULL CHECK (duration_seconds BETWEEN 5 AND 60),
    aspect_ratio VARCHAR(16) NOT NULL DEFAULT '9:16',
    
    -- Spatial index inherited from restaurant for geo-fenced discovery
    h3_res8 BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('PENDING_TRANSCODE', 'ACTIVE', 'INACTIVE')),
    
    views_count BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 5. Spatial & Composite Indexes (Tuned for High Concurrency)
-- GiST index on location for distance searches
CREATE INDEX IF NOT EXISTS idx_restaurants_location_gist 
    ON restaurants USING GIST (location);

-- GiST index on delivery_zone polygon for ST_Covers / ST_Intersects queries
CREATE INDEX IF NOT EXISTS idx_restaurants_delivery_zone_gist 
    ON restaurants USING GIST (delivery_zone);

-- Composite btree_gist index for location + business status + city
CREATE INDEX IF NOT EXISTS idx_restaurants_spatial_active_composite 
    ON restaurants USING GIST (location, is_active, city_id);

-- Standard B-Tree index on H3 cell for fast discovery in feed service
CREATE INDEX IF NOT EXISTS idx_restaurants_h3_res8 
    ON restaurants (h3_res8) WHERE is_active = true;

CREATE INDEX IF NOT EXISTS idx_dishes_restaurant 
    ON dishes (restaurant_id, is_available);

CREATE INDEX IF NOT EXISTS idx_video_reels_h3_status 
    ON video_reels (h3_res8, status);

-- 6. Updated_at Automatic Trigger
CREATE OR REPLACE FUNCTION update_timestamp_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_restaurants_updated_at
BEFORE UPDATE ON restaurants
FOR EACH ROW EXECUTE FUNCTION update_timestamp_column();

CREATE TRIGGER trg_dishes_updated_at
BEFORE UPDATE ON dishes
FOR EACH ROW EXECUTE FUNCTION update_timestamp_column();

CREATE TRIGGER trg_video_reels_updated_at
BEFORE UPDATE ON video_reels
FOR EACH ROW EXECUTE FUNCTION update_timestamp_column();
