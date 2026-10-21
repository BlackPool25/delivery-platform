#!/usr/bin/env bash
# ==============================================================================
# Sovereign Delivery Platform - Kubernetes Deployment Script (Phase 6)
# Provisions local Kind cluster, ingress gateway, and sovereign microservices
# ==============================================================================
set -euo pipefail

CLUSTER_NAME="${CLUSTER_NAME:-delivery-cluster}"
NAMESPACE="${NAMESPACE:-delivery-prod}"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "===================================================================="
echo "🚀 Deploying Sovereign Delivery Platform to Kubernetes (Kind)"
echo "===================================================================="

# 1. Create or verify Kind Cluster
if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
    echo "✔ Kind cluster '${CLUSTER_NAME}' already running."
else
    echo "--> Creating Kind cluster '${CLUSTER_NAME}' with ports 8085 (HTTP) and 9090 (gRPC)..."
    kind create cluster --name "${CLUSTER_NAME}" --config "${PROJECT_ROOT}/k8s/kind-cluster-config.yaml"
fi

kubectl cluster-info --context "kind-${CLUSTER_NAME}"

# 2. Deploy Ingress Controller (ingress-nginx)
echo "--> Deploying Ingress NGINX controller..."
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml

echo "--> Waiting for Ingress controller pod readiness..."
kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=120s || echo "Notice: Ingress controller still initializing"

# 3. Create Namespace & ConfigMaps
echo "--> Creating namespace '${NAMESPACE}' and k6 ConfigMap..."
kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

kubectl create configmap k6-test-scripts -n "${NAMESPACE}" \
  --from-file="${PROJECT_ROOT}/load-tests/" \
  --dry-run=client -o yaml | kubectl apply -f -

# 4. Deploy Base Datastores & Infrastructure
echo "--> Deploying datastores: PostGIS, Redis, RabbitMQ, MinIO, ntfy..."
kubectl apply -f "${PROJECT_ROOT}/k8s/postgres/postgres.yaml"
kubectl apply -f "${PROJECT_ROOT}/k8s/redis/redis.yaml"
kubectl apply -f "${PROJECT_ROOT}/k8s/rabbitmq/rabbitmq.yaml"
kubectl apply -f "${PROJECT_ROOT}/k8s/minio/minio.yaml"
kubectl apply -f "${PROJECT_ROOT}/k8s/ntfy/ntfy.yaml"

# 5. Deploy Sovereign Boundary Services
echo "--> Deploying local boundary mocks: Payment Ledger & SMS Gateway..."
kubectl apply -f "${PROJECT_ROOT}/k8s/local-payment-service/local-payment-service.yaml"
kubectl apply -f "${PROJECT_ROOT}/k8s/local-sms-gateway/local-sms-gateway.yaml"

# 6. Deploy Core Domain Microservices & Offline Geospatial Engines
echo "--> Deploying core microservices & offline GIS engines..."
kubectl apply -f "${PROJECT_ROOT}/k8s/auth-service/auth-service.yaml"
kubectl apply -f "${PROJECT_ROOT}/k8s/catalog-service/catalog-service.yaml"
kubectl apply -f "${PROJECT_ROOT}/k8s/order-service/order-service.yaml"
kubectl apply -f "${PROJECT_ROOT}/k8s/tracking-service/tracking-service.yaml"
kubectl apply -f "${PROJECT_ROOT}/k8s/media-service/media-service.yaml"
kubectl apply -f "${PROJECT_ROOT}/k8s/transcoder/transcode-worker.yaml"
kubectl apply -f "${PROJECT_ROOT}/k8s/martin/martin.yaml"
kubectl apply -f "${PROJECT_ROOT}/k8s/valhalla/valhalla.yaml"
kubectl apply -f "${PROJECT_ROOT}/k8s/courier-simulator/courier-simulator.yaml"
kubectl apply -f "${PROJECT_ROOT}/k8s/digital-twin-ui/digital-twin-ui.yaml"

# 7. Apply Phase 6 Ingress Gateway (Host Port 8085)
echo "--> Applying Phase 6 Ingress Gateway routing..."
kubectl apply -f "${PROJECT_ROOT}/k8s/ingress/delivery-ingress-phase6.yaml"

# 8. Apply Chaos Mesh Experiments Manifests
echo "--> Applying Chaos Mesh experiment descriptors..."
kubectl apply -f "${PROJECT_ROOT}/k8s/chaos/" || true

echo "===================================================================="
echo "✔ Kubernetes Deployment Complete!"
echo "  - Ingress Gateway Host Port: http://localhost:8085"
echo "  - High-Throughput gRPC Host Port: localhost:9090"
echo "===================================================================="
