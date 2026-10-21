import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import grpc from 'k6/net/grpc';

// Custom Micrometer / Prometheus aligned metrics
export const orderCheckoutTrend = new Trend('order_checkout_duration', true);
export const courierTelemetryTrend = new Trend('courier_telemetry_duration', true);
export const mapTileTrend = new Trend('map_tile_duration', true);
export const grpcTelemetryTrend = new Trend('grpc_telemetry_duration', true);
export const authChurnTrend = new Trend('auth_refresh_duration', true);

export const orderSuccessRate = new Rate('order_success_rate');
export const telemetrySuccessRate = new Rate('telemetry_success_rate');
export const globalErrorRate = new Rate('global_error_rate');
export const totalOrdersCreated = new Counter('total_orders_created');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8085';
const GRPC_HOST = __ENV.GRPC_HOST || 'localhost:9090';
const ENABLE_GRPC = __ENV.ENABLE_GRPC === 'true';

// 4-Hub Commercial Dining Pareto Hubs (San Francisco WGS 84 EPSG:4326)
const PARETO_HUBS = [
    { name: 'Financial District', lat: 37.7955, lon: -122.3937 },
    { name: 'SOMA Tech Corridor', lat: 37.7844, lon: -122.4014 },
    { name: 'Mission Culinary Hub', lat: 37.7599, lon: -122.4148 },
    { name: 'Marina & North Beach', lat: 37.8035, lon: -122.4368 }
];

// Fixed seed restaurants in SF from catalog-service V2 seed migration
const SEED_RESTAURANTS = [
    'a1111111-1111-1111-1111-111111111111', // Mission Taqueria
    'a2222222-2222-2222-2222-222222222222', // Valencia Ramen & Izakaya
    'a3333333-3333-3333-3333-333333333333'  // Tartine Bakery
];

// Pre-generated pool of virtual couriers (25-50 active couriers)
const VIRTUAL_COURIERS = [];
for (let i = 1; i <= 50; i++) {
    const hex = i.toString(16).padStart(4, '0');
    VIRTUAL_COURIERS.push(`00000000-0000-4000-8000-${hex}00000001`);
}

// Default execution options with SLO thresholds matching Section 13.6
export const options = {
    scenarios: {
        workstation_stress: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '15s', target: 100 },   // Warmup ramp
                { duration: '30s', target: 500 },   // Scaling to medium load
                { duration: '45s', target: 1000 },  // Workstation target benchmark (1,000 VUs)
                { duration: '30s', target: 200 },   // Cool down ramp
                { duration: '10s', target: 0 }
            ],
            gracefulRampDown: '5s'
        }
    },
    thresholds: {
        // Section 13.6: Workstation Performance SLO Thresholds
        'order_checkout_duration': ['p(95)<150', 'p(99)<300'],
        'courier_telemetry_duration': ['p(95)<30', 'p(99)<60'],
        'map_tile_duration': ['p(95)<25'],
        'global_error_rate': ['rate<0.001'], // Global 5xx error rate < 0.1%
        'order_success_rate': ['rate>0.999']
    }
};

// Generates Pareto clustered coordinates (70% within 4 hubs, 30% suburban)
function sampleLocation() {
    const isHub = Math.random() < 0.70;
    if (isHub) {
        const hub = PARETO_HUBS[Math.floor(Math.random() * PARETO_HUBS.length)];
        // Normal distribution around hub center (~400m sigma -> ~0.0035 degrees)
        const dLat = (Math.random() - 0.5) * 0.007;
        const dLon = (Math.random() - 0.5) * 0.007;
        return { lat: hub.lat + dLat, lon: hub.lon + dLon };
    } else {
        // Uniform suburban SF distribution
        const lat = 37.7100 + Math.random() * 0.0600;
        const lon = -122.4800 + Math.random() * 0.0600;
        return { lat: lat, lon: lon };
    }
}

// Generate random UUID v4
function uuidv4() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
        const r = Math.random() * 16 | 0;
        const v = c === 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

const grpcClient = new grpc.Client();
if (ENABLE_GRPC) {
    grpcClient.load(['../services/tracking-service/src/main/proto'], 'telemetry.proto');
}

export default function () {
    const vuId = __VU;
    const iteration = __ITER;
    const loc = sampleLocation();
    const courierId = VIRTUAL_COURIERS[vuId % VIRTUAL_COURIERS.length];
    const restaurantId = SEED_RESTAURANTS[Math.floor(Math.random() * SEED_RESTAURANTS.length)];
    const userId = uuidv4();

    // 1. High-Frequency Courier Telemetry Ingestion (p(95) < 30ms SLO)
    const telemetryPayload = JSON.stringify({
        latitude: loc.lat,
        longitude: loc.lon,
        timestamp: new Date().toISOString(),
        speedKmh: 18.5 + (Math.random() * 10),
        heading: Math.floor(Math.random() * 360),
        accuracy: 3.5
    });

    const telemetryHeaders = {
        'Content-Type': 'application/json',
        'X-Test-Tenant': 'LOADTEST_LOCAL'
    };

    const telemetryRes = http.post(
        `${BASE_URL}/api/v1/tracking/couriers/${courierId}/telemetry`,
        telemetryPayload,
        { headers: telemetryHeaders, tags: { type: 'telemetry' } }
    );

    courierTelemetryTrend.add(telemetryRes.timings.duration);
    const telemetryOk = check(telemetryRes, {
        'telemetry accepted (200 or 422)': (r) => r.status === 200 || r.status === 422
    });
    telemetrySuccessRate.add(telemetryOk ? 1 : 0);
    globalErrorRate.add(telemetryRes.status >= 500 ? 1 : 0);

    // 2. Order Placement & Checkout Transaction (p(95) < 150ms SLO)
    if (iteration % 3 === 0) {
        const idempotencyKey = uuidv4();
        const orderPayload = JSON.stringify({
            userId: userId,
            restaurantId: restaurantId,
            dropoffLongitude: loc.lon,
            dropoffLatitude: loc.lat,
            dropoffBuilding: 'Apex Tower ' + ((vuId % 20) + 1),
            dropoffFloor: 'Floor ' + ((vuId % 10) + 1),
            dropoffRoom: 'Suite ' + (100 + (vuId % 90)),
            dropoffGateCode: 'GATE#4829',
            dropoffNotes: 'Contactless delivery during workstation benchmark',
            verificationPin: '123456',
            idempotencyKey: idempotencyKey,
            items: [
                {
                    dishId: uuidv4(),
                    dishName: 'Artisan Sourdough Pizza',
                    unitPrice: 22.00,
                    quantity: 1,
                    customizationNotes: 'Extra basil'
                }
            ]
        });

        const orderHeaders = {
            'Content-Type': 'application/json',
            'Idempotency-Key': idempotencyKey,
            'X-Test-Tenant': 'LOADTEST_LOCAL'
        };

        const orderRes = http.post(
            `${BASE_URL}/api/v1/orders`,
            orderPayload,
            { headers: orderHeaders, tags: { type: 'order_checkout' } }
        );

        orderCheckoutTrend.add(orderRes.timings.duration);
        const orderOk = check(orderRes, {
            'order checkout success (201 Created or 200 Idempotent)': (r) => r.status === 201 || r.status === 200
        });
        orderSuccessRate.add(orderOk ? 1 : 0);
        globalErrorRate.add(orderRes.status >= 500 ? 1 : 0);
        if (orderOk) {
            totalOrdersCreated.add(1);
        }
    }

    // 3. Realistic Auth Churn: Token Refresh Ingestion (15-25 RPS)
    if (iteration % 5 === 0) {
        const authPayload = JSON.stringify({
            refreshToken: 'mock-refresh-token-' + userId
        });
        const authRes = http.post(
            `${BASE_URL}/api/v1/auth/refresh`,
            authPayload,
            { headers: { 'Content-Type': 'application/json', 'X-Test-Tenant': 'LOADTEST_LOCAL' }, tags: { type: 'auth_churn' } }
        );
        authChurnTrend.add(authRes.timings.duration);
    }

    // 4. Vector Map Tile Serving (p(95) < 25ms SLO)
    if (iteration % 4 === 0) {
        const tileRes = http.get(
            `${BASE_URL}/tiles/city/0/0/0`,
            { tags: { type: 'tiles' } }
        );
        mapTileTrend.add(tileRes.timings.duration);
        check(tileRes, {
            'tile response valid': (r) => r.status === 200 || r.status === 404
        });
    }

    // Dynamic pacing for realistic developer workstation concurrency
    sleep(0.05 + Math.random() * 0.1);
}
