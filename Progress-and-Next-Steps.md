# Progress and Next Steps

## ✅ Done (design phase)

- Defined the problem: multiple nodes monitoring one shared folder → duplicate processing.
- Chose ZooKeeper as the coordination service.
- Designed **per-file ephemeral ownership znodes** (`/processing/<fileId>`).
- Identified the **crash-after-side-effect** duplicate window.
- Added a durable **completion registry** (`fileId`, `status`, `workerId`, `attempts`, `processedAt`).
- Settled the principle: *ZooKeeper = who owns it now, registry = already done?*
- Decided on deterministic `fileId` (SHA-256 of path), per-file locks over a global lock, and Curator as the client.

## ⏭️ Next steps

- [ ] Docker Compose setup: 3-node ZooKeeper ensemble + database.
- [ ] Java worker service: `WatchService` + Curator + ownership znode logic.
- [ ] Completion registry table and repository.
- [ ] Idempotent processing (upsert keyed by `fileId`).
- [ ] Demo script: start 3 workers, drop files, show each file processed once.
- [ ] Failure demo: kill a worker mid-file and show another worker take over.
- [ ] Stale/orphan handling: retry `FAILED` files, cap `attempts`.
