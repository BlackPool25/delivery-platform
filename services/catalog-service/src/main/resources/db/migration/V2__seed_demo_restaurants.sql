-- Flyway Migration: V2__seed_demo_restaurants.sql
-- Seeds default San Francisco dining destinations with geodesic EPSG:4326 geometries

INSERT INTO restaurants (id, name, description, phone, location, delivery_zone, h3_res8, city_id, is_active, is_accepting_orders, average_prep_time_minutes, rating)
VALUES
  (
    'a1111111-1111-1111-1111-111111111111',
    'Mission Taqueria',
    'Authentic artisanal street tacos and burritos in the heart of SF Mission',
    '+14155551234',
    ST_GeogFromText('SRID=4326;POINT(-122.4194 37.7749)'),
    ST_GeogFromText('SRID=4326;POLYGON((-122.4350 37.7550, -122.4050 37.7550, -122.4050 37.7950, -122.4350 37.7950, -122.4350 37.7550))'),
    599814421671936000,
    'san-francisco',
    true,
    true,
    15,
    4.9
  ),
  (
    'a2222222-2222-2222-2222-222222222222',
    'Valencia Ramen & Izakaya',
    'Handmade noodles and 18-hour rich tonkotsu broth on the Valencia corridor',
    '+14155555678',
    ST_GeogFromText('SRID=4326;POINT(-122.4215 37.7610)'),
    ST_GeogFromText('SRID=4326;POLYGON((-122.4350 37.7500, -122.4050 37.7500, -122.4050 37.7850, -122.4350 37.7850, -122.4350 37.7500))'),
    599814421671936000,
    'san-francisco',
    true,
    true,
    20,
    4.8
  ),
  (
    'a3333333-3333-3333-3333-333333333333',
    'Tartine Bakery',
    'Iconic rustic sourdough loaves, flaky croissants, and morning buns',
    '+14155559012',
    ST_GeogFromText('SRID=4326;POINT(-122.4241 37.7614)'),
    ST_GeogFromText('SRID=4326;POLYGON((-122.4400 37.7500, -122.4000 37.7500, -122.4000 37.7850, -122.4400 37.7850, -122.4400 37.7500))'),
    599814421671936000,
    'san-francisco',
    true,
    true,
    10,
    5.0
  )
ON CONFLICT (id) DO NOTHING;

-- Dishes for Mission Taqueria
INSERT INTO dishes (id, restaurant_id, name, description, price, cuisine_type, is_available)
VALUES
  ('b1111111-1111-1111-1111-111111111101', 'a1111111-1111-1111-1111-111111111111', 'Birria Street Tacos (3 pcs)', 'Slow-braised beef birria with cilantro, onions, and dipping consommé', 14.50, 'Mexican', true),
  ('b1111111-1111-1111-1111-111111111102', 'a1111111-1111-1111-1111-111111111111', 'Mission Super Burrito', 'Carne asada, pinto beans, Mexican rice, guacamole, crema, and salsa fresca', 16.00, 'Mexican', true),
  ('b1111111-1111-1111-1111-111111111103', 'a1111111-1111-1111-1111-111111111111', 'Horchata con Espresso', 'House-steeped cinnamon rice milk topped with a double espresso shot', 6.00, 'Beverage', true)
ON CONFLICT (id) DO NOTHING;

-- Dishes for Valencia Ramen
INSERT INTO dishes (id, restaurant_id, name, description, price, cuisine_type, is_available)
VALUES
  ('b2222222-2222-2222-2222-222222222201', 'a2222222-2222-2222-2222-222222222222', 'Tonkotsu Black Garlic Ramen', 'Rich pork bone broth, charred garlic oil, chashu pork, and soft-boiled egg', 18.50, 'Japanese', true),
  ('b2222222-2222-2222-2222-222222222202', 'a2222222-2222-2222-2222-222222222222', 'Pan-Fried Pork Gyoza (6 pcs)', 'Crispy bottom dumplings served with scallion ponzu sauce', 9.50, 'Japanese', true),
  ('b2222222-2222-2222-2222-222222222203', 'a2222222-2222-2222-2222-222222222222', 'Spicy Miso Ramen', 'Rich sesame miso broth with ground pork and chili oil', 19.00, 'Japanese', true)
ON CONFLICT (id) DO NOTHING;

-- Dishes for Tartine Bakery
INSERT INTO dishes (id, restaurant_id, name, description, price, cuisine_type, is_available)
VALUES
  ('b3333333-3333-3333-3333-333333333301', 'a3333333-3333-3333-3333-333333333333', 'Country Sourdough Loaf', 'Signature naturally leavened whole-grain loaf with dark blistered crust', 13.00, 'Bakery', true),
  ('b3333333-3333-3333-3333-333333333302', 'a3333333-3333-3333-3333-333333333333', 'Morning Bun', 'Flaky croissant pastry rolled in cinnamon orange sugar', 6.50, 'Bakery', true),
  ('b3333333-3333-3333-3333-333333333303', 'a3333333-3333-3333-3333-333333333333', 'Valrhona Chocolate Croissant', 'Buttery laminated pastry filled with dark Valrhona batons', 7.50, 'Bakery', true)
ON CONFLICT (id) DO NOTHING;
