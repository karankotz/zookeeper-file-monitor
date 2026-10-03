# Architecture

```mermaid
flowchart TB
    FS[(Shared File System)]
    W1[Worker 1]
    W2[Worker 2]
    W3[Worker 3]
    ZK{{ZooKeeper Ensemble<br/>/processing/&lt;fileId&gt;}}
    DB[(Completion Registry DB)]

    FS --> W1 & W2 & W3
    W1 & W2 & W3 <--> ZK
    W1 & W2 & W3 --> DB
```

ASCII version:

```
           Shared File System
                  |
       +----------+----------+
       |          |          |
   Worker 1   Worker 2   Worker 3
       |          |          |
       +----------+----------+
                  |
          ZooKeeper Ensemble
                  |
       /processing/<fileId>        <- live ownership
                  |
                  v
        Processing Database         <- durable completion registry
        +--------------------+
        | fileId             |
        | status             |
        | workerId           |
        | attempts           |
        | processedAt        |
        +--------------------+
```

## Responsibilities

| Component | Responsibility |
|-----------|----------------|
| **Shared folder** | Source of incoming files, visible to all workers |
| **Worker node** | Watches the folder, claims files, processes them |
| **ZooKeeper** | Answers *"who owns this file right now?"* — temporary, tied to worker sessions |
| **Completion registry** | Answers *"has this file already been processed?"* — durable, survives crashes |

## Znode layout

```
/processing                     (persistent)
    /<fileId>                   (ephemeral)  data: nodeId, hostname, startTime
```

Example:

```
/processing
    /fileA -> node-1
    /fileB -> node-3
    /fileC -> node-2
```
