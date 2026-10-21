import grpc from 'k6/net/grpc';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

export const grpcDuration = new Trend('grpc_telemetry_duration', true);
export const grpcSuccessRate = new Rate('grpc_success_rate');
export const grpcPingsSent = new Counter('grpc_pings_sent');

const client = new grpc.Client();
client.load(['../services/tracking-service/src/main/proto'], 'telemetry.proto');

const GRPC_HOST = __ENV.GRPC_HOST || 'localhost:9090';

export const options = {
    scenarios: {
        grpc_workstation_load: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '10s', target: 50 },
                { duration: '20s', target: 100 },
                { duration: '20s', target: 200 },
                { duration: '10s', target: 0 }
            ],
            gracefulRampDown: '5s'
        }
    },
    thresholds: {
        'grpc_telemetry_duration': ['p(95)<30', 'p(99)<60'],
        'grpc_success_rate': ['rate>0.99']
    }
};

// 4-Hub commercial centers in SF
const HUBS = [
    { lat: 37.7955, lon: -122.3937 }, // FiDi
    { lat: 37.7844, lon: -122.4014 }, // SOMA
    { lat: 37.7599, lon: -122.4148 }, // Mission
    { lat: 37.8035, lon: -122.4368 }  // Marina
];

function uuidv4() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
        const r = Math.random() * 16 | 0;
        const v = c === 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

export default function () {
    try {
        client.connect(GRPC_HOST, { plaintext: true });
    } catch (e) {
        // Connection already established, reuse multiplexed HTTP/2 channel
    }

    const vuId = __VU;
    const hub = HUBS[vuId % HUBS.length];
    const courierId = `00000000-0000-4000-8000-${(vuId % 50).toString(16).padStart(4, '0')}00000001`;

    // Realistic continuous kinematic movement (~20 km/h) to pass anti-spoofing velocity filter (< 120 km/h)
    const step = (__ITER * 0.000005) % 0.01;
    const lat = hub.lat + step;
    const lon = hub.lon + step;

    // 1. Unary IngestTelemetry
    const pingRequest = {
        courier_id: courierId,
        latitude: lat,
        longitude: lon,
        timestamp_epoch_ms: Date.now(),
        speed_kmh: 22.5,
        heading: 90.0,
        accuracy: 3.0
    };

    const start = Date.now();
    const response = client.invoke('com.delivery.tracking.grpc.CourierTelemetryService/IngestTelemetry', pingRequest);
    const duration = Date.now() - start;

    grpcDuration.add(duration);
    grpcPingsSent.add(1);

    const isOk = check(response, {
        'grpc status is OK': (r) => r && r.status === grpc.StatusOK,
        'telemetry accepted': (r) => r && r.message && r.message.accepted === true
    });
    grpcSuccessRate.add(isOk ? 1 : 0);

    // 2. Unary GetCourierLocation
    if (__ITER % 5 === 0) {
        const locResponse = client.invoke('com.delivery.tracking.grpc.CourierTelemetryService/GetCourierLocation', {
            courier_id: courierId
        });
        check(locResponse, {
            'location query status is OK': (r) => r && r.status === grpc.StatusOK,
            'location found': (r) => r && r.message && r.message.found === true
        });
    }

    sleep(0.05 + Math.random() * 0.05);
}
