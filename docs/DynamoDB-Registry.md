# DynamoDB Registry

The [Completion Registry](Completion-Registry.md) is implemented as an **AWS DynamoDB** table.
Code: [`DynamoDbFileRegistry.java`](../src/main/java/com/karankotz/filemonitor/DynamoDbFileRegistry.java)

## Why DynamoDB

- **Durable and managed.** It survives crashes of any worker, and there are no database servers to run.
- **Conditional writes.** Every state change can say "only if…", which gives us fencing for free.
- **Strongly consistent reads** (`ConsistentRead=true`), so a worker always sees the latest status.
- **On-demand billing** (`PAY_PER_REQUEST`): a demo table costs close to nothing.

## Table

| Attribute | Type | Meaning |
|-----------|------|---------|
| `fileId` (partition key) | S | SHA-256 of the file's absolute path |
| `fileName` | S | Original file name |
| `status` | S | `PROCESSING`, `COMPLETED` or `FAILED` |
| `workerId` | S | Node that owns the current/last attempt |
| `attempts` | N | How many times processing started |
| `updatedAt` | S | ISO-8601 timestamp |
| `lineCount` | N | The processing result (demo: number of lines) |
| `error` | S | Last failure message, if `FAILED` |

The worker creates the table on startup if it does not exist (`ensureTable()`).

## The three writes

### 1. `markProcessing`: start an attempt

```
SET status = PROCESSING, workerId = :me, attempts = attempts + 1, ...
CONDITION attribute_not_exists(fileId) OR status <> COMPLETED
```

If the condition fails, the file was completed by someone else in the meantime, so the worker skips it.

### 2. `markCompleted`: store result and finish, as one fenced write

```
SET status = COMPLETED, lineCount = :lines, ...
CONDITION status = PROCESSING AND workerId = :me
```

The result and the COMPLETED status are written **atomically in the same item update**. So there is no window where the result is saved but the status isn't.

`workerId = :me` is a **fence**. Suppose node-a stalls (GC pause, network partition), its ZooKeeper session expires, and node-b takes over the file (`markProcessing` sets `workerId = node-b`). When node-a wakes up and tries to commit, DynamoDB rejects the write. node-a's stale result never overwrites node-b's.

### 3. `markFailed`: record an error

Fenced the same way. The file stays in the inbox and is retried on the next rescan, until `attempts >= MAX_ATTEMPTS`; then it is moved to `failed/`.

## Example

```
fileName             status      worker     attempts lines
orders-143148-1.csv  COMPLETED   node-2     1        33
orders-143148-4.csv  COMPLETED   node-2     2        37    <- node-1 was killed mid-file
orders-143148-6.csv  COMPLETED   node-3     2        13    <- node-1 was killed mid-file
```

## Running against real AWS

Leave `DYNAMODB_ENDPOINT` unset. The AWS SDK then uses the default credential chain (env vars, `~/.aws/credentials`, or an IAM role on EC2/ECS/EKS).

```bash
export AWS_REGION=us-east-1
export REGISTRY_TABLE=FileRegistry
unset DYNAMODB_ENDPOINT
java -jar target/file-monitor.jar worker
```

Minimum IAM permissions for the worker:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "dynamodb:DescribeTable", "dynamodb:CreateTable",
      "dynamodb:GetItem", "dynamodb:UpdateItem", "dynamodb:Scan"
    ],
    "Resource": "arn:aws:dynamodb:*:*:table/FileRegistry"
  }]
}
```

(Drop `CreateTable` if you create the table yourself. `Scan` is only used by the `status` command.)
