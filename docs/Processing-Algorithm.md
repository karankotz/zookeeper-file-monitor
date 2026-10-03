# Processing Algorithm

Every worker node runs the same loop.

## Steps

1. Monitor the directory (e.g. Java `WatchService`).
2. When a file appears, compute a unique `fileId`.
3. Check the completion registry — if `COMPLETED`, ignore it.
4. Try to create the ephemeral znode `/processing/<fileId>`.
5. If it fails with `NodeExists`, another worker owns it — skip.
6. If it succeeds, process the file.
7. Make processing **idempotent** where possible.
8. Record `COMPLETED` in durable storage.
9. Move / delete / archive the input file.
10. Delete the ZooKeeper ownership znode.
11. If the worker crashes, ZooKeeper removes the ephemeral znode and another worker retries.

## Flow

```
Node finds file123
        |
Check completion registry
        |
   COMPLETED?
   /       \
 yes        no
  |          |
ignore   create /processing/file123 (EPHEMERAL)
             |
         success?
         /      \
       no        yes
        |         |
     ignore    process file
                  |
          DB transaction: status = COMPLETED
                  |
              move file
                  |
         delete /processing/file123
```

## Parallelism

Each file has its own znode, so different nodes work on different files at the same time:

```
Folder: A.csv B.csv C.csv D.csv E.csv F.csv

Node 1        Node 2        Node 3
lock A        lock B        lock C
process A     process B     process C
lock D        lock E        lock F
process D     process E     process F
```

## In the real code

[`FileMonitorNode.handle()`](../src/main/java/com/karankotz/filemonitor/FileMonitorNode.java) implements this flow:

1. `registry.isCompleted(fileId)`: skip (and archive) files that are already done
2. `ownership.tryClaim(fileId)`: ephemeral znode `/file-monitor/processing/<fileId>`
3. `registry.markProcessing(...)`: conditional write, `attempts + 1`
4. `processor.process(file)`
5. `registry.markCompleted(...)`: result + COMPLETED in one write, **fenced** on `workerId`
6. move to `processed/`
7. `ownership.release(claim)`: deletes the znode **only if our session still owns it**

Two things the sketch below leaves out:

- **Periodic rescan.** `WatchService` only reports *new* events. A file whose owner crashed produces no new event, so each node also rescans the inbox every `RESCAN_INTERVAL_MS`. That is how the survivors find orphaned files.
- **Write-then-rename.** Producers write `name.tmp` and rename it when done. Workers ignore `*.tmp` and dotfiles, so they never read half-written files.

## Java sketch

```java
void onFileDetected(Path file) throws Exception {
    String fileId = sha256(file.toAbsolutePath().toString());

    if (registry.isCompleted(fileId)) {
        return;
    }

    String lockPath = "/processing/" + fileId;
    try {
        zk.create(lockPath, nodeId.getBytes(),
                  ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
    } catch (KeeperException.NodeExistsException e) {
        return; // another node owns it
    }

    try {
        if (registry.isCompleted(fileId)) { // re-check after acquiring ownership
            return;
        }
        processFileIdempotently(file, fileId);
        registry.markCompleted(fileId, nodeId);
        moveToProcessedDirectory(file);
    } finally {
        zk.delete(lockPath, -1);
    }
}
```

The re-check after acquiring ownership covers the case where another node finished the file between our first check and our `create`.
