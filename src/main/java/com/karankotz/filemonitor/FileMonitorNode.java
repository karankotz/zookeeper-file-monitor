package com.karankotz.filemonitor;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.karankotz.filemonitor.OwnershipManager.Claim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One worker node. Every node watches the same inbox; ZooKeeper decides who processes
 * each file, DynamoDB remembers which files are done.
 *
 * <pre>
 * file seen -> registry COMPLETED? -> claim /processing/{fileId} in ZooKeeper
 *           -> re-check registry -> markProcessing -> process
 *           -> markCompleted (fenced) -> move to processed/ -> release claim
 * </pre>
 */
public class FileMonitorNode implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileMonitorNode.class);

    private final Config config;
    private final OwnershipManager ownership;
    private final DynamoDbFileRegistry registry;
    private final FileProcessor processor;

    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private WatchService watchService;
    private Thread watchThread;

    public FileMonitorNode(Config config, OwnershipManager ownership,
                           DynamoDbFileRegistry registry, FileProcessor processor) {
        this.config = config;
        this.ownership = ownership;
        this.registry = registry;
        this.processor = processor;
        this.workers = Executors.newFixedThreadPool(config.workerThreads(),
                Thread.ofPlatform().name(config.nodeId() + "-worker-", 0).factory());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name(config.nodeId() + "-rescan").daemon().factory());
    }

    public void start() throws Exception {
        Files.createDirectories(config.inboxDir());
        Files.createDirectories(config.processedDir());
        Files.createDirectories(config.failedDir());
        ownership.registerWorker();

        watchService = config.inboxDir().getFileSystem().newWatchService();
        config.inboxDir().register(watchService, ENTRY_CREATE, ENTRY_MODIFY);
        watchThread = Thread.ofPlatform().name(config.nodeId() + "-watcher").daemon().start(this::watchLoop);

        // WatchService only reports new events. The periodic rescan picks up files that
        // were already there at startup, events we missed, and files whose owner crashed.
        scheduler.scheduleWithFixedDelay(this::rescan, 0,
                config.rescanInterval().toMillis(), TimeUnit.MILLISECONDS);
        log.info("Worker {} watching {}", config.nodeId(), config.inboxDir());
    }

    private void watchLoop() {
        try {
            while (true) {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == OVERFLOW) {
                        rescan();
                    } else {
                        submit(config.inboxDir().resolve((Path) event.context()));
                    }
                }
                if (!key.reset()) {
                    log.warn("Inbox {} is no longer watchable", config.inboxDir());
                    return;
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            // shutting down
        }
    }

    void rescan() {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(config.inboxDir())) {
            files.forEach(this::submit);
        } catch (IOException e) {
            log.warn("Rescan of {} failed: {}", config.inboxDir(), e.toString());
        }
    }

    private void submit(Path file) {
        if (!isCandidate(file)) {
            return;
        }
        String fileId = FileIds.of(file);
        // Don't queue the same file twice inside this node; other nodes are ZooKeeper's job.
        if (inFlight.add(fileId)) {
            try {
                workers.submit(() -> {
                    try {
                        handle(file, fileId);
                    } finally {
                        inFlight.remove(fileId);
                    }
                });
            } catch (RuntimeException e) {
                inFlight.remove(fileId);
            }
        }
    }

    /** Writers should create files as "name.tmp" or ".name" and rename when complete. */
    private static boolean isCandidate(Path file) {
        String name = file.getFileName().toString();
        return !name.startsWith(".") && !name.endsWith(".tmp") && Files.isRegularFile(file);
    }

    void handle(Path file, String fileId) {
        String name = file.getFileName().toString();
        try {
            // 1. Cheap check first: already done? (e.g. crashed after completing, before moving)
            if (registry.isCompleted(fileId)) {
                moveQuietly(file, config.processedDir());
                return;
            }

            // 2. Become the owner, or skip: another node is working on it.
            Optional<Claim> maybeClaim = ownership.tryClaim(fileId);
            if (maybeClaim.isEmpty()) {
                log.debug("{} skipped: owned by another worker", name);
                return;
            }
            Claim claim = maybeClaim.get();
            try {
                process(file, fileId, name, claim);
            } finally {
                ownership.release(claim);
            }
        } catch (Exception e) {
            log.error("{} error handling {}: {}", config.nodeId(), name, e.toString());
        }
    }

    private void process(Path file, String fileId, String name, Claim claim) throws Exception {
        if (!Files.exists(file)) {
            return; // another worker finished and moved it between our scan and our claim
        }

        // 3. Record PROCESSING. Empty means someone completed it after our first check.
        Optional<FileRecord> started = registry.markProcessing(fileId, name, config.nodeId());
        if (started.isEmpty()) {
            moveQuietly(file, config.processedDir());
            return;
        }
        int attempt = started.get().attempts();
        log.info("{} PROCESSING {} (attempt {})", config.nodeId(), name, attempt);

        long lines;
        try {
            lines = processor.process(file);
        } catch (Exception e) {
            log.warn("{} FAILED {}: {}", config.nodeId(), name, e.toString());
            registry.markFailed(fileId, config.nodeId(), e.toString());
            if (attempt >= config.maxAttempts()) {
                moveQuietly(file, config.failedDir());
            }
            return;
        }

        // 4. Commit result + COMPLETED in one fenced write. If we lost our ZooKeeper
        //    session meanwhile and another worker took over, DynamoDB rejects this.
        if (!registry.markCompleted(fileId, config.nodeId(), lines)) {
            log.warn("{} lost ownership of {} while processing; result discarded", config.nodeId(), name);
            return;
        }

        // 5. Archive the input. The claim is released by the caller.
        moveQuietly(file, config.processedDir());
        log.info("{} COMPLETED {} ({} lines)", config.nodeId(), name, lines);
    }

    private void moveQuietly(Path file, Path targetDir) {
        Path target = targetDir.resolve(file.getFileName());
        try {
            try {
                Files.move(file, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (NoSuchFileException e) {
            // Already moved by another worker.
        } catch (IOException e) {
            log.warn("Could not move {} to {}: {}", file, targetDir, e.toString());
        }
    }

    @Override
    public void close() throws Exception {
        scheduler.shutdownNow();
        if (watchService != null) {
            watchService.close();
        }
        if (watchThread != null) {
            watchThread.interrupt();
        }
        workers.shutdown();
        if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
            workers.shutdownNow();
        }
    }
}
