# ZooKeeper File Monitoring Service — Wiki

This wiki explains **how Apache ZooKeeper works** by walking through a concrete use case: a **file monitoring service** where several service nodes watch the same shared folder, and ZooKeeper makes sure **no two nodes process the same file**.

## The problem in one sentence

> Multiple nodes monitor a shared folder. Every node sees every new file. Without coordination, the same file gets processed several times.

## The solution in one sentence

> Before processing a file, a node must win ownership of it in ZooKeeper (by creating an *ephemeral znode*), and a durable *completion registry* records which files are already done.

## Pages

| Page | What you'll learn |
|------|-------------------|
| [Problem Statement](Problem-Statement) | The duplicate-processing problem and the goals of the service |
| [ZooKeeper Basics](ZooKeeper-Basics) | Znodes, sessions, ephemeral nodes, ensembles — the concepts this design relies on |
| [Architecture](Architecture) | How the shared folder, worker nodes, ZooKeeper and the registry fit together |
| [File Ownership with Ephemeral Znodes](File-Ownership-with-Ephemeral-Znodes) | The per-file lock: how only one node wins |
| [Completion Registry](Completion-Registry) | Why ZooKeeper alone is not enough, and the durable "already processed?" record |
| [Processing Algorithm](Processing-Algorithm) | Step-by-step flow every node runs, with Java code |
| [Failure Scenarios](Failure-Scenarios) | Node crashes, the duplicate window, and how idempotency closes it |
| [Design Decisions](Design-Decisions) | Per-file vs. global lock, raw ZooKeeper vs. Curator, znode naming |
| [Progress and Next Steps](Progress-and-Next-Steps) | What is done so far and what's planned |

## Key takeaway

```
ZooKeeper          = "Who owns this file RIGHT NOW?"   (live, session-bound)
Completion Registry = "Has this file ALREADY been processed?" (durable)
Idempotency        = protects the tiny window between doing the work and recording it
```
