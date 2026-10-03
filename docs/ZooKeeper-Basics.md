# ZooKeeper Basics

Only the concepts this service actually uses.

## Znodes

ZooKeeper stores data in a hierarchical namespace, like a file system. Each entry is a **znode**, which has a path and a small amount of data.

```
/
└── processing
    ├── a94f23...   data: "nodeId=node-A"
    ├── c83291...   data: "nodeId=node-B"
    └── d921ab...   data: "nodeId=node-C"
```

## Creating a znode is atomic

`create(path, ...)` either succeeds or fails with `NodeExistsException`. If three clients race to create the same path, **exactly one wins**. That is the foundation of our per-file lock.

## Sessions

Each client (our worker node) connects to ZooKeeper with a **session** kept alive by heartbeats. If the node crashes or is partitioned away, heartbeats stop and the session **expires** after the session timeout.

## Ephemeral vs. persistent znodes

| Type | Lifetime | Use in this service |
|------|----------|---------------------|
| **Persistent** | Until explicitly deleted | Parent path like `/processing` |
| **Ephemeral** | Deleted automatically when the creating session ends | Per-file ownership: `/processing/<fileId>` |

Ephemeral nodes are why a crashed worker never holds a file forever.

## Ensemble

In production ZooKeeper runs as an **ensemble** (typically 3 or 5 servers). Writes go through a leader and are committed by a majority (quorum), so the service tolerates the loss of a minority of servers.

## Apache Curator

[Curator](https://curator.apache.org/) is a client library on top of ZooKeeper that handles connection retries, session re-establishment and provides recipes such as `InterProcessMutex`. See [Design Decisions](Design-Decisions.md).
