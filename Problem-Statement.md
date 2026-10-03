# Problem Statement

## Scenario

- A **shared folder** (e.g. NFS / shared volume) receives incoming files: `file1.csv`, `file2.csv`, ...
- The **file monitoring service** runs as **multiple nodes** (Node A, Node B, Node C) for throughput and high availability.
- Every node watches the same folder (for example with Java `WatchService`), so **every node is notified about every file**.

```
            Shared Folder
           | file1.txt
           | file2.txt
           | file3.txt
           |
  +--------+--------+
  |        |        |
Node A   Node B   Node C
```

## What goes wrong without coordination

All three nodes detect `file123.csv` at roughly the same time and all three process it:

- duplicate rows in the database,
- duplicate downstream events / emails / payments,
- wasted compute.

## Goals

1. **Mutual exclusion per file** — at most one node processes a given file at any moment.
2. **Parallelism across files** — different nodes should process *different* files concurrently.
3. **Automatic recovery** — if a node crashes mid-file, another node should be able to pick the file up.
4. **No re-processing of completed files** — a file that was already handled must not be handled again later.

## Why ZooKeeper

ZooKeeper is a distributed coordination service. It gives us exactly the primitives needed for goals 1–3:

- atomic "create if not exists" on a path → a natural **lock / ownership claim**,
- **ephemeral nodes** that disappear when the owner's session dies → **automatic recovery**,
- strong ordering and consistency across the ensemble.

Goal 4 needs something durable in addition — see [Completion Registry](Completion-Registry).
