// k6 load test for the distributed rate limiter.
//
// Usage:
//   k6 run rate-limiter-load.js
//
// Target: 10,000+ sustained requests/second.
// The script rotates across 10,000 distinct client IDs (X-Api-Key header)
// so no single client is rate-limited during the test — we're measuring
// raw Redis throughput, not the limiter's rejection behaviour.

import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const CLIENT_IDS = 10_000;

export const options = {
    stages: [
        { duration: '10s', target: 2000 },   // ramp to 2k rps
        { duration: '20s', target: 10000 },  // hold at 10k rps
        { duration: '10s', target: 0 },      // drain
    ],
};

export function setup() {
    // Warm up: verify the service is reachable
    const res = http.get(`${BASE}/actuator/health`);
    if (res.status !== 200) {
        console.log(`WARNING: /actuator/health returned ${res.status} — service may not be running`);
    }
}

export default function () {
    const clientId = `k6-${Math.floor(Math.random() * CLIENT_IDS)}`;
    const url = `${BASE}/ratelimit/check/${clientId}`;

    const res = http.get(url, {
        headers: { 'X-Api-Key': clientId },
    });

    check(res, {
        'status is 200': (r) => r.status === 200,
        'X-RateLimit-Limit header present': (r) => r.headers['X-RateLimit-Limit'] !== undefined,
        'response time < 500ms': (r) => r.timings.duration < 500,
    });
}

export function handleSummary(data) {
    return {
        'load-test-summary.json': JSON.stringify({
            checks: data.root_group.checks,
            rate: data.metrics.http_reqs,
            http_req_failed: data.metrics.http_req_failed,
            http_req_duration: data.metrics.http_req_duration,
        }),
    };
}
