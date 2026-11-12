# Kubernetes

Everything that runs inside the GKE cluster. Terraform (`../terraform`) builds the cluster and the cloud resources
these manifests reference by name: the static IP, certificate map, TLS policy, Cloud Armor policy, the consumer's
Google service account and its MongoDB secret.

```
platform/          cluster add-ons (cert-manager, RabbitMQ operators, KEDA) at pinned versions
base/              the application: RabbitMQ cluster, topology and users, both services, Gateway, policies
overlays/prod/     production values (images, project, hostname)
overlays/demo/     the same for a short-lived demo in a personal project
validate.sh        offline validation of every overlay
```

## Validate (no cluster needed)

Requires `kubectl`, [`kubeconform`](https://github.com/yannh/kubeconform), `python3` with PyYAML, and `curl`:

```bash
./k8s/validate.sh
```

To see exactly what would be applied: `kubectl kustomize k8s/overlays/prod`.

## Deploy

Once per cluster, after `terraform apply`:

```bash
gcloud container clusters get-credentials ecabs-gke --region europe-west1 --dns-endpoint
./k8s/platform/install.sh
```

Then for each release (CI does this on every push to `main`):

```bash
cd k8s/overlays/prod
kustomize edit set image \
  booking-producer=europe-west1-docker.pkg.dev/ecabs-booking-prod/ecabs/booking-producer:<git-sha> \
  booking-consumer=europe-west1-docker.pkg.dev/ecabs-booking-prod/ecabs/booking-consumer:<git-sha>
kubectl apply --server-side -k .
```

On the very first apply, the service pods wait in `ContainerCreating` until RabbitMQ is up and the operator has
created their credential Secrets; this resolves itself within a few minutes.

## How the pieces fit

- **RabbitMQ**: three nodes, one per zone, created by the Cluster Operator. The Messaging Topology Operator creates
  the exchanges, queues and bindings (identical to `BookingTopology` in the contract module) and one user per client
  with the minimum permissions. The services never declare topology here (`SPRING_RABBITMQ_DYNAMIC=false`).
- **Credentials**: the operator writes each user's generated password to a `<user>-user-credentials` Secret, mounted
  into the pod as files that Spring reads as `spring.rabbitmq.username`/`password`. The MongoDB connection string
  is mounted from Secret Manager by the GKE add-on, readable only by the consumer's Workload Identity. After
  rotating it (see `../terraform/README.md`), restart the consumer: `kubectl -n ecabs rollout restart deployment/booking-consumer`.
- **Scaling**: KEDA scales the consumer on the depth of the busiest booking queue and the producer on CPU; both
  are pre-scaled to 8 pods every Friday from 21:30 to 02:30 Malta time.
- **Edge**: the Gateway creates the global load balancer on the reserved IP, with the certificate map, TLS policy and
  Cloud Armor rate limit attached. Only `/bookings` is routed.
