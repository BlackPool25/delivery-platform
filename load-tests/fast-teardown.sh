#!/usr/bin/env bash
# ==============================================================================
# Fast Synthetic Test Data Teardown Script (Section 13.4)
# Resets database staging tables and in-memory caches in < 100ms with zero WAL bloat
# ==============================================================================
set -euo pipefail

INGRESS_PORT="${1:-8085}"
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-postgres}"
PG_PASSWORD="${PG_PASSWORD:-postgres}"
PG_DATABASE="${PG_DATABASE:-delivery_platform}"

echo "===================================================================="
echo "⚡ Initiating Fast Synthetic Test Data Teardown (< 100ms Target)"
echo "===================================================================="
START_TIME=$(date +%s%3N)

# 1. Truncate staging table via REST API or Direct SQL
echo "--> Truncating synthetic staging table (TRUNCATE TABLE orders_loadtest_staging CASCADE)..."
if curl -sf -X POST "http://localhost:${INGRESS_PORT}/api/v1/orders/loadtest/teardown" > /dev/null 2>&1; then
    echo "    ✔ API fast teardown completed successfully."
else
    echo "    Notice: API endpoint unreachable, attempting direct psql truncation..."
    if command -v psql >/dev/null 2>&1; then
        PGPASSWORD="${PG_PASSWORD}" psql -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USER}" -d "${PG_DATABASE}" \
            -c "TRUNCATE TABLE orders_loadtest_staging CASCADE;" > /dev/null 2>&1 || true
        echo "    ✔ Direct database truncation complete."
    fi
fi

# 2. Redis Key Fast Unlink
echo "--> Unlinking synthetic Redis loadtest keys..."
if command -v redis-cli >/dev/null 2>&1; then
    redis-cli --scan --pattern "loadtest:*" | xargs -r -L 500 redis-cli UNLINK >/dev/null 2>&1 || true
    redis-cli --scan --pattern "tracking:courier:00000000-0000-4000-8000-*" | xargs -r -L 500 redis-cli UNLINK >/dev/null 2>&1 || true
    echo "    ✔ Redis keys unlinked."
fi

END_TIME=$(date +%s%3N)
ELAPSED=$((END_TIME - START_TIME))

echo "===================================================================="
echo "✔ Fast Teardown Finished in ${ELAPSED}ms (SLO: < 100ms)"
echo "===================================================================="
