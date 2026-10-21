#!/usr/bin/env bash
# ==============================================================================
# Phase 6: Right-Sized Workstation Load Testing & SLO Verification (Section 13.2)
# Executes k6 benchmark across Pareto clusters and validates workstation SLOs
# ==============================================================================
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8085}"
GRPC_HOST="${GRPC_HOST:-localhost:9090}"
MODE="${1:---smoke}" # --smoke (quick gate) or --full (1,000+ VUs stress)
LOAD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../load-tests" && pwd)"

echo "===================================================================="
echo "⚡ Starting Phase 6 Workstation Load Benchmark (${MODE})"
echo "   Target Ingress Gateway: ${BASE_URL}"
echo "   Target gRPC Telemetry: ${GRPC_HOST}"
echo "===================================================================="

if [ "${MODE}" == "--full" ]; then
    echo "--> Running Full 1,000 VU Workstation Stress Benchmark (p(95) < 150ms SLO)..."
    k6 run \
        --env BASE_URL="${BASE_URL}" \
        --env GRPC_HOST="${GRPC_HOST}" \
        "${LOAD_DIR}/workstation-benchmark.js"
else
    echo "--> Running Quick Smoke & Verification Gate (20 VUs, 15 seconds)..."
    k6 run \
        --vus 20 \
        --duration 15s \
        --env BASE_URL="${BASE_URL}" \
        --env GRPC_HOST="${GRPC_HOST}" \
        "${LOAD_DIR}/workstation-benchmark.js"
fi

echo ""
echo "--> Executing Fast Synthetic Test Data Teardown (< 100ms)..."
"${LOAD_DIR}/fast-teardown.sh" "8085"

echo "===================================================================="
echo "✔ Workstation Benchmark Execution Completed"
echo "===================================================================="
