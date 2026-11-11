# ADR 1: Move booking data from the consumer's H2 into MongoDB Atlas without downtime

**Status:** proposed · **Scope:** the running production system (old code, old broker topology). The code in this
repository is the target state.

## Context

- **The data exists only in one process's heap.** The consumer runs H2 in memory (`jdbc:h2:mem`, `create-drop`), on
  a single replica. There is no TCP server or console, so nothing outside the process can read it. **Any restart
  deletes it, including the deploy that would ship the Atlas code.** So "export, then switch" is impossible without
  first securing the data somewhere else.
- **The broker already holds a complete history.** Every event is fanned out to `ecabs.messageaudit.q`, a durable
  queue with persistent messages and no consumer. It holds every add, edit and delete since it was created, in
  publish order. That is a superset of H2, which lost everything before its last restart.
- **Events carry full state.** An add carries the whole booking, an edit is a full `PUT` and a delete names the ID.
  Replaying them in order rebuilds the state deterministically, and re-applying a run of events in order converges
  to the same result.
- **A queue's type cannot change in place.** The target topology uses quorum queues and a stream, so they need new
  names (for example, a `.v2` suffix) beside the old classic queues.
- **Bookings must keep flowing:** the API keeps answering, and processing never stops.

## Decision

**Rebuild Atlas by replaying the broker's event history, then run the new consumers beside the old one on parallel
queues.** The old consumer is never restarted, modified or read until Atlas is complete and verified. All changes
before decommissioning are broker bindings and new deployments, so the old path keeps working throughout.

## Plan

1. **Prepare (no production impact).** Provision Atlas with Terraform. Ship the new consumer with order-safe,
   idempotent handlers: insert-if-absent adds, full-state edits, deletes that leave a tombstone (all implemented in
   this repository). Add a single-threaded replay mode that reads a stream in order and writes to Atlas. All services
   run with `spring.rabbitmq.dynamic=false`, so no service redeclares a queue with conflicting arguments.
2. **Open the live path and freeze the history (broker-only changes, seconds apart).**
   1. Bind new quorum queues (`ecabs.bookingadd.q.v2` and the others) to `ecabs.booking.x` with the same routing
      keys, and bind a new audit stream to `ecabs.message.x`. From now on every event also lands there, buffered
      with no consumer yet.
   2. Unbind the old audit queue. It becomes a closed history *H*. The few events published between the two steps
      exist in both places; step 4 shows why that is harmless.
   3. Shovel *H* into a stream `ecabs.audit.history`. Unlike a queue, a stream can be re-read, so the backfill can
      be repeated if anything goes wrong.
3. **Check that the history is complete before trusting it.** It must contain every booking ID that the old
   consumer's logs show as processed, and its size must match the exchange's publish counters. If there is a gap,
   the only other copy is the old consumer's heap: take a heap dump through an ephemeral debug container (no restart)
   and extract the missing rows offline. This is a last resort.
4. **Backfill.** One replay instance reads `ecabs.audit.history` from the first offset into Atlas, in strict order,
   with ordered bulk writes. Overlapping events are a run at the end of *H* and the same run at the start of the new
   queues. Applying them a second time, in order, before any later event, ends in the same state, and tombstones
   stop a duplicated add from reviving a deleted booking. Atlas also regains the bookings that H2 lost in earlier
   restarts.
5. **Go live.** Only after the backfill has finished, start the new consumers (KEDA-scaled) on the `.v2` queues.
   They work through the buffered events, then keep up in real time. The old consumer still runs on the old queues,
   so both stores receive every event (dual-run).
6. **Verify during dual-run** (for days, not hours, and not over a Friday). Compare per-day counts of adds, edits and
   deletes in both consumers' logs against Atlas, spot-check bookings end to end, and watch the dead-letter queue and
   consumer lag on the new path. H2 cannot be queried, so the evidence is the replay's determinism plus these checks.
7. **Decommission.** Unbind the old queues, let the old consumer drain them, then remove it and delete the old
   queues. Re-point the audit retention at the new stream. Nobody notices this step.

## Rollback

- **Up to step 7:** the old path is untouched. Stop the new consumers; messages wait in the `.v2` queues.
- **After step 7:** the history and audit streams remain, so Atlas can be rebuilt from scratch at any time by
  replaying them. A rollback to H2 would make no sense, because it loses data on every restart.

## Consequences

**Positive:**
- No maintenance window.
- Nothing depends on the fragile process until its data is secured.
- The backfill can be repeated.
- Bookings that H2 had already lost are recovered.
- Atlas never has two writers racing: the backfill finishes before the new consumers start.

**Negative:**
- Broker storage is doubled during the dual-run.
- Queue names change, and the contract's constants change with them.
- The ordered replay is single-threaded, so a long history takes hours: run it on a weekday morning.
- Verification is indirect, because H2 cannot be queried.

## Alternatives considered

| Option | Rejected because |
|---|---|
| Add an export endpoint or H2 TCP server, then copy | Requires deploying the consumer: the restart wipes the data before the export can run |
| Dual-write from the consumer to H2 and Atlas | Same restart problem, and existing bookings never reach Atlas |
| Maintenance window (stop intake, export, switch) | Violates the requirement, and still cannot export without a restart |
| Change data capture (for example Debezium) | No connector for in-memory H2 |
