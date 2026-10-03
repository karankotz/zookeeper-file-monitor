package com.karankotz.filemonitor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Worker configuration, read from environment variables so the same jar runs
 * locally (DynamoDB Local) and on AWS (real DynamoDB).
 */
public record Config(
        String nodeId,
        String zkConnect,
        Duration zkSessionTimeout,
        String zkRoot,
        Path inboxDir,
        Path processedDir,
        Path failedDir,
        String registryTable,
        String awsRegion,
        String dynamoDbEndpoint,
        int workerThreads,
        Duration rescanInterval,
        Duration processingDelay,
        int maxAttempts) {

    public static Config fromEnv() {
        return fromMap(System.getenv());
    }

    static Config fromMap(Map<String, String> env) {
        Path dataDir = Path.of(env.getOrDefault("DATA_DIR", "data")).toAbsolutePath();
        return new Config(
                env.getOrDefault("NODE_ID", "node-" + ProcessHandle.current().pid()),
                env.getOrDefault("ZK_CONNECT", "localhost:2181,localhost:2182,localhost:2183"),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("ZK_SESSION_TIMEOUT_MS", "10000"))),
                env.getOrDefault("ZK_ROOT", "/file-monitor"),
                Path.of(env.getOrDefault("INBOX_DIR", dataDir.resolve("inbox").toString())).toAbsolutePath(),
                Path.of(env.getOrDefault("PROCESSED_DIR", dataDir.resolve("processed").toString())).toAbsolutePath(),
                Path.of(env.getOrDefault("FAILED_DIR", dataDir.resolve("failed").toString())).toAbsolutePath(),
                env.getOrDefault("REGISTRY_TABLE", "FileRegistry"),
                env.getOrDefault("AWS_REGION", "us-east-1"),
                emptyToNull(env.getOrDefault("DYNAMODB_ENDPOINT", "")),
                Integer.parseInt(env.getOrDefault("WORKER_THREADS", "2")),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("RESCAN_INTERVAL_MS", "5000"))),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("PROCESSING_DELAY_MS", "0"))),
                Integer.parseInt(env.getOrDefault("MAX_ATTEMPTS", "3")));
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
