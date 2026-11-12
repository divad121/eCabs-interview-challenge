# Surge night: Friday, 23:05

Read against the original system: one consumer, in-memory H2, no autoscaling, no publisher confirms.

## 1. What breaks first, and why: the consumer, and everything follows from it

1. **A fixed 30 msg/s sink:** one replica, one listener thread per queue, against ~357 bookings/s. The queue grows
   ~330/s: 143k is ~7 minutes of excess.
2. **Out of memory, and each restart deletes the database:** bookings sit on the heap in H2 (480 MB → 3.6 GB)
   until the OOM kill. Three restarts: bookings already answered "202 Accepted" are gone.
3. **Broker pressure:** the never-consumed audit queue stores every event a second time; RabbitMQ throttles
   publishers, then blocks them all at its memory or disk alarm.
4. **The API stalls:** publishes block, Tomcat's pool fills, p99 hits 6.8 s and the load balancer times out
   (502/504), on a producer at 12× its traffic with no autoscaling.

The worst outcome is the silent data loss in step 2, not the errors customers see.

## 2. The first 15 minutes

1. **23:05 Open the incident:** one lead, one channel; tell dispatch and support that bookings are delayed.
2. **23:07 Confirm:** consumer rate and restarts, broker alarms, producer threads.
3. **23:09 Protect the broker:** its audit queue is the only complete record, including bookings wiped from H2.
4. **23:12 Scale the producer out** (stateless); if the broker throttles, cap traffic at the edge instead: a fast
   429/503 that clients retry beats a 7-second hang.
5. **23:15 Raise the consumer's memory limit** to space out OOM kills. After the peak, replay the audit queue to
   rebuild lost bookings.

**Deliberately not done:** purge a queue (each message is an accepted booking); scale out the consumer (each replica
gets its own empty H2); restart RabbitMQ (100% errors, minutes to recover); hotfix or roll back (nothing was
deployed: this is capacity).

## 3. Make next Friday boring

**Architecture (built here):** bookings in MongoDB Atlas; 4–8 listener threads per pod; KEDA scales consumers 2–20
on queue depth, and both services pre-scale to 8 pods on Fridays 21:30–02:30. Replicated quorum queues, a bounded
audit stream and capped booking queues: overload is a fast 503, never a blocked broker. 202 only after the broker
confirms; Cloud Armor rate limits; no GKE upgrades on Fridays.

**Capacity and process:** load-test at 2× tonight's peak (~720/s), aiming to drain a 143k backlog in 10 minutes;
alert on queue growth and oldest-message age, not CPU; drill this runbook; change freeze from Friday 18:00.

**Cost:** ~$800/month at europe-west1 list prices: Atlas M30 with backups ~$450, RabbitMQ ~$140, baseline pods
~$100, load balancer, Cloud Armor and monitoring ~$90, surge pods ~$15. Mostly the database, which stops the data loss.
