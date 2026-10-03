# Progress and Next Steps

## ✅ Phase 1 — Design

- Defined the problem: multiple nodes monitoring one shared folder → duplicate processing.
- Chose ZooKeeper as the coordination service.
- Designed **per-file ephemeral ownership znodes** (`/processing/<fileId>`).
- Identified the **crash-after-side-effect** duplicate window.
- Added a durable **completion registry**.
- Settled the principle: *ZooKeeper = who owns it now, registry = already done?*

## ✅ Phase 2 — Implementation

- Java 21 worker using **Apache Curator** (`OwnershipManager`) and **AWS SDK v2** (`DynamoDbFileRegistry`).
- **AWS DynamoDB** as the registry, with conditional writes for idempotency and **fencing** of stale workers.
- `WatchService` plus a **periodic rescan** to find new files and files orphaned by crashed workers.
- Live worker registration under `/file-monitor/workers`.
- Retry with `MAX_ATTEMPTS`, then move to `failed/`.
- `status` command showing ZooKeeper state and the DynamoDB registry side by side.
- Local infrastructure without Docker: a 3-server ZooKeeper ensemble and DynamoDB Local in one JVM.
- Integration tests (real ZooKeeper and DynamoDB Local): exactly-once across 3 nodes, crash takeover, fencing, claim exclusivity.
- Demo verified: `kill -9` of a worker mid-file, its files completed by the survivors as attempt 2. See [Running the Demo](Running-the-Demo.md).

## ⏭️ Next steps

- [ ] Run against a real AWS DynamoDB table (instructions in [DynamoDB Registry](DynamoDB-Registry.md)).
- [ ] Docker Compose / Kubernetes deployment of a real ZooKeeper ensemble and the workers.
- [ ] Demo of killing a **ZooKeeper server** (not a worker) to show the ensemble keeps working with a quorum.
- [ ] Metrics: files processed, attempts, takeover count.
- [ ] Stuck-`PROCESSING` report: registry rows in PROCESSING with no live claim.
