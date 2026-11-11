#!/usr/bin/env bash
# Installs the cluster add-ons the application manifests depend on, at pinned versions. Idempotent: re-run to
# upgrade after changing a version. Requires kubectl (pointed at the cluster) and helm.
set -euo pipefail

source "$(dirname "$0")/versions.env"

helm repo add jetstack https://charts.jetstack.io --force-update
helm repo add kedacore https://kedacore.github.io/charts --force-update

# cert-manager issues the topology operator's webhook certificate. Autopilot forbids writes to kube-system,
# so leader election moves into cert-manager's own namespace.
helm upgrade --install cert-manager jetstack/cert-manager \
  --namespace cert-manager --create-namespace \
  --version "${CERT_MANAGER_VERSION}" \
  --set crds.enabled=true \
  --set global.leaderElection.namespace=cert-manager \
  --wait

kubectl apply --server-side -f \
  "https://github.com/rabbitmq/cluster-operator/releases/download/${RABBITMQ_CLUSTER_OPERATOR_VERSION}/cluster-operator.yml"
kubectl -n rabbitmq-system rollout status deployment/rabbitmq-cluster-operator --timeout=5m

kubectl apply --server-side -f \
  "https://github.com/rabbitmq/messaging-topology-operator/releases/download/${RABBITMQ_TOPOLOGY_OPERATOR_VERSION}/messaging-topology-operator-with-certmanager.yaml"
kubectl -n rabbitmq-system rollout status deployment/messaging-topology-operator --timeout=5m

helm upgrade --install keda kedacore/keda \
  --namespace keda --create-namespace \
  --version "${KEDA_VERSION}" \
  --wait
