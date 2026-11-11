# eCabs booking platform

A booking API (`booking-producer-service`) accepts ride bookings and publishes events to RabbitMQ; a worker
(`booking-consumer-service`) consumes them and persists bookings to MongoDB. Prepared to run on Google Cloud (GKE).

Design choices and their rationale are in [DECISIONS.md](DECISIONS.md).

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

## Modules

* `booking-producer-service` – REST API, request validation, publishes booking events to RabbitMQ.
* `booking-consumer-service` – RabbitMQ listeners, booking logic and persistence (MongoDB).
* `booking-contract` – DTOs, messages and AMQP names shared by both services: the communication contract.
* `terraform/` – the GCP platform (network, GKE Autopilot, IAM, Artifact Registry, Secret Manager, MongoDB Atlas,
  load-balancer edge). See [terraform/README.md](terraform/README.md) for validate and plan instructions.

## Assumptions

* The client application holds the booking state and uses the generated UUIDs for `PUT` and `DELETE` operations.
