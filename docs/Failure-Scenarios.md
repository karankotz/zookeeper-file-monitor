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

## Summary

```
ZooKeeper lock            -> prevents simultaneous processing
Durable COMPLETED state   -> prevents processing the same file again later
Idempotency               -> protects the small window between side effects
                             and recording completion
```

Together these give **effectively-once** processing.

> "I'll use ZooKeeper for distributed ownership so only one node processes a file concurrently. Because a worker can crash after performing the side effect but before recording completion, I'll make processing idempotent and persist a completion record keyed by a unique file ID. This gives effectively-once behavior even across retries."
