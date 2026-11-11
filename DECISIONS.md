# Decisions

Every non-obvious choice in this submission, with the reasoning behind it.

## Platform

| Decision | Choice | Rationale |
|---|---|---|
| Runtime | GKE Autopilot | The consumer is a long-running AMQP worker, not an HTTP service; Kubernetes runs it natively and lets us scale it on queue depth (KEDA), which is the core of the surge-night problem. Autopilot removes node management. |
| Rejected: Cloud Run | — | Ideal for the producer, but the consumer would need worker pools or a fake HTTP port, queue-depth scaling is not built in, and RabbitMQ would still need a home elsewhere. |
| Rejected: hybrid (Cloud Run + GKE) | — | Two platforms to operate, secure and observe for a two-service system. |
| Messaging | RabbitMQ on-cluster via the RabbitMQ Cluster Operator, 3 replicas across zones, quorum queues | Keeps the existing AMQP contract, replicates messages so a single pod/zone loss loses nothing, stays internal-only, and exposes queue depth for autoscaling. |
| Rejected: CloudAMQP | — | Removes broker operations, but adds a vendor account, private-connectivity work and cost, for a broker the operator already runs well. |
| Rejected: Pub/Sub | — | The most GCP-native option, but requires rewriting messaging in both services; listed under future work. |

## Data

| Decision | Choice | Rationale |
|---|---|---|
| Production datastore | MongoDB Atlas (on GCP), Spring Data MongoDB in the consumer | Matches the eCabs production stack; a booking with embedded waypoints maps naturally to a single document. In-memory H2 loses all data on restart and cannot be shared between replicas. |
| Rejected: Cloud SQL (Postgres) | — | Smallest code change (JPA stays), but diverges from the platform eCabs actually operates. |
| Document shape | One `bookings` document per booking, waypoints embedded | Waypoints are never read without their booking; embedding makes every add/edit/delete a single-document write, which MongoDB applies atomically, so no multi-document transactions (and no `@Transactional`) are needed. |
| Concurrency | `@Version` optimistic locking | Several consumer replicas may process edits for the same booking; a stale write fails instead of silently overwriting. A null version also marks new documents, so `@CreatedDate` works with client-assigned UUIDs. |
| Edit semantics | `PUT /bookings/{id}`: the URL's ID is authoritative (an ID in the body is ignored); the waypoint list in the request replaces the stored list in order and membership, while waypoints already stored are updated in place, so fields the client omits keep their values | Matches PUT semantics for the list while staying tolerant of partial waypoint payloads, which the original code intended. Letting the body's ID win would let one request overwrite a different booking. |
| Redelivered "add" | Duplicate key is logged and ignored | RabbitMQ is at-least-once; re-processing an add must be idempotent rather than dead-lettered. |
| Storage types | UUID as standard binary (subtype 4), `price` as Decimal128, `pickupTime` as BSON date | Native, queryable, language-neutral types. Set in code (not properties) because they define the data format, not the environment. |
| `pickupTime` offset | Stored as a UTC instant; the original offset is not kept | BSON dates have no offset. The instant is what matters operationally; clients render it in local time. Keeping the offset would need a sub-document. |
| Persistence tests | Testcontainers with `mongo:8.0` | Verifies mapping against a real MongoDB (same major version as Atlas) instead of mocks; runs anywhere Docker runs, including CI. |

## Messaging

| Decision | Choice | Rationale |
|---|---|---|
| Topology ownership | Exchanges, queues and bindings defined once in the contract module (`BookingTopology`) and declared by both services | Previously only the consumer declared them, so anything the producer published before the first consumer started was routed nowhere and silently dropped. Declarations are idempotent, so whichever service starts first creates the topology, and a single definition cannot drift between services. |
| Booking queues | Quorum queues | Raft-replicated across the three broker nodes, so losing a pod or zone loses no accepted booking; classic queues live on one node. |
| Dead-lettering | Quorum dead-letter queue, `at-least-once` dead-letter strategy | The default dead-lettering is fire-and-forget and can lose the failed message; at-least-once keeps it until the DLQ has confirmed it. |
| Queue overflow | `reject-publish` overflow (the length limit itself is a broker policy set per environment) | When a queue is full the broker refuses the publish, which reaches the client as a 503 instead of silently dropping the oldest booking. Policies can be tuned without redeploying either service. |
| Audit queue | Stream with 7-day / 5 GB retention | The original audit queue had no consumer and grew without limit until it exhausted broker memory. A stream is an append-only log: retention is bounded, and any number of future consumers (analytics, replay) can read it from any point without removing messages. |
| Publisher confirms | Correlated confirms plus mandatory returns; the API answers 202 only after the broker confirms, otherwise 503 | A 202 is a promise that the booking will be processed. Without confirms, a broker failover, a full queue or an unroutable message lost the booking after the client had been told it was accepted. Waiting for the confirm costs a few milliseconds per request. |
| Consumer retry | 5 attempts with exponential backoff (0.5 s to 4 s), then dead-letter; the error handler logs the queue and full cause, never the payload | Most failures on surge nights are transient: an edit processed before its add (they travel on separate queues), an optimistic-locking conflict, a database blip. The original code dead-lettered all of them on the first failure. Payloads contain passenger PII, so they stay in the DLQ, not in the logs. |
| Retry location | In-process (blocks the consumer thread for up to ~7.5 s) | Simple, and adequate at this volume because other threads keep consuming. At much higher volume a delayed-retry queue would avoid tying up threads; noted as future work. |
| Consumer concurrency | 4-8 threads per pod, prefetch 50 | The original single thread with prefetch 1 capped each pod at one round trip at a time. Prefetch is kept moderate so that a pod being scaled in hands back few in-flight messages. Pod-level scaling on queue depth (KEDA) remains the main lever. |
| Message ordering | Not guaranteed between add, edit and delete for the same booking | Separate queues and concurrent consumers can reorder events. Retry absorbs the common edit-before-add case, and `@Version` stops stale overwrites. Strict per-booking ordering (single active consumer, or consistent-hash partitioning by booking ID) is listed as future work. |
| Messaging tests | Testcontainers RabbitMQ 4.4 + MongoDB 8.0 | Proves that a real broker accepts the topology (quorum and stream arguments are validated server-side), and exercises the end-to-end flow and the edit-before-add retry. |

## Containers

| Decision | Choice | Rationale |
|---|---|---|
| Dockerfile layout | One Dockerfile per service, built from the repository root | Both services depend on the `booking-contract` module, so the build needs the reactor (`-pl <service> -am`); each image copies only its own and the contract's sources, so a change to one service does not invalidate the other's cache. |
| Runtime base image | `gcr.io/distroless/java21-debian13:nonroot` | No shell or package manager: far fewer CVEs and nothing for an attacker to work with; non-root user built in. Debugging uses `kubectl debug` ephemeral containers instead of a shell in the image. |
| Build base image | `eclipse-temurin:21-jdk-noble` | Standard, well-maintained OpenJDK build; only used in the build stage, never shipped. |
| Image pinning | Base images pinned by digest | Reproducible builds; nothing changes underneath a tag between CI runs. Dependabot keeps the digests current. |
| Layering | Spring Boot layered-jar extraction (`-Djarmode=tools extract --layers`) | Dependencies (~38 MB) sit in a cached layer; a code change ships a ~25 KB layer, which makes pushes and node pulls fast during scale-out. |
| Tests in image build | Skipped (`-DskipTests`); CI runs the full test suite before building images | Testcontainers cannot run inside `docker build`, and testing once in CI avoids duplicate work. |
| JVM memory | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` via `JAVA_TOOL_OPTIONS` | Heap derives from the container limit, leaving headroom for metaspace, threads and buffers, so the JVM garbage-collects before the kernel OOM-kills it; if the heap is exhausted, the process exits immediately and is restarted cleanly instead of limping. Overridable without a rebuild. |
| Process model | Exec-form `ENTRYPOINT`, Java as PID 1 | SIGTERM reaches the JVM directly, so Spring's graceful shutdown drains in-flight requests and messages during rollouts. |

## Local environment

| Decision | Choice | Rationale |
|---|---|---|
| Orchestration | Docker Compose, `docker compose up --build` | One command, no tooling beyond Docker; builds the same images that ship to production. |
| Network isolation | RabbitMQ, MongoDB and the consumer on an `internal` network; only the producer joins the published `frontend` network | Mirrors production: the data tier and worker are unreachable from outside, and the internal network also has no outbound route. |
| Local credentials | Throwaway defaults in `docker-compose.yml`, overridable via a git-ignored `.env` | Keeps the reviewer's run to one command; these values protect nothing and never reach production, where secrets come from Secret Manager. |
| Secret delivery locally | Environment variables (production uses mounted files) | The apps accept both; files would need extra local setup for no security gain on a developer machine. |
| Memory limits | `mem_limit: 512m` per service | The JVM sizes its heap from the limit exactly as it will in Kubernetes, so memory problems surface locally first. |
| RabbitMQ user | Runs as the image's unprivileged `rabbitmq` user | Least privilege; also prevents healthcheck CLI calls from creating a root-owned Erlang cookie that would stop the broker from starting. |

## Configuration and operability

| Decision | Choice | Rationale |
|---|---|---|
| Configuration source | Spring's standard environment variables (`SPRING_RABBITMQ_HOST`, `SPRING_MONGODB_URI`, ...) plus an optional `configtree` at `/etc/ecabs/secrets/` | No custom indirection layer to document; any property is overridable without rebuilding. Property files hold only credential-less local defaults. |
| Secret delivery | Secrets as mounted files (file name = property name), not environment variables | Matches the GKE Secret Manager add-on (CSI volume); file mounts do not leak through `kubectl describe`, crash dumps or child processes the way environment variables can. |
| Management port | Actuator on port 8081, API on 8080 | Health, metrics and info are never reachable through the public load balancer, without needing path-based filtering. |
| Probes | Liveness checks only the JVM's own state; readiness adds RabbitMQ (producer) and RabbitMQ + MongoDB (consumer) | Restarting pods does not fix a broker outage and causes restart storms; readiness instead sheds traffic and gates rollouts until a new pod can actually work. |
| Metrics | Micrometer Prometheus endpoint | Request latency/error rates and listener metrics for dashboards and alerting (GKE Managed Prometheus scrapes it). |
| API docs | Swagger/OpenAPI disabled by default, enabled for local runs | Public API documentation is an unnecessary attack-surface map in production. |

## Application stack

| Decision | Choice | Rationale |
|---|---|---|
| Framework | Spring Boot 4.1.x | Boot 2.3 has been out of OSS support since 2021 and pulls in libraries with known CVEs. 4.1 is the newest line with OSS support (until July 2027); 3.5 and 4.0 lose or have lost it in 2026. |
| Java | 21 (LTS) | Long-term support release with mature container support and widely available base images. |
