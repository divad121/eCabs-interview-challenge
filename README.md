# eCabs booking platform

A booking API (`booking-producer-service`) accepts ride bookings and publishes events to RabbitMQ; a worker
(`booking-consumer-service`) consumes them and persists bookings to MongoDB. Prepared to run on Google Cloud (GKE).

> **A note on scope.** The brief expects 4–6 hours and warns against gold-plating, and this submission goes further
> on purpose. eCabs runs on Google Cloud, which is new to me, so I used the challenge to learn it properly: next to
> the local stack, I built the production setup in full (GKE Autopilot, Workload Identity, Secret Manager, the
> Gateway with Cloud Armor, KEDA scaling) instead of stopping at a Terraform outline. The brief's core is the
> one-command local run, the Dockerfiles, `terraform/`, CI and the documents below; every choice beyond it is
> explained in DECISIONS.md, and what I deliberately left out is listed there under "What I would do with another day".
> I also deployed it to a personal GCP project to prove it works beyond `plan` (see [Live demo on GCP](#live-demo-on-gcp)).

| Document | Contents |
|---|---|
| [FIXES.md](FIXES.md) | Every issue found in the original code, ranked by production risk, with its status |
| [DECISIONS.md](DECISIONS.md) | Every non-obvious choice with its rationale, and what I would do with another day |
| [SURGE.md](SURGE.md) | The Friday-night surge: failure chain, first 15 minutes, making next Friday boring |
| [ADR.md](ADR.md) | Moving booking data from the in-memory H2 into MongoDB Atlas without downtime |

## Run it locally (one command)

Prerequisites: Docker with Compose v2.

```bash
docker compose up --build
```

The first build takes a few minutes (Maven downloads dependencies); later builds are cached. The API is ready when
the producer logs `Started BookingProducerServiceApplication`.

Only the API is published, on <http://localhost:8080>. RabbitMQ, MongoDB and the consumer run on an internal Docker
network with no published ports.

### Make a booking and watch it being processed

In a second terminal, follow the consumer:

```bash
docker compose logs -f booking-consumer
```

Create a booking (bash):

```bash
curl -i -X POST http://localhost:8080/bookings -H 'Content-Type: application/json' -d '{
  "passengerName": "Jane Doe", "passengerContactNumber": "+35699740158",
  "pickupTime": "2026-11-13T23:05:00+01:00", "asap": true, "noOfPassengers": 2, "price": 18.50,
  "tripWayPoints": [
    {"lastStop": false, "locality": "Paceville", "lat": 35.92, "lng": 14.49},
    {"lastStop": true,  "locality": "Sliema",    "lat": 35.912, "lng": 14.504}
  ]}'
```

or PowerShell:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/bookings -ContentType 'application/json' -Body '{
  "passengerName": "Jane Doe", "passengerContactNumber": "+35699740158",
  "pickupTime": "2026-11-13T23:05:00+01:00", "asap": true, "noOfPassengers": 2, "price": 18.50,
  "tripWayPoints": [
    {"lastStop": false, "locality": "Paceville", "lat": 35.92, "lng": 14.49},
    {"lastStop": true,  "locality": "Sliema",    "lat": 35.912, "lng": 14.504}
  ]}'
```

The API answers `202 Accepted` with the generated `bookingId` once RabbitMQ has confirmed it stored the event
(or `503 Service Unavailable` if it could not, so the client can retry), and the consumer logs:

```
... BookingsServiceImpl : Booking 81876e83-6b40-4a0a-b2ef-9474995a8d09 added
```

Edits (`PUT /bookings/{id}`) and deletions (`DELETE /bookings/{id}`) log `updated` / `deleted` the same way.
Interactive API documentation is available locally at <http://localhost:8080/swagger-ui/index.html>.

### Inspect the data tier

```bash
docker compose exec rabbitmq rabbitmqctl list_queues name type messages consumers
docker compose exec mongodb mongosh -u ecabs -p local-dev-only --authenticationDatabase admin ecabs \
  --eval 'db.bookings.find().pretty()'
```

Messages that still fail after five attempts (for example an edit of a booking that does not exist) are moved to
`ecabs.dlq`; the consumer logs the queue and cause. The RabbitMQ management UI is not published to the host; to browse
it, temporarily add `ports: ["15672:15672"]` to the `rabbitmq` service.

> Upgrading from an earlier checkout: run `docker compose down -v` once. The booking queues are now quorum queues, and
> RabbitMQ refuses to redeclare an existing classic queue with a different type.

### Stop

```bash
docker compose down        # keep data
docker compose down -v     # also delete RabbitMQ and MongoDB volumes
```

## Live demo on GCP

The same Terraform and CI pipeline are deployed to a personal GCP project, at **`https://api.ecabs.divad.dev`**
(`divad.dev` is a domain I keep for dev/test projects). It is temporary and will be torn down after the review, so
the URL may no longer answer.

```bash
curl -i -X POST https://api.ecabs.divad.dev/bookings -H 'Content-Type: application/json' -d '{
  "passengerName": "Jane Doe", "passengerContactNumber": "+35699740158",
  "pickupTime": "2026-11-13T23:05:00+01:00", "asap": true, "noOfPassengers": 2, "price": 18.50,
  "tripWayPoints": [{"lastStop": true, "locality": "Sliema", "lat": 35.912, "lng": 14.504}]}'
```

It runs the production design (Atlas M10 over Private Service Connect, Workload Identity, the Gateway with a
Google-managed certificate and Cloud Armor, keyless deploys from GitHub), with the demo overlay
([`k8s/overlays/demo`](k8s/overlays/demo/kustomization.yaml)) scaled down to fit a free-trial project's quota of
12 vCPUs: one RabbitMQ node instead of three, fewer replicas, no Friday pre-scaling.

Applying it for real caught two things `terraform plan` and manifest validation could not: the pods were rejected
because the distroless image names its user (`nonroot`) instead of giving a numeric ID that the kubelet can check
against `runAsNonRoot` (fixed in the base manifests with UID 65532), and the production shape exceeds a trial
project's CPU and SSD quotas.

## Configuration

Nothing is baked into the images: every Spring property can be overridden at runtime with an environment variable
(Spring's relaxed binding, e.g. `spring.rabbitmq.password` → `SPRING_RABBITMQ_PASSWORD`) or with a file in
`/etc/ecabs/secrets/` whose name is the property name (how secrets are mounted in Kubernetes).

| Variable | Service | Purpose |
|---|---|---|
| `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_PORT` | both | Broker address (default `localhost:5672`) |
| `SPRING_RABBITMQ_USERNAME`, `SPRING_RABBITMQ_PASSWORD` | both | Broker credentials (secret) |
| `SPRING_MONGODB_URI` | consumer | MongoDB connection string incl. credentials (secret) |
| `SPRINGDOC_API_DOCS_ENABLED`, `SPRINGDOC_SWAGGER_UI_ENABLED` | producer | API docs, off by default |
| `JAVA_TOOL_OPTIONS` | both | JVM flags (default `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`) |

Local compose credentials are throwaway defaults; override them with a git-ignored `.env` file
(template: [.env.example](.env.example)).

Ports: the producer serves the API on `8080`; both services serve actuator endpoints on the management port `8081`
(`/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/prometheus`), which is never exposed publicly.

## Build

Java 21 and Docker are the only requirements; Maven is provided by the wrapper.

```bash
./mvnw verify                      # build all modules and run all tests (MongoDB tests use Testcontainers, so Docker must be running)

docker build -f booking-producer-service/Dockerfile -t booking-producer-service .
docker build -f booking-consumer-service/Dockerfile -t booking-consumer-service .
```

Images are built from the repository root because both services depend on the shared `booking-contract` module.
The Dockerfiles are multi-stage (JDK build stage, distroless non-root Java 21 runtime) and skip tests, which run in CI
before images are built.

## CI/CD

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every pull request and every push to `main`:

| Job | What it does |
|---|---|
| Build and test | `./mvnw verify`: all modules, including the Testcontainers suites against real RabbitMQ and MongoDB |
| Terraform | `fmt -check`, `validate`, and `plan` for both `prod.tfvars` and `demo.tfvars.example` (local state, no credentials) |
| Kubernetes manifests | `k8s/validate.sh`: renders every overlay and validates it strictly against the pinned CRDs |
| Secret and configuration scan | gitleaks on the commits; Trivy on the Dockerfiles, Terraform and manifests (fails on HIGH/CRITICAL) |
| Image (one per service) | builds the image and scans it with Trivy, failing on fixable HIGH/CRITICAL vulnerabilities; on `main`, pushes it to Artifact Registry tagged with the commit SHA |
| Deploy to GKE | on `main`, after every other job passes: pins the overlay to the new images, applies it and waits for the rollout |

Pushing and deploying are switched on by repository variables taken from the Terraform outputs; until they are set,
those steps are skipped and everything else still runs:

| Variable | Value |
|---|---|
| `GCP_PROJECT_ID` | the project ID |
| `GCP_REGION` | optional, defaults to `europe-west1` |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | output `github_workload_identity_provider` |
| `GCP_CI_SERVICE_ACCOUNT` | output `ci_service_account` |
| `K8S_OVERLAY` | optional, defaults to `prod` |

CI authenticates to Google with short-lived Workload Identity Federation tokens, accepted only from this
repository's `main` branch; no service-account key exists. [Dependabot](.github/dependabot.yml) opens weekly
update pull requests for Maven, base images, Compose images, GitHub Actions and Terraform providers.

**About the first red run.** The pipeline's first run
([43095061621](https://github.com/divad121/eCabs-interview-challenge/actions/runs/43095061621), 11 November) failed
in Build and test. The log showed the MongoDB Testcontainer exiting within a second of starting, so the consumer's
database tests failed before reaching any application code. The same evening GitHub had an
[incident affecting Actions](https://www.githubstatus.com/incidents/dvy0t3mvh1lg) (20:48–21:21 UTC), which explains
the rest of what the run did: jobs stuck in the queue, the secret scan cancelled and the logs unavailable for a
while. The commit passed locally on Windows and in a Linux container, and re-running the failed jobs passed with no
code change. The logs don't say why `mongod` exited; if it happened again, the first step would be pinning the test
image to an exact patch version instead of `mongo:8.0`, which Docker Hub re-tags whenever a new build is published.

## Modules

* `booking-producer-service` – REST API, request validation, publishes booking events to RabbitMQ.
* `booking-consumer-service` – RabbitMQ listeners, booking logic and persistence (MongoDB).
* `booking-contract` – DTOs, messages and AMQP names shared by both services: the communication contract.
* `terraform/` – the GCP platform (network, GKE Autopilot, IAM, Artifact Registry, Secret Manager, MongoDB Atlas,
  load-balancer edge). See [terraform/README.md](terraform/README.md) for validate and plan instructions.
* `k8s/` – everything inside the cluster (RabbitMQ and its topology, both services, autoscaling, Gateway, network
  policies). See [k8s/README.md](k8s/README.md) for validation and deployment.

## Assumptions

* The client application holds the booking state and uses the generated UUIDs for `PUT` and `DELETE` operations.
