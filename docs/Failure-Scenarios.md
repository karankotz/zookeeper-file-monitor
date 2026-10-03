# Failure Scenarios

## 1. Worker crashes before finishing

```
Node A acquires /processing/file123
Node A crashes mid-processing
ZooKeeper session expires -> znode deleted
Node B acquires /processing/file123 and processes it
```

✅ Handled by **ephemeral znodes**. The file is not lost and is not stuck.

## 2. Worker crashes after the side effect but before recording completion

```
Node A acquires lock
   ↓
Node A processes file
   ↓
Database write succeeds
   ↓
Node A crashes            <-- file NOT yet marked/moved
   ↓
ZooKeeper lock disappears
   ↓
Node B processes the same file   <-- DUPLICATE
```

❌ ZooKeeper alone cannot prevent this. **ZooKeeper gives coordination, not exactly-once processing.**

Mitigations:

- **Record completion in the same transaction** as the processing side effects when they share a database.
- **Make processing idempotent** — e.g. upsert keyed by `fileId`, so a retry produces the same result.

## 3. File re-appears / is re-detected later

A node restarts and its `WatchService` re-scans the folder, or the file wasn't moved yet.

✅ Handled by the **completion registry** — `COMPLETED` files are skipped.

## 4. Stalled worker comes back after losing its session ("zombie")

```
node-a claims file, starts processing
node-a stalls (long GC pause / network partition) > session timeout
ZooKeeper expires node-a's session -> claim deleted
node-b claims the file, markProcessing sets workerId = node-b
node-b completes the file
node-a wakes up and tries markCompleted
```

✅ Handled by **fencing in DynamoDB**. `markCompleted` has the condition `workerId = :me`, so node-a's write is rejected and its stale result is discarded. node-a's `release()` also checks that the znode's `ephemeralOwner` is still its own session, so it can't delete a claim that now belongs to node-b.

This is covered by the test `workerThatLosesItsSessionIsFencedAndAnotherWorkerTakesOver`.

## 5. Crashed worker's files have no new filesystem event

✅ Handled by the **periodic rescan**. Survivors find the file on the next rescan once the claim expires. Observed in the [demo](Running-the-Demo.md): node-1 killed at 14:31:52, its files re-claimed as attempt 2 at 14:32:05.

## 6. Processing keeps failing

✅ `markFailed` records the error and the file is retried on later rescans. After `MAX_ATTEMPTS` it is moved to `failed/`.

## Summary

```
ZooKeeper lock            -> prevents simultaneous processing
Durable COMPLETED state   -> prevents processing the same file again later
Idempotency               -> protects the small window between side effects
                             and recording completion
```

Together these give **effectively-once** processing.

> "I'll use ZooKeeper for distributed ownership so only one node processes a file concurrently. Because a worker can crash after performing the side effect but before recording completion, I'll make processing idempotent and persist a completion record keyed by a unique file ID. This gives effectively-once behavior even across retries."
