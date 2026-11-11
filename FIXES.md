# Fixes

Every issue found in the original code (commit `b79c2b7`), ranked by production risk: how likely it is to lose
bookings, take the platform down or expose data on a surge night. Each entry gives the impact, the remedy and the
status, with the commit that fixed it. Deferred items are deliberate; the reason is given.

| Status | Count |
|---|---|
| Fixed | 24 |
| Partly fixed | 1 |
| Deferred | 3 |

## Critical: loses bookings or takes the platform down

1. **Bookings stored in an in-memory H2 database** (`jdbc:h2:mem`, `ddl-auto=create-drop`). Fixed in `189362a`.
   - *Impact:* every restart, deploy or crash deletes all bookings. Every booking also stays on the consumer's heap, so memory climbs until the pod is OOM-killed, which then wipes the data. Replicas can't share data, so the consumer can't scale out.
   - *Remedy:* moved persistence to MongoDB (Atlas in production), with optimistic locking and an idempotent add.
2. **Audit queue with no consumer and no limit** (`ecabs.messageaudit.q`). Fixed in `52e4063`.
   - *Impact:* it receives a copy of every booking event and grows forever, which I observed locally. On a busy night it fills broker memory, and RabbitMQ's memory alarm then blocks *all* publishers, so the booking API stalls.
   - *Remedy:* made it a stream with 7-day / 5 GB retention, which stays readable by future audit consumers.
3. **The API answers 202 for bookings that may never be stored.** Fixed in `52e4063`.
   - *Impact:* there were no publisher confirms and no mandatory flag, and only the consumer declared the exchanges and queues. Events published before the consumer's first start went to an exchange that didn't exist yet and were discarded. A broker failure or restart could also drop in-flight messages from the single-node classic queues, after the client had been told "accepted".
   - *Remedy:* both services now declare one shared topology, and the producer waits for the broker's confirm, returning 503 so the client can retry. Booking queues are now quorum queues, replicated across brokers.
4. **Credentials hard-coded and committed** (`guest/guest`, `sa/password`). Fixed in `6f77d8c`.
   - *Impact:* secrets sat in the jar and in git history, and changing them required a rebuild. `guest` only works from localhost, so the image couldn't have run against a real broker anyway.
   - *Remedy:* removed all credentials from the code. Settings come from environment variables, and secrets from files mounted from Secret Manager. Anything ever committed must be treated as burned.
5. **End-of-life stack with known vulnerabilities.** Fixed in `52aa6d9`.
   - *Impact:* Spring Boot 2.3 has been out of support since 2021, Jackson 2.11.1 was pinned by hand (several deserialisation CVEs) and springdoc 1.2 and ModelMapper 2.3 were outdated, with no security patches.
   - *Remedy:* upgraded to Spring Boot 4.1 and Java 21 with Boot-managed versions. Dependabot will keep it current (added with CI).

## High: degrades badly under load, corrupts data or exposes the service

6. **Consumer throughput is fixed regardless of backlog.** Fixed in `52e4063` and `k8s/base/booking-consumer.yaml`.
   - *Impact:* one listener thread per queue and no autoscaling produce the flat ~30 msg/s seen in the surge metrics while the queue climbs.
   - *Remedy:* 4–8 threads per pod with a bounded prefetch, and KEDA scales pods from 2 to 20 on the depth of the busiest booking queue, holding 8 if it cannot read the depth.
7. **Every failure dead-letters immediately, with a one-line log.** Fixed in `52e4063`.
   - *Impact:* transient errors were treated as permanent: an edit processed before its add (they travel on separate queues), a database blip or a locking conflict. Each permanently lost a booking change, and the log omitted the queue and the cause.
   - *Remedy:* 5 attempts with exponential backoff, then dead-lettering with at-least-once delivery. The log now shows the queue and full stack trace, without the PII-bearing payload.
8. **Listener configuration ignored.** Fixed in `189362a` and `6f77d8c`.
   - *Impact:* the custom listener factory bypassed every `spring.rabbitmq.listener.simple.*` property, and `listener.direct.default-requeue-rejected` targeted a container type that wasn't in use. Concurrency, prefetch and retry could not be tuned without a code change.
   - *Remedy:* the factory now applies Boot's configurer, and the property names are corrected.
9. **No API authentication or rate limiting.** Partly fixed in `terraform/edge.tf` and `k8s/base/gateway.yaml`.
   - *Impact:* anyone who can reach the API can create, edit or delete any booking by its ID, and nothing limits a client flooding it.
   - *Remedy:* Cloud Armor now limits each client IP at the load balancer and only `/bookings` is routed (done). Authentication, validating tokens from eCabs' identity provider, is deferred because the identity model belongs to the wider platform, not this service.
10. **Client-supplied price accepted as-is.** Deferred.
    - *Impact:* a client can submit any fare, including €0, and the system stores it.
    - *Remedy:* calculate the price on the server from the tariff, or verify a signed quote. That's business logic outside the scope of a platform exercise.
11. **An edit could overwrite a different booking.** Fixed in `a7325cf`.
    - *Impact:* the `bookingId` in the request body was copied onto the stored booking, so `PUT /bookings/A` with B's ID in the body wrote to B.
    - *Remedy:* the ID in the URL is authoritative in both services.
12. **Edits duplicated existing waypoints.** Fixed in `a7325cf`.
    - *Impact:* the ModelMapper converter updated a matched waypoint and then appended it again, so every edit that kept a waypoint grew the route. The tests never exercised that path.
    - *Remedy:* the request's list now defines the waypoints and their order. Stored waypoints are updated in place, and new tests cover the case.
13. **Waypoints were never validated** (no `@Valid` on the list). Fixed in `a7325cf`.
    - *Impact:* waypoints with no coordinates or a blank locality were accepted and published.
    - *Remedy:* each waypoint is now validated and rejected with a 400 before publishing.
14. **No health probes or metrics.** Fixed in `6f77d8c`.
    - *Impact:* an orchestrator couldn't tell a stuck or disconnected pod from a healthy one, and latency, error rates and listener throughput were invisible.
    - *Remedy:* Actuator liveness and readiness probes (readiness includes RabbitMQ and MongoDB) and Prometheus metrics, all on a separate management port.

## Medium: security hygiene, correctness edge cases, build reliability

15. **Swagger UI and the API docs exposed by default.** Fixed in `6f77d8c`.
    - *Impact:* they publish a complete map of the API to anyone who can reach it in production.
    - *Remedy:* disabled by default and enabled only for local runs.
16. **Passenger names written to logs.** Fixed in `4f9b69c`.
    - *Impact:* both services logged passenger names (at debug level). Turning on debug during an incident would ship personal data into log storage, a GDPR exposure.
    - *Remedy:* logs carry booking IDs only.
17. **No ordering between add, edit and delete for the same booking.** Fixed in `52e4063` and `BookingsServiceImpl` (tombstones).
    - *Impact:* the three events use separate queues and concurrent consumers. A delete processed before its add brought the booking back to life (reproduced locally), and an edit can arrive before its add.
    - *Remedy:* retries absorb edit-before-add, `@Version` blocks stale overwrites, and a delete leaves a 7-day tombstone that discards any later add or edit. Strict ordering of successive edits would need consistent-hash partitioning by booking ID; it's listed under future work.
18. **Bean overriding enabled to hide a duplicate `RabbitTemplate`.** Fixed in `52aa6d9`.
    - *Impact:* `allow-bean-definition-overriding` masked a second template. Its private `ObjectMapper` ignored the application's JSON settings (dates were sent as numeric timestamps), and future bean clashes would be hidden silently.
    - *Remedy:* removed the flag and the custom template; Boot's template uses the shared converter.
19. **Inconsistent Java levels and no unified build.** Fixed in `52aa6d9`.
    - *Impact:* the compiler targets were 1.7 in the parent, 8 in the contract and producer, 10 in the consumer and 11 in `java.version`. Each service also had its own Maven wrapper, and there was no root wrapper, so builds depended on the machine.
    - *Remedy:* Java 21 everywhere, a single root Maven wrapper and a reactor build (`-pl <service> -am`).
20. **Maven wrapper not executable** (`mvnw` without the executable bit). Fixed in `cd1ab5f`.
    - *Impact:* `./mvnw` fails with "permission denied" on Linux CI runners and in container builds.
    - *Remedy:* set the executable bit in git and pinned LF line endings in `.gitattributes`.
21. **Contract module forced heavy runtime dependencies.** Fixed in `52aa6d9`.
    - *Impact:* the shared contract pulled in `spring-boot-starter-validation` (Hibernate Validator, Tomcat EL) and hand-pinned Jackson versions into every consumer of the contract.
    - *Remedy:* the contract depends only on the validation API (and Spring AMQP for the shared topology). Implementations come from each service.
22. **Tests reached for a real broker and never touched a real database.** Fixed in `52aa6d9` and `189362a`.
    - *Impact:* the consumer tests started live listeners against `localhost:5672`, with a 1 ms timeout hiding the connection errors. A developer with a local broker running would have had real messages consumed. Persistence was only ever tested against mocks, and the tests ran on mixed JUnit 4 and 5.
    - *Remedy:* listeners are off in tests that call the service directly, and Testcontainers runs real MongoDB and RabbitMQ for the persistence and messaging tests, all on JUnit 5.

## Low and cosmetic

23. **Mockito attaches itself as a Java agent at test time.** Deferred.
    - *Impact:* tests log a warning today, and future JDKs will refuse dynamic agent loading, breaking the suite on a JDK upgrade.
    - *Remedy:* pass Mockito as `-javaagent` in the Surefire configuration. It's deferred because it's a warning and has no production effect.
24. **Unused contract messages** (`AddWaypointMessage`, `EditWaypointMessage`, `DelWaypointMessage`). Deferred.
    - *Impact:* dead code in a shared contract suggests events that no service produces or handles.
    - *Remedy:* delete them, or implement waypoint-level events if they're needed. It's deferred because changing the contract should be agreed with its owners.
25. **Exchange declared twice, with a typo** (`massageExchange`). Fixed in `52e4063`.
    - *Impact:* harmless at runtime, but a sign the topology was never reviewed.
    - *Remedy:* replaced by the single shared `BookingTopology` definition.
26. **IDE file committed and an incomplete `.gitignore`** (`booking-contract.iml`). Fixed in `52aa6d9`.
    - *Impact:* noise in the repository, and a risk of committing local or secret files such as `.env`.
    - *Remedy:* removed the file. `.gitignore` now covers build output, IDE files and `.env*` (except `.env.example`).
27. **Version drift between modules.** Fixed in `52aa6d9`.
    - *Impact:* the services were `0.0.1-SNAPSHOT` under a `1.0-SNAPSHOT` parent, and the contract version was repeated by hand.
    - *Remedy:* every module inherits the parent version, and the contract version is managed in the parent.
28. **Stray typos** (`;;` in `TripWaypointDTO`). Fixed in `a7325cf`.
