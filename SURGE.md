# Surge night: Friday, 23:05

Read against the original system: one consumer, in-memory H2, no autoscaling, no publisher confirms.

## 1. What breaks first, and why

**The consumer; everything else follows from it.**

1. **It is a fixed 30 msg/s sink:** one replica, one listener thread per queue (its custom factory ignored every
   listener setting). Demand is ~357 bookings/s, so the queue grows ~330/s: 143k is ~7 minutes of excess.
2. **It runs out of memory, and each restart deletes the database.** Every booking stored tonight sits on the heap
   in H2 (480 MB → 3.6 GB) until the pod is OOM-killed. Three restarts: tonight's accepted bookings are gone, after
   customers were told "202 Accepted". Each restart also stops consumption, and edits for wiped bookings dead-letter.
3. **The broker comes under pressure.** Every event is stored twice, once in the audit queue, which has no consumer
   and never shrinks. RabbitMQ throttles publishers, and at its memory or disk alarm it blocks all of them.
4. **The API stalls.** Producer threads block in publish, Tomcat's pool fills, p99 hits 6.8 s and the load balancer
   times out (the 502/504s), on a producer taking 12× its traffic with no autoscaling.

Customers see errors last; the silent data loss in step 2 is the worst outcome.

## 2. The first 15 minutes

1. **23:05 Open the incident:** one lead, one channel; tell dispatch and support that bookings are delayed.
2. **23:07 Confirm the read:** consumer rate and restarts, broker alarms and flow control, producer CPU and threads.
3. **23:09 Protect the broker**, the only complete record: the audit queue holds every event, including bookings
   wiped from H2. Add disk or memory if an alarm is close.
4. **23:12 Scale the producer out** (stateless, safe). If the broker is throttling, cap traffic at the edge instead:
   a fast 429/503 that clients retry beats a 7-second hang.
5. **23:15 Raise the consumer's memory limit** to space out the OOM kills; it restarts every ~7 minutes anyway.
   Update stakeholders every 15 minutes; after the peak, replay the audit queue to rebuild lost bookings.

**Deliberately not done:**

- **Purge a queue:** every message is an accepted booking, and the audit queue is the only copy of those lost.
- **Scale out the consumer:** each replica gets its own empty H2, so edits and deletes land on the wrong one.
- **Restart RabbitMQ or "everything":** 100% errors, and a broker with this backlog takes minutes to recover.
- **Hotfix, tune or roll back at peak:** nothing was deployed; this is capacity, not a bad release.

## 3. Make next Friday boring

**Architecture (built in this repository):** bookings in MongoDB Atlas, so restarts lose nothing and consumers
scale out; 4–8 listener threads per pod; KEDA scales consumers 2–20 on queue depth, and both services pre-scale to
8 pods every Friday 21:30–02:30. Quorum queues across three zones, the audit queue as a bounded stream, and capped
booking queues, so overload is a fast 503, never a blocked broker. 202 only after the broker confirms the write.
Cloud Armor rate-limits per client; GKE never upgrades on Fridays.

**Capacity:** load-test at 2× tonight's peak (~720/s) before trusting it; target: drain a 143k backlog in 10 minutes.

**Process:** alert on queue growth and oldest-message age, not CPU; drill this runbook; freeze changes from Friday
18:00; add pre-scale windows for known events.

**Cost** (list prices, europe-west1, approximate):

| Item | Per month |
|---|---|
| Atlas M30 replica set with backups | ~$450 |
| RabbitMQ: 3 × (1 vCPU, 2 GiB) plus disks | ~$140 |
| Baseline pods: 3 producer, 2 consumer | ~$100 |
| Friday pre-scaling and surge pods (~22 h) | ~$15 |
| Load balancer, Cloud Armor, monitoring | ~$90 |
| **Total** | **~$800** |

Most of it is the database, the fix for losing bookings. Surge capacity costs ~$15 because it scales away by 02:30.
