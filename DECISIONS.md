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
| Redelivered "add" | Duplicate key is logged and ignored | RabbitMQ is at-least-once; re-processing an add must be idempotent rather than dead-lettered. |
| Storage types | UUID as standard binary (subtype 4), `price` as Decimal128, `pickupTime` as BSON date | Native, queryable, language-neutral types. Set in code (not properties) because they define the data format, not the environment. |
| `pickupTime` offset | Stored as a UTC instant; the original offset is not kept | BSON dates have no offset. The instant is what matters operationally; clients render it in local time. Keeping the offset would need a sub-document. |
| Persistence tests | Testcontainers with `mongo:8.0` | Verifies mapping against a real MongoDB (same major version as Atlas) instead of mocks; runs anywhere Docker runs, including CI. |

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
