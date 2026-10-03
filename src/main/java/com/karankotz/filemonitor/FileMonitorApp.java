package com.karankotz.filemonitor;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Entry point.
 *
 * <pre>
 * java -jar target/file-monitor.jar worker   # run a worker node
 * java -jar target/file-monitor.jar status   # show live workers, claims and the registry
 * </pre>
 */
public class FileMonitorApp {

    private static final Logger log = LoggerFactory.getLogger(FileMonitorApp.class);

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "worker" : args[0];
        Config config = Config.fromEnv();
        switch (command) {
            case "worker" -> runWorker(config);
            case "status" -> printStatus(config);
            default -> {
                System.err.println("Usage: file-monitor [worker|status]");
                System.exit(2);
            }
        }
    }

    static CuratorFramework newCurator(Config config) {
        return CuratorFrameworkFactory.builder()
                .connectString(config.zkConnect())
                .sessionTimeoutMs((int) config.zkSessionTimeout().toMillis())
                .connectionTimeoutMs(5_000)
                .retryPolicy(new ExponentialBackoffRetry(500, 5))
                .build();
    }

    private static void runWorker(Config config) throws Exception {
        CuratorFramework curator = newCurator(config);
        curator.getConnectionStateListenable().addListener((client, state) -> {
            if (state == ConnectionState.LOST) {
                log.warn("ZooKeeper session LOST: all claims of {} are gone; in-flight results will be fenced",
                        config.nodeId());
            } else {
                log.info("ZooKeeper connection {}", state);
            }
        });
        curator.start();
        curator.blockUntilConnected();

        DynamoDbClient dynamo = DynamoDbFileRegistry.createClient(config);
        DynamoDbFileRegistry registry = new DynamoDbFileRegistry(dynamo, config.registryTable());
        registry.ensureTable();

        OwnershipManager ownership = new OwnershipManager(curator, config.zkRoot(), config.nodeId());
        FileMonitorNode node = new FileMonitorNode(config, ownership, registry,
                FileProcessor.lineCounter(config.processingDelay()));

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down {}", config.nodeId());
            try {
                node.close();
            } catch (Exception e) {
                log.warn("Error during shutdown", e);
            }
            curator.close(); // ends the session: ZooKeeper deletes our ephemeral znodes now
            dynamo.close();
            stopped.countDown();
        }));

        node.start();
        stopped.await();
    }

    private static void printStatus(Config config) throws Exception {
        try (CuratorFramework curator = newCurator(config);
             DynamoDbClient dynamo = DynamoDbFileRegistry.createClient(config)) {
            curator.start();
            curator.blockUntilConnected();
            OwnershipManager ownership = new OwnershipManager(curator, config.zkRoot(), "status");
            DynamoDbFileRegistry registry = new DynamoDbFileRegistry(dynamo, config.registryTable());

            System.out.println("== ZooKeeper " + config.zkRoot() + "/workers (live workers)");
            ownership.liveWorkers().forEach(w -> System.out.println("  " + w));
            System.out.println("== ZooKeeper " + config.zkRoot() + "/processing (who owns what right now)");
            List<String> claims = ownership.currentClaims();
            if (claims.isEmpty()) {
                System.out.println("  (none)");
            }
            claims.forEach(c -> System.out.println("  " + c.substring(0, 12) + "... " + c.substring(64)));

            System.out.println("== DynamoDB " + config.registryTable() + " (has it been processed?)");
            System.out.printf("  %-20s %-11s %-10s %-8s %s%n", "fileName", "status", "worker", "attempts", "lines");
            registry.findAll().stream()
                    .sorted(Comparator.comparing(FileRecord::fileName))
                    .forEach(r -> System.out.printf("  %-20s %-11s %-10s %-8d %s%n",
                            r.fileName(), r.status(), r.workerId(), r.attempts(),
                            r.lineCount() == null ? "" : r.lineCount()));
        }
    }
}
