# Completion Registry

> **Implementation:** this registry is an AWS DynamoDB table. See [DynamoDB Registry](DynamoDB-Registry.md).

## Why we need it

ZooKeeper ownership only prevents **simultaneous** processing. Once the ephemeral znode is gone (released or session expired), ZooKeeper has no memory that the file was ever handled. We need a **durable registry** of processing status.

## What it looks like

A database table (or any durable store):

```
FILE_PROCESSING
-------------------------------------------
file_id       status       worker_id
-------------------------------------------
fileA.csv     COMPLETED    node-1
fileB.csv     PROCESSING   node-2
fileC.csv     FAILED       node-3
```

Suggested columns: `file_id`, `status`, `worker_id`, `attempts`, `processed_at`.

## Two registries, two questions

| | ZooKeeper | Completion Registry |
|---|-----------|---------------------|
| Question | Who owns this file **right now**? | Has this file **already** been processed? |
| Lifetime | Temporary, tied to worker session | Durable, survives crashes |
| Example | `/processing/fileB.csv -> node-2` | `fileB.csv -> COMPLETED` |

```
ZooKeeper    = "Who owns this file right now?"
Registry DB  = "Has this file already been processed?"
```

That separation keeps the design clean: ZooKeeper does coordination, the database does record-keeping.

## Checking the registry

A node checks the registry **before** trying to claim a file, and records `COMPLETED` (ideally in the same transaction as the processing side effects) **before** releasing the ZooKeeper znode. See [Processing Algorithm](Processing-Algorithm.md).
