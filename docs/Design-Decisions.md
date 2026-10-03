# Design Decisions

## Per-file lock, not a global lock

❌ One global lock:

```
/file-processing-lock
```

Only one node can work at a time, so the other nodes are idle.

✅ One znode per file:

```
/processing/A
/processing/B
/processing/C
```

Nodes process different files concurrently.

## Ephemeral ownership znode vs. Curator `InterProcessMutex`

Curator's mutex recipe:

```java
InterProcessMutex lock =
    new InterProcessMutex(curatorFramework, "/file-locks/" + fileId);

if (lock.acquire(0, TimeUnit.SECONDS)) {
    try {
        processFile(file);
    } finally {
        lock.release();
    }
}
```

For this use case we **prefer a plain ephemeral ownership znode**: each file is an independent unit of work assigned to one owner, and a non-waiting `create` is simpler. We do want **Curator as the client library**, though, because it handles connection loss, retries and session management.

## Znode naming

Use `sha256(absolutePath)` as `fileId` so znode paths are always valid. Use raw filenames only if they're guaranteed unique and path-safe.

## ZooKeeper vs. the database

| Concern | Where it lives |
|---------|----------------|
| Live ownership | ZooKeeper (ephemeral) |
| Completion history | Durable registry (DB) |

Don't use ZooKeeper as the long-term history store: it's built for small coordination data, not growing records.

## No exactly-once claim

The design guarantees **mutual exclusion** plus **effectively-once** processing through the registry and idempotency. It doesn't claim strict exactly-once.
