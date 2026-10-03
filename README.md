# ZooKeeper File Monitor

A usage guide to **Apache ZooKeeper**, demonstrated with a working **file monitoring service**. Multiple worker nodes watch a shared folder. **ZooKeeper** makes sure no two nodes process the same file at the same time, and **AWS DynamoDB** stores the durable registry of which files are done.

```
ZooKeeper  (ephemeral znodes)    = "Who owns this file RIGHT NOW?"
DynamoDB   (conditional writes)  = "Has this file ALREADY been processed?" + fencing of stale workers
```

## Quick start (Java 21 + Maven, no Docker or AWS account needed)

```bash
scripts/local-infra.sh          # 3-server ZooKeeper ensemble + DynamoDB Local
scripts/worker.sh node-1        # in three more terminals
scripts/worker.sh node-2
scripts/worker.sh node-3
scripts/drop-files.sh 9         # drop sample files into data/inbox
scripts/status.sh               # see owners in ZooKeeper and states in DynamoDB
```

Then `kill -9` a worker partway through its work and watch the others take over its files. Details: [Running the Demo](docs/Running-the-Demo.md).

Tests (real ZooKeeper and DynamoDB Local): `mvn test`

## Code

| Class | Role |
|-------|------|
| [`FileMonitorNode`](src/main/java/com/karankotz/filemonitor/FileMonitorNode.java) | Watches the inbox, runs the claim → process → commit → release flow |
| [`OwnershipManager`](src/main/java/com/karankotz/filemonitor/OwnershipManager.java) | ZooKeeper (Curator): worker registration and per-file ephemeral claims |
| [`DynamoDbFileRegistry`](src/main/java/com/karankotz/filemonitor/DynamoDbFileRegistry.java) | DynamoDB registry with conditional, fenced writes |
| [`FileMonitorApp`](src/main/java/com/karankotz/filemonitor/FileMonitorApp.java) | `worker` and `status` commands |

## Wiki

Start at **[docs/Home.md](docs/Home.md)**.

- [Problem Statement](docs/Problem-Statement.md)
- [ZooKeeper Basics](docs/ZooKeeper-Basics.md)
- [Architecture](docs/Architecture.md)
- [File Ownership with Ephemeral Znodes](docs/File-Ownership-with-Ephemeral-Znodes.md)
- [Completion Registry](docs/Completion-Registry.md)
- [DynamoDB Registry](docs/DynamoDB-Registry.md)
- [Processing Algorithm](docs/Processing-Algorithm.md)
- [Failure Scenarios](docs/Failure-Scenarios.md)
- [Design Decisions](docs/Design-Decisions.md)
- [Running the Demo](docs/Running-the-Demo.md)
- [Progress and Next Steps](docs/Progress-and-Next-Steps.md)
