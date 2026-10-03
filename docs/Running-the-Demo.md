# Running the Demo

Everything runs on a laptop with **Java 21 and Maven**. No Docker and no AWS account are needed.

## 1. Start ZooKeeper + DynamoDB Local

```bash
scripts/local-infra.sh
```

This starts, in one JVM:

- a **3-server ZooKeeper ensemble** on `localhost:2181,2182,2183` (Curator's `TestingCluster`), and
- **DynamoDB Local** on `http://localhost:8000` (in-memory).

## 2. Start three workers (three terminals)

```bash
scripts/worker.sh node-1
scripts/worker.sh node-2
scripts/worker.sh node-3
```

Each worker waits `PROCESSING_DELAY_MS` (default 3000 ms) per file, so you have time to watch and to kill workers.

## 3. Drop files

```bash
scripts/drop-files.sh 9
```

## 4. Watch the state

```bash
scripts/status.sh
```

```
== ZooKeeper /file-monitor/workers (live workers)
  node-3
  node-1
  node-2
== ZooKeeper /file-monitor/processing (who owns what right now)
  ba9c633397b1...  -> nodeId=node-1,startTime=...
  05d46b79d0ed...  -> nodeId=node-2,startTime=...
  69f9d3cc1edd...  -> nodeId=node-3,startTime=...
  edc042a3fb5a...  -> nodeId=node-3,startTime=...
  a633c75c0d52...  -> nodeId=node-2,startTime=...
  8347c2dde1be...  -> nodeId=node-1,startTime=...
== DynamoDB FileRegistry (has it been processed?)
  fileName             status      worker     attempts lines
  orders-143111-1.csv  PROCESSING  node-1     1
  orders-143111-3.csv  PROCESSING  node-3     1
  ...
```

Each file has exactly one owner, and the three nodes work in parallel (2 threads each).

## 5. Kill a worker mid-file

Start a slow worker so the kill lands mid-processing:

```bash
PROCESSING_DELAY_MS=15000 scripts/worker.sh node-1
scripts/drop-files.sh 6
kill -9 <pid of node-1>        # a crash: no clean shutdown
```

What happened in a real run:

| Time | Event |
|------|-------|
| 14:31:50 | node-1 claims `orders-…-4.csv` and `orders-…-6.csv` |
| 14:31:52 | `kill -9` node-1 |
| 14:31:52–14:32:02 | node-1's znodes still exist: ZooKeeper waits for the 10 s session timeout |
| ~14:32:02 | Session expires, so ZooKeeper deletes `/workers/node-1` and both `/processing/...` claims |
| 14:32:05 | Next rescan: node-2 and node-3 claim the two files, **attempt 2** |

```
orders-143148-4.csv  COMPLETED   node-2     2        37
orders-143148-6.csv  COMPLETED   node-3     2        13
```

With **Ctrl+C** instead of `kill -9`, the worker closes its ZooKeeper session cleanly and its claims disappear **immediately**, without waiting for the timeout.

## Configuration

| Env var | Default | Meaning |
|---------|---------|---------|
| `NODE_ID` | `node-<pid>` | Worker name |
| `ZK_CONNECT` | `localhost:2181,localhost:2182,localhost:2183` | ZooKeeper ensemble |
| `ZK_SESSION_TIMEOUT_MS` | `10000` | How long before a dead worker's claims expire |
| `ZK_ROOT` | `/file-monitor` | Root znode |
| `DATA_DIR` | `data` | Base for `inbox/`, `processed/`, `failed/` |
| `INBOX_DIR` / `PROCESSED_DIR` / `FAILED_DIR` | under `DATA_DIR` | Override individually |
| `REGISTRY_TABLE` | `FileRegistry` | DynamoDB table |
| `AWS_REGION` | `us-east-1` | AWS region |
| `DYNAMODB_ENDPOINT` | *(unset = real AWS)* | e.g. `http://localhost:8000` for DynamoDB Local |
| `WORKER_THREADS` | `2` | Files processed in parallel per node |
| `RESCAN_INTERVAL_MS` | `5000` | Periodic inbox rescan |
| `PROCESSING_DELAY_MS` | `0` (`3000` in `worker.sh`) | Artificial delay for demos |
| `MAX_ATTEMPTS` | `3` | After this many failures, move file to `failed/` |

## Automated tests

```bash
mvn test
```

These run real ZooKeeper (in-process) and real DynamoDB Local over HTTP:

| Test | Proves |
|------|--------|
| `threeWorkersProcessEveryFileExactlyOnce` | 3 nodes, 30 files: each processed once, `attempts = 1` |
| `workerThatLosesItsSessionIsFencedAndAnotherWorkerTakesOver` | Stalled node loses its session, another node completes the file, and the stale node's commit is rejected |
| `registryFencesStaleWorkersAndRejectsCompletedFiles` | DynamoDB conditional writes |
| `ownershipAllowsExactlyOneClaimPerFile` | ZooKeeper claims; a stale release can't delete another node's claim |
