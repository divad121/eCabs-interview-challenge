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

## Application stack

| Decision | Choice | Rationale |
|---|---|---|
| Framework | Spring Boot 4.1.x | Boot 2.3 has been out of OSS support since 2021 and pulls in libraries with known CVEs. 4.1 is the newest line with OSS support (until July 2027); 3.5 and 4.0 lose or have lost it in 2026. |
| Java | 21 (LTS) | Long-term support release with mature container support and widely available base images. |
