#!/usr/bin/env bash
# Renders every overlay and validates it strictly, offline from the cluster. RabbitMQ resources are checked against
# the CRDs of the pinned operator versions; KEDA and GKE resources against the community CRD catalog.
# Requires kubectl, kubeconform, python3 with PyYAML, and curl.
set -euo pipefail

cd "$(dirname "$0")"
source platform/versions.env

KUBERNETES_VERSION="${KUBERNETES_VERSION:-1.34.0}"
OPENAPI2JSONSCHEMA_URL="https://raw.githubusercontent.com/yannh/kubeconform/v0.8.0/scripts/openapi2jsonschema.py"
work="$(mktemp -d)"
trap 'rm -rf "${work}"' EXIT

curl -fsSL -o "${work}/openapi2jsonschema.py" "${OPENAPI2JSONSCHEMA_URL}"
curl -fsSL -o "${work}/cluster-operator.yml" \
  "https://github.com/rabbitmq/cluster-operator/releases/download/${RABBITMQ_CLUSTER_OPERATOR_VERSION}/cluster-operator.yml"
curl -fsSL -o "${work}/topology-operator.yml" \
  "https://github.com/rabbitmq/messaging-topology-operator/releases/download/${RABBITMQ_TOPOLOGY_OPERATOR_VERSION}/messaging-topology-operator-with-certmanager.yaml"

mkdir -p "${work}/schemas"
(cd "${work}/schemas" && FILENAME_FORMAT='{fullgroup}-{kind}-{version}' \
  python3 ../openapi2jsonschema.py ../cluster-operator.yml ../topology-operator.yml >/dev/null)

for overlay in overlays/*/; do
  echo "Validating ${overlay}"
  kubectl kustomize "${overlay}" | kubeconform -strict -summary \
    -kubernetes-version "${KUBERNETES_VERSION}" \
    -schema-location default \
    -schema-location "${work}/schemas/{{.Group}}-{{.ResourceKind}}-{{.ResourceAPIVersion}}.json" \
    -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
done
