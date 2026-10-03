package com.karankotz.filemonitor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live ownership of files, backed by ZooKeeper ephemeral znodes.
 *
 * <pre>
 * {zkRoot}/workers/{nodeId}        EPHEMERAL  - which workers are alive
 * {zkRoot}/processing/{fileId}     EPHEMERAL  - who owns which file right now
 * </pre>
 *
 * Because both are ephemeral, ZooKeeper removes them when a worker's session ends
 * (clean shutdown or crash + session timeout), so a dead worker never holds a file.
 */
public class OwnershipManager {

    private static final Logger log = LoggerFactory.getLogger(OwnershipManager.class);

    private final CuratorFramework client;
    private final String nodeId;
    private final String workersPath;
    private final String processingPath;

    public OwnershipManager(CuratorFramework client, String zkRoot, String nodeId) {
        this.client = client;
        this.nodeId = nodeId;
        this.workersPath = zkRoot + "/workers";
        this.processingPath = zkRoot + "/processing";
    }

    /** Announce this worker as alive. The znode disappears when the session ends. */
    public void registerWorker() throws Exception {
        String path = workersPath + "/" + nodeId;
        try {
            client.create().creatingParentContainersIfNeeded()
                    .withMode(CreateMode.EPHEMERAL)
                    .forPath(path, describe().getBytes(StandardCharsets.UTF_8));
        } catch (KeeperException.NodeExistsException e) {
            if (!isOwnedByCurrentSession(path)) {
                throw new IllegalStateException("Another live worker is already registered as " + nodeId, e);
            }
        }
        log.info("Registered worker {} at {}", nodeId, path);
    }

    /**
     * Try to become the owner of a file. Exactly one concurrent caller succeeds,
     * because ZooKeeper's create is atomic: everyone else gets NodeExistsException.
     */
    public Optional<Claim> tryClaim(String fileId) throws Exception {
        String path = processingPath + "/" + fileId;
        try {
            client.create().creatingParentContainersIfNeeded()
                    .withMode(CreateMode.EPHEMERAL)
                    .forPath(path, describe().getBytes(StandardCharsets.UTF_8));
            return Optional.of(new Claim(fileId, path, currentSessionId()));
        } catch (KeeperException.NodeExistsException e) {
            // Curator retries a create after a connection blip; the first attempt may
            // already have succeeded. If the znode belongs to our session, it is ours.
            if (isOwnedByCurrentSession(path)) {
                return Optional.of(new Claim(fileId, path, currentSessionId()));
            }
            return Optional.empty();
        }
    }

    /** True if this worker's ZooKeeper session still holds the claim. */
    public boolean stillOwns(Claim claim) {
        try {
            Stat stat = client.checkExists().forPath(claim.path());
            return stat != null && stat.getEphemeralOwner() == claim.sessionId();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Release a claim, but only if it is still ours. If our session expired while we
     * were working, another worker may now own a znode at the same path: never delete it.
     */
    public void release(Claim claim) {
        try {
            Stat stat = client.checkExists().forPath(claim.path());
            if (stat != null && stat.getEphemeralOwner() == claim.sessionId()) {
                client.delete().withVersion(stat.getVersion()).forPath(claim.path());
            }
        } catch (KeeperException.NoNodeException | KeeperException.BadVersionException e) {
            // Already gone or replaced by another owner: nothing to release.
        } catch (Exception e) {
            log.warn("Could not release claim {}: {}", claim.path(), e.toString());
        }
    }

    public List<String> liveWorkers() throws Exception {
        return childrenOf(workersPath);
    }

    /** fileId -> owner description, for the status command. */
    public List<String> currentClaims() throws Exception {
        return childrenOf(processingPath).stream()
                .map(fileId -> fileId + " -> " + dataOf(processingPath + "/" + fileId))
                .toList();
    }

    private List<String> childrenOf(String path) throws Exception {
        try {
            return client.getChildren().forPath(path);
        } catch (KeeperException.NoNodeException e) {
            return List.of();
        }
    }

    private String dataOf(String path) {
        try {
            return new String(client.getData().forPath(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(released)";
        }
    }

    private boolean isOwnedByCurrentSession(String path) throws Exception {
        Stat stat = client.checkExists().forPath(path);
        return stat != null && stat.getEphemeralOwner() == currentSessionId();
    }

    private long currentSessionId() throws Exception {
        return client.getZookeeperClient().getZooKeeper().getSessionId();
    }

    private String describe() {
        return "nodeId=" + nodeId + ",startTime=" + Instant.now();
    }

    public record Claim(String fileId, String path, long sessionId) {
    }
}
