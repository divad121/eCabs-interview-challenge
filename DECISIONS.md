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

## Infrastructure as code

| Decision | Choice | Rationale |
|---|---|---|
| Terraform scope | Terraform builds the GCP platform; everything inside the cluster (RabbitMQ, KEDA, the services) is Kubernetes manifests applied by CI | Plan never needs a live cluster, so it works against placeholder projects; app deploys do not run through Terraform, and platform changes do not redeploy apps. |
| Layout | One root configuration; one tfvars file and one state per environment; one GCP project per environment | Environments cannot drift structurally and share no blast radius; IAM and billing separate cleanly. Modules would add indirection for a two-service system. |
| State | GCS backend (bucket given at init), versioned bucket, native locking; reviewers plan through a git-ignored local-backend override | The committed configuration is what production runs; reviewers need one documented extra command. |
| Region | `europe-west1` (Belgium) | Lower-cost EU region with every service used here (Autopilot, Certificate Manager, Atlas `WESTERN_EUROPE`); ~40 ms from Malta is irrelevant for an asynchronous booking API. Data stays in the EU. |
| Cluster access | Private nodes; control plane reachable only through its DNS endpoint, authorised by IAM; IP endpoint disabled | No public node IPs and no IP allow-lists or bastion to maintain for CI runners with changing addresses; every API call is an authenticated, audited Google identity. |
| Node identity | Dedicated node service account with `container.defaultNodeServiceAccount` and registry read only | The default Compute Engine account has project Editor; a compromised node would otherwise own the project. |
| Upgrade window | GKE auto-upgrades only Tuesday-Thursday, 02:00-06:00 UTC | Google must never roll nodes during a Friday-night surge. |
| CI credentials | Workload Identity Federation for GitHub Actions, restricted to this repository's main branch; deployer has `container.developer` and push rights on one repository | No long-lived JSON key exists to leak; pull requests (including from forks) cannot obtain cloud credentials; the deployer cannot alter cluster configuration or RBAC. |
| Production database | Atlas M30 replica set (3 nodes, one per zone) with compute auto-scaling up to M50, continuous backup with point-in-time restore | Comfortable for surge-night write rates (~360 bookings/s peak) with headroom; auto-scaling handles growth, not minute-level spikes (the queue absorbs those). |
| Database connectivity | Private Service Connect (port-mapped architecture), no public access list | The database is unreachable from the internet; one internal IP serves every node. |
| Database password | Ephemeral random value passed only through write-only arguments to Atlas and Secret Manager | Never stored in Terraform state, plan files or CI logs; rotation is one version bump. |
| Secret scoping | Secret Manager IAM granted per secret to the one workload identity that needs it | A compromised producer cannot read the database credentials. |
| RabbitMQ credentials | Generated inside the cluster by the RabbitMQ operators, never in Terraform or Secret Manager | They are only ever used inside the cluster; keeping them there removes a copy to protect. |
| Public edge | GKE Gateway on a global external Application Load Balancer, Google-managed certificate (DNS-authorised), TLS 1.2+ `MODERN` policy, Cloud Armor | Global anycast load balancing and TLS termination at Google's edge; certificate issued before traffic moves. |
| Rate limiting | Cloud Armor throttle at 1,200 requests/minute per client IP, answering 429 | Stops a single client flooding the API; set high because many phones share carrier NAT addresses. Should key on client identity once authentication exists. |
| WAF rules | OWASP SQL-injection and XSS rules in preview (log-only) mode | Enforcing untested rules risks rejecting legitimate bookings on the busiest night; enforce after reviewing the logs. |
| DNS | Cloudflare records set to DNS-only (not proxied) | Proxying would hide client IPs from Cloud Armor's per-IP limit and double-terminate TLS. |
| Images | Artifact Registry with immutable tags (commit SHA), keep the 20 newest, delete versions older than 90 days | A tag always identifies exactly one image, so rollbacks are exact; storage cost stays bounded. |
| Budget alerts | Created manually, not in Terraform | Budget filters need the project number, which Terraform cannot know for a placeholder project without an API call that would break `plan`. |

## Kubernetes

| Decision | Choice | Rationale |
|---|---|---|
| Manifest tooling | Kustomize: one `base`, one overlay per environment holding only project-specific values (images, identities, hostname) | Built into `kubectl`, no templating language; the overlay is a short, reviewable diff from the base. |
| Cluster add-ons | cert-manager, the two RabbitMQ operators and KEDA installed by one idempotent script at versions pinned in a single file, which validation also uses | Add-ons change rarely and are cluster-wide; keeping them out of the app manifests means a service deploy never upgrades an operator. |
| RabbitMQ users | One user per client, generated by the operator: the producer may only publish to `ecabs.message.x`, the consumer may only read its three booking queues, KEDA may only read queue statistics | A leaked producer credential cannot read passenger data from queues, and no credential can alter the topology. Verified locally with the same permissions before writing them down. |
| Queue length limit | Broker policy of 1M messages per booking queue (about 45 minutes of peak traffic with no consumers) | With reject-publish overflow, a full queue becomes 503s to clients instead of a broker that blocks every publisher or runs out of disk. Tunable without redeploying. |
| Consumer scaling | KEDA on queue depth: 2-20 pods, one pod per ~500 waiting messages on the busiest queue, fallback to 8 pods if the depth cannot be read, fast scale-up, 5-minute scale-down | Backlog is the signal that matters on a surge night; CPU rises late for an I/O-bound worker. The ceiling protects the database; it rises with the Atlas tier. |
| Producer scaling | KEDA on CPU (60 %), 3-30 pods | The API is stateless and its CPU tracks request rate; the minimum of three keeps one pod per zone. KEDA rather than a plain HPA so it shares the Friday schedule below. |
| Friday pre-scaling | Cron trigger on both services: at least 8 pods each from 21:30 to 02:30 Malta time on Fridays | Reactive scaling waits for the backlog, then for Autopilot to add nodes (1-2 minutes); the known weekly peak starts with capacity already running, for a few dollars a month. |
| Rollouts | `maxUnavailable: 0`, PodDisruptionBudgets, readiness gates, a 10 s pre-stop pause on the producer | Deploys and node upgrades never reduce capacity, and the load balancer stops sending requests before a pod shuts down. |
| Pod hardening | Non-root, read-only root filesystem, all capabilities dropped, seccomp `RuntimeDefault`, no service-account token mounted; the namespace warns on anything below the `restricted` Pod Security Standard | Limits what an attacker can do with a compromised container. The namespace enforces `baseline` only because the RabbitMQ operator's init container needs it. |
| Network policies | Default deny for ingress; the producer accepts only Google's load-balancer ranges, the consumer only metric scrapes, RabbitMQ only the two services, the operators and KEDA | Only the producer can be reached from outside; a compromised pod elsewhere in the cluster cannot connect to the broker. |
| Public routing | The Gateway routes only `/bookings`; HTTP redirects to HTTPS | Actuator and any future debug endpoints are unreachable even if a port is misconfigured. |
| Metrics collection | Google Managed Service for Prometheus `PodMonitoring` for both services and RabbitMQ's per-queue endpoint | No Prometheus server to run; queue depth, publish and deliver rates sit beside the application metrics for alerting. |
| Validation | `k8s/validate.sh`: renders every overlay and validates it strictly with kubeconform, against schemas generated from the pinned operator CRDs | Catches typos and unknown fields without a cluster. The community schema catalog lags the operators and rejected valid fields. |

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
| Topology ownership | Locally and in tests, both services declare one shared definition (`BookingTopology` in the contract module). In production, the RabbitMQ Messaging Topology Operator creates the same exchanges, queues and bindings from manifests, and the services run with `spring.rabbitmq.dynamic=false` | Previously only the consumer declared them, so anything the producer published before the first consumer started was routed nowhere and silently dropped. Declaring from both services fixes that with zero setup locally. In production it would require every service to hold configure rights on the broker; with the platform owning the topology, a compromised service cannot delete or redefine queues. The Java and YAML definitions must be kept identical (see future work). |
| Booking queues | Quorum queues | Raft-replicated across the three broker nodes, so losing a pod or zone loses no accepted booking; classic queues live on one node. |
| Dead-lettering | Quorum dead-letter queue, `at-least-once` dead-letter strategy | The default dead-lettering is fire-and-forget and can lose the failed message; at-least-once keeps it until the DLQ has confirmed it. |
| Queue overflow | `reject-publish` overflow (the length limit itself is a broker policy set per environment) | When a queue is full the broker refuses the publish, which reaches the client as a 503 instead of silently dropping the oldest booking. Policies can be tuned without redeploying either service. |
| Audit queue | Stream with 7-day / 5 GB retention | The original audit queue had no consumer and grew without limit until it exhausted broker memory. A stream is an append-only log: retention is bounded, and any number of future consumers (analytics, replay) can read it from any point without removing messages. |
| Publisher confirms | Correlated confirms plus mandatory returns; the API answers 202 only after the broker confirms, otherwise 503 | A 202 is a promise that the booking will be processed. Without confirms, a broker failover, a full queue or an unroutable message lost the booking after the client had been told it was accepted. Waiting for the confirm costs a few milliseconds per request. |
| Consumer retry | 5 attempts with exponential backoff (0.5 s to 4 s), then dead-letter; the error handler logs the queue and full cause, never the payload | Most failures on surge nights are transient: an edit processed before its add (they travel on separate queues), an optimistic-locking conflict, a database blip. The original code dead-lettered all of them on the first failure. Payloads contain passenger PII, so they stay in the DLQ, not in the logs. |
| Retry location | In-process (blocks the consumer thread for up to ~7.5 s) | Simple, and adequate at this volume because other threads keep consuming. At much higher volume a delayed-retry queue would avoid tying up threads; noted as future work. |
| Consumer concurrency | 4-8 threads per pod, prefetch 50 | The original single thread with prefetch 1 capped each pod at one round trip at a time. Prefetch is kept moderate so that a pod being scaled in hands back few in-flight messages. Pod-level scaling on queue depth (KEDA) remains the main lever. |
| Message ordering | Not guaranteed by the broker; the consumer makes the outcome order-independent. Retry absorbs edit-before-add, `@Version` stops stale overwrites, and a delete writes a tombstone (`deleted_bookings`, expires after 7 days) that discards any later add or edit | Separate queues and concurrent consumers reorder events, and a delete processed before its add brought the booking back (reproduced locally). The add checks the tombstone after inserting, so concurrent consumers cannot interleave around it, without needing transactions. Strict ordering of successive edits (consistent-hash partitioning by booking ID) is listed as future work. |
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

## CI/CD

| Decision | Choice | Rationale |
|---|---|---|
| Pipeline | GitHub Actions, one workflow: build and test, Terraform, Kubernetes validation and security scans in parallel; images after tests; deploy only after everything passes | The code already lives on GitHub; every pull request gets the same checks that gate production. |
| Tests in CI | Full `./mvnw verify`, including Testcontainers against real RabbitMQ and MongoDB | GitHub's runners include Docker, so the broker topology and database mapping are tested for real, not mocked. |
| Terraform in CI | `fmt`, `validate` and `plan` for both environments against local state with a placeholder token | Proves on every change that the configuration plans cleanly, exactly as reviewers run it, without cloud credentials in pull requests. |
| Image gate | Trivy scan of each built image; fails on HIGH/CRITICAL vulnerabilities that have a fix; the scanned image is the one pushed | Catches vulnerable libraries before they ship: running the same scan before adding it found four HIGH CVEs in the RabbitMQ client bundled by Spring Boot. Unfixable findings are not blocked, since nothing could resolve them. |
| Image tags | Commit SHA only, never `latest` | Matches the registry's immutable tags; a running pod always maps to one commit and one scan result. |
| Action pinning | Every third-party action pinned to a full commit SHA, version in a comment; Dependabot updates them | A tag can be moved to malicious code, a commit SHA cannot. |
| Dependency updates | Dependabot weekly for Maven, base-image digests, Compose images, Actions and Terraform providers, with a 7-day cooldown; no major bumps for the Compose MongoDB and RabbitMQ images | Patches arrive as reviewed, tested pull requests; the cooldown avoids adopting a compromised release in its first days. Local MongoDB and RabbitMQ must stay on the production majors (Atlas 8.0, RabbitMQ 4.4), which are upgraded deliberately. |
| Deploy trigger | Every merge to `main`, behind a `production` GitHub environment, skipped while the project variables are unset | Small, frequent deploys are easier to roll back; the environment can add a manual approval; forks and fresh clones still run green. |
| Deploy method | `kustomize edit set image` to the commit SHA, server-side apply, then wait for both rollouts | The job fails visibly if new pods never become ready, while the old ReplicaSet keeps serving (`maxUnavailable: 0`). |
| Rejected: Cloud Build | — | Equally capable, but splits the pipeline from the pull requests that reviewers read, and would need its own GitHub connection. |

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
| RabbitMQ Java client | Overridden to 5.37.1 (Boot 4.1.2 manages 5.30.0) | 5.30.0 has four HIGH CVEs, fixed in 5.33-5.34; same major line, so Spring AMQP is unaffected. The override comes out once Boot manages a fixed version. |

## What I would do with another day

Ordered by value for surge night first, then security, then housekeeping.

1. **Load test at 2× peak in CI** (k6 against a staging overlay, ~720 bookings/s) to replace the estimated per-pod
   throughput behind the KEDA targets with measured numbers.
2. **Alerts as code:** Cloud Monitoring policies in Terraform for queue growth, age of the oldest message, the
   dead-letter queue, p99 latency and 5xx rate, routed to on-call.
3. **Idempotent `POST`:** accept an `Idempotency-Key` header, so a client retrying after a 503 cannot create a
   duplicate booking (each `POST` currently gets a new UUID).
4. **Strict ordering of successive edits:** a consistent-hash exchange that partitions by booking ID into queues
   with single active consumers.
5. **Delayed-retry queue** instead of in-process retry, so a burst of failing messages cannot tie up listener
   threads for seconds each.
6. **Spare capacity on Autopilot** (low-priority placeholder pods) so unplanned spikes outside the Friday window skip
   the 1-2 minutes of node provisioning.
7. **One source for the broker topology:** generate `k8s/base/rabbitmq-topology.yaml` from `BookingTopology`, or
   fail a test when they differ.
8. **Authentication** (FIXES #9) through eCabs' identity provider, then rate limits keyed on the client instead of
   its IP; **server-side pricing** (FIXES #10).
9. **Database credentials:** Atlas workload identity federation instead of a password, or at least zero-downtime
   rotation that alternates between two database users.
10. **Cluster hardening:** egress network policies, customer-managed encryption keys for Kubernetes secrets, and
    Binary Authorization so the cluster runs only images signed by CI (cosign signatures and SBOMs in the pipeline).
11. **GitOps and promotion:** a staging environment, promotion of the same image digest to production, and Argo CD
    pulling from git instead of CI pushing with `kubectl`.
12. **Faster JVM start for scale-out:** class data sharing or an AOT cache, measured against pod readiness time.
13. **Multi-region disaster recovery** with explicit RTO and RPO targets (the other Part 4 option).
14. **Evaluate Pub/Sub** against self-run RabbitMQ once the platform team's operating cost is known.
15. **Housekeeping:** load Mockito as a Java agent (FIXES #23); remove or implement the unused waypoint messages
    (FIXES #24).
