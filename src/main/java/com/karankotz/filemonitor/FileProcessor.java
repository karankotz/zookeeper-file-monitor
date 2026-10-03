package com.karankotz.filemonitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

/** The actual business work done on a file. Must be safe to repeat (idempotent). */
@FunctionalInterface
public interface FileProcessor {

    /** Process the file and return its line count, which is stored in the registry. */
    long process(Path file) throws Exception;

    /** Demo processor: counts lines, optionally sleeping so you have time to kill a worker. */
    static FileProcessor lineCounter(Duration delay) {
        return file -> {
            if (!delay.isZero()) {
                Thread.sleep(delay.toMillis());
            }
            try (Stream<String> lines = Files.lines(file)) {
                return lines.count();
            } catch (IOException e) {
                throw new IOException("Cannot read " + file, e);
            }
        };
    }
}
