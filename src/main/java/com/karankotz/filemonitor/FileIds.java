package com.karankotz.filemonitor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic, ZooKeeper-path-safe file IDs. Every node sees the shared folder
 * at the same absolute path, so every node computes the same ID for a file.
 */
public final class FileIds {

    private FileIds() {
    }

    public static String of(Path file) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(file.toAbsolutePath().normalize().toString()
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
