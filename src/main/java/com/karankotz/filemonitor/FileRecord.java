package com.karankotz.filemonitor;

/** One row of the completion registry. */
public record FileRecord(
        String fileId,
        String fileName,
        FileStatus status,
        String workerId,
        int attempts,
        String updatedAt,
        Long lineCount,
        String error) {

    public enum FileStatus {
        PROCESSING, COMPLETED, FAILED
    }
}
