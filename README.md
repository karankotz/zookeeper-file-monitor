# ZooKeeper File Monitor

A usage guide to **Apache ZooKeeper**, demonstrated with a **file monitoring service**: multiple service nodes watch a shared folder, and ZooKeeper makes sure no two nodes process the same file.

```
ZooKeeper           = "Who owns this file RIGHT NOW?"        (live, session-bound)
Completion Registry = "Has this file ALREADY been processed?" (durable)
Idempotency         = protects the window between doing the work and recording it
```

## Wiki

Start at **[docs/Home.md](docs/Home.md)**.

- [Problem Statement](docs/Problem-Statement.md)
- [ZooKeeper Basics](docs/ZooKeeper-Basics.md)
- [Architecture](docs/Architecture.md)
- [File Ownership with Ephemeral Znodes](docs/File-Ownership-with-Ephemeral-Znodes.md)
- [Completion Registry](docs/Completion-Registry.md)
- [Processing Algorithm](docs/Processing-Algorithm.md)
- [Failure Scenarios](docs/Failure-Scenarios.md)
- [Design Decisions](docs/Design-Decisions.md)
- [Progress and Next Steps](docs/Progress-and-Next-Steps.md)

## Status

Design phase complete. Implementation (Java + Curator + Docker Compose ZooKeeper ensemble) is next — see [Progress and Next Steps](docs/Progress-and-Next-Steps.md).
