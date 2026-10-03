package com.karankotz.filemonitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.amazonaws.services.dynamodbv2.local.server.DynamoDBProxyServer;
import com.karankotz.filemonitor.FileRecord.FileStatus;
import com.karankotz.filemonitor.dev.LocalInfra;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.test.TestingServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Real ZooKeeper (in-process) + real DynamoDB Local over HTTP. */
class FileMonitorIntegrationTest {

    private static TestingServer zookeeper;
    private static DynamoDBProxyServer dynamoServer;
    private static String dynamoEndpoint;

    @TempDir
    Path dataDir;

    private String table;
    private DynamoDbClient dynamo;
    private DynamoDbFileRegistry registry;
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @BeforeAll
    static void startInfra() throws Exception {
        zookeeper = new TestingServer(true);
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        dynamoServer = LocalInfra.startDynamoDbLocal(port);
        dynamoEndpoint = "http://localhost:" + port;
    }

    @AfterAll
    static void stopInfra() throws Exception {
        dynamoServer.stop();
        zookeeper.close();
    }

    @BeforeEach
    void setUp() {
        table = "FileRegistry-" + UUID.randomUUID();
        dynamo = DynamoDbFileRegistry.createClient(config("setup"));
        registry = new DynamoDbFileRegistry(dynamo, table);
        registry.ensureTable();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : toClose.reversed()) {
            try {
                c.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
        dynamo.close();
    }

    @Test
    void threeWorkersProcessEveryFileExactlyOnce() throws Exception {
        Map<String, AtomicInteger> processedCount = new ConcurrentHashMap<>();
        FileProcessor counting = file -> {
            processedCount.computeIfAbsent(file.getFileName().toString(), k -> new AtomicInteger())
                    .incrementAndGet();
            Thread.sleep(20);
            return Files.readAllLines(file).size();
        };
        for (String node : List.of("node-1", "node-2", "node-3")) {
            startNode(node, counting);
        }

        int files = 30;
        for (int i = 0; i < files; i++) {
            dropFile("file-" + i + ".csv", "a\nb\nc\n");
        }

        Path inbox = config("x").inboxDir();
        await(() -> registry.findAll().stream().filter(r -> r.status() == FileStatus.COMPLETED).count() == files
                && isEmpty(inbox), Duration.ofSeconds(30));

        assertEquals(files, processedCount.size());
        processedCount.forEach((name, count) -> assertEquals(1, count.get(), name + " processed more than once"));
        for (FileRecord r : registry.findAll()) {
            assertEquals(1, r.attempts(), r.fileName());
            assertEquals(3L, r.lineCount(), r.fileName());
        }
        try (var processed = Files.list(config("x").processedDir())) {
            assertEquals(files, processed.count());
        }
    }

    @Test
    void workerThatLosesItsSessionIsFencedAndAnotherWorkerTakesOver() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        AtomicInteger aCalls = new AtomicInteger();
        FileProcessor stuck = file -> {
            aCalls.incrementAndGet();
            slowStarted.countDown();
            releaseSlow.await();
            return 99;
        };
        NodeHandle a = startNode("node-a", stuck);
        String fileId = FileIds.of(dropFile("slow.csv", "1\n2\n"));

        assertTrue(slowStarted.await(10, TimeUnit.SECONDS), "node-a never started processing");
        assertEquals("node-a", registry.get(fileId).orElseThrow().workerId());

        // node-a's ZooKeeper session ends (crash / partition): its ephemeral claim disappears.
        a.curator().close();

        AtomicInteger bCalls = new AtomicInteger();
        startNode("node-b", file -> {
            bCalls.incrementAndGet();
            return Files.readAllLines(file).size();
        });
        await(() -> registry.isCompleted(fileId), Duration.ofSeconds(15));

        // node-a wakes up and tries to commit: DynamoDB's "workerId = me" condition rejects it.
        releaseSlow.countDown();
        Thread.sleep(500);

        FileRecord record = registry.get(fileId).orElseThrow();
        assertEquals(FileStatus.COMPLETED, record.status());
        assertEquals("node-b", record.workerId());
        assertEquals(2, record.attempts());
        assertEquals(2L, record.lineCount(), "node-a's stale result must not overwrite node-b's");
        assertEquals(1, aCalls.get());
        assertEquals(1, bCalls.get());
        assertTrue(Files.exists(config("x").processedDir().resolve("slow.csv")));
    }

    @Test
    void registryFencesStaleWorkersAndRejectsCompletedFiles() {
        String fileId = "abc";
        assertTrue(registry.markProcessing(fileId, "f.csv", "node-a").isPresent());
        assertEquals(2, registry.markProcessing(fileId, "f.csv", "node-b").orElseThrow().attempts());

        assertFalse(registry.markCompleted(fileId, "node-a", 1), "stale owner must be fenced");
        assertTrue(registry.markCompleted(fileId, "node-b", 2));

        assertTrue(registry.markProcessing(fileId, "f.csv", "node-c").isEmpty(), "completed file must not restart");
        assertTrue(registry.isCompleted(fileId));
    }

    @Test
    void ownershipAllowsExactlyOneClaimPerFile() throws Exception {
        CuratorFramework c1 = curator();
        CuratorFramework c2 = curator();
        String root = "/test-" + UUID.randomUUID();
        OwnershipManager a = new OwnershipManager(c1, root, "a");
        OwnershipManager b = new OwnershipManager(c2, root, "b");

        var claim = a.tryClaim("file-1").orElseThrow();
        assertTrue(b.tryClaim("file-1").isEmpty(), "second worker must not get the same file");
        assertTrue(a.tryClaim("file-1").isPresent(), "re-claim by the same session is idempotent");

        a.release(claim);
        assertTrue(b.tryClaim("file-1").isPresent(), "file is claimable again after release");
        a.release(claim); // must not delete b's claim
        assertEquals(1, b.currentClaims().size());
    }

    // ---- helpers ----

    private record NodeHandle(FileMonitorNode node, CuratorFramework curator) {
    }

    private NodeHandle startNode(String nodeId, FileProcessor processor) throws Exception {
        Config cfg = config(nodeId);
        CuratorFramework curator = curator();
        FileMonitorNode node = new FileMonitorNode(cfg,
                new OwnershipManager(curator, cfg.zkRoot(), nodeId), registry, processor);
        toClose.add(node);
        node.start();
        return new NodeHandle(node, curator);
    }

    private CuratorFramework curator() throws InterruptedException {
        CuratorFramework curator = FileMonitorApp.newCurator(config("c"));
        curator.start();
        curator.blockUntilConnected();
        toClose.add(curator);
        return curator;
    }

    private Config config(String nodeId) {
        return Config.fromMap(Map.of(
                "NODE_ID", nodeId,
                "ZK_CONNECT", zookeeper.getConnectString(),
                "ZK_SESSION_TIMEOUT_MS", "4000",
                "ZK_ROOT", "/fm-" + Integer.toHexString(System.identityHashCode(this)),
                "DATA_DIR", dataDir.toString(),
                "REGISTRY_TABLE", table == null ? "unused" : table,
                "DYNAMODB_ENDPOINT", dynamoEndpoint,
                "WORKER_THREADS", "2",
                "RESCAN_INTERVAL_MS", "200"));
    }

    /** Write under a temporary name, then rename, so workers never see a half-written file. */
    private Path dropFile(String name, String content) throws Exception {
        Path inbox = config("x").inboxDir();
        Files.createDirectories(inbox);
        Path tmp = inbox.resolve(name + ".tmp");
        Files.writeString(tmp, content);
        return Files.move(tmp, inbox.resolve(name));
    }

    private static boolean isEmpty(Path dir) {
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            Thread.sleep(100);
        }
    }
}
