#!/usr/bin/env bash
# Load test driver for the distributed rate limiter.
#
# Usage:
#   bash load-test/run-load-test.sh            # runs k6 if available, else JMeter
#   bash load-test/run-load-test.sh jmeter     # force JMeter
#   bash load-test/run-load-test.sh k6         # force k6
#
# Prerequisites:
#   - rate limiter service running on :8080 (or set TARGET_HOST/TARGET_PORT)
#   - Redis running on :6379 (or set REDIS_HOST/REDIS_PORT in service env)

set -euo pipefail

TARGET_HOST="${TARGET_HOST:-localhost}"
TARGET_PORT="${TARGET_PORT:-8080}"
METHOD="${1:-auto}"

# Pre-flight check
echo "Checking service health at http://${TARGET_HOST}:${TARGET_PORT}/actuator/health ..."
if ! curl -sf "http://${TARGET_HOST}:${TARGET_PORT}/actuator/health" | grep -q '"status":"UP"'; then
    echo "ERROR: rate limiter service is not healthy. Start it first: mvn spring-boot:run"
    exit 1
fi

if [[ "$METHOD" == "k6" || "$METHOD" == "auto" ]]; then
    if command -v k6 &>/dev/null; then
        echo "Running k6 load test (10k rps target) ..."
        k6 run \
            --out - \
            -e BASE_URL="http://${TARGET_HOST}:${TARGET_PORT}" \
            "$(dirname "$0")/rate-limiter-load.js"
        echo "Done. Check output above for p95 latency and error rate."
        exit 0
    elif [[ "$METHOD" == "k6" ]]; then
        echo "ERROR: k6 is not installed. Install: https://k6.io/docs/quick-start/"
        exit 1
    fi
fi

if [[ "$METHOD" == "jmeter" || "$METHOD" == "auto" ]]; then
    if command -v jmeter &>/dev/null; then
        echo "Running JMeter load test (200 threads, 20,000 total requests) ..."
        jmeter -n \
            -JtargetHost="${TARGET_HOST}" \
            -JtargetPort="${TARGET_PORT}" \
            -t "$(dirname "$0")/rate-limiter-load.jmx" \
            -l "$(dirname "$0")/results.jtl" \
            -j /dev/null
        echo "JTL results: $(dirname "$0")/results.jtl"
        echo "Parse with: jmeter -g $(dirname "$0")/results.jml"
        exit 0
    elif [[ "$METHOD" == "jmeter" ]]; then
        echo "ERROR: jmeter is not installed. Install: https://jmeter.apache.org/"
        exit 1
    fi
fi

echo "ERROR: neither k6 nor jmeter found on PATH. Install one and re-run."
exit 1
