package fw.store;

import fw.json.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Durable storage with three physically separated areas, as required:
 *
 * <ul>
 *   <li>{@code raw/} - imported, signed, immutable inputs (family manifests and
 *       firmware metadata; written once, never mutated in place);</li>
 *   <li>{@code derived/} - computed results (plans, rollout snapshots,
 *       exports) plus the current catalog/device state;</li>
 *   <li>{@code events/} - append-only operation log and the request ledger.</li>
 * </ul>
 *
 * <p>All record writes go through {@link #atomicWrite(Path, String)} (temp file
 * + fsync + atomic rename), so a process killed mid-write can never expose a
 * half-written JSON document. The event log is length-prefixed framed; a torn
 * tail line is discarded on recovery rather than parsed.</p>
 */
public final class Store {

    public static final String DIR_RAW = "raw";
    public static final String DIR_DERIVED = "derived";
    public static final String DIR_EVENTS = "events";

    private final Path root;
    private final Path raw;
    private final Path derived;
    private final Path events;

    public Store(Path root) {
        this.root = root;
        this.raw = root.resolve(DIR_RAW);
        this.derived = root.resolve(DIR_DERIVED);
        this.events = root.resolve(DIR_EVENTS);
        for (Path p : List.of(raw, derived, events,
                raw.resolve("families"), raw.resolve("packages"),
                derived.resolve("plans"), derived.resolve("rollouts"),
                derived.resolve("exports"), derived.resolve("devices"),
                derived.resolve("snapshots"))) {
            try {
                Files.createDirectories(p);
            } catch (IOException ex) {
                throw new UncheckedIOException("cannot create " + p, ex);
            }
        }
    }

    public Path root() {
        return root;
    }

    public Path rawDir() {
        return raw;
    }

    public Path derivedDir() {
        return derived;
    }

    // ------------------------------------------------------------ atomic write

    /** Writes JSON durably and atomically. The rename is the commit point. */
    public void atomicWrite(Path target, String content) {
        try {
            Path parent = target.getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, ".tmp-" + target.getFileName() + "-", ".json");
            Files.writeString(tmp, content, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            // Best-effort flush of file data before the rename commit.
            try (var ch = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            try (var dir = Files.newDirectoryStream(parent)) {
                // touching the directory stream is enough; fsync of dir fd is
                // not portable in Java. The atomic rename is the durability
                // guarantee that matters for the recovery contract.
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("atomic write failed: " + target, ex);
        }
    }

    public void writeJson(Path target, Object value) {
        atomicWrite(target, Json.write(value));
    }

    public Optional<Map<String, Object>> readJsonIfExists(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Json.parseObject(text));
        } catch (IOException ex) {
            throw new UncheckedIOException("read failed: " + file, ex);
        }
    }

    public List<Path> list(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException("list failed: " + dir, ex);
        }
    }

    // -------------------------------------------------------------- locations

    public Path familyFile(String family) {
        return raw.resolve("families").resolve(safe(family) + ".json");
    }

    public Path packageFile(String storageKey) {
        return raw.resolve("packages").resolve(safe(storageKey) + ".json");
    }

    public Path deviceFile(String deviceId) {
        return derived.resolve("devices").resolve(safe(deviceId) + ".json");
    }

    public Path planFile(String planId) {
        return derived.resolve("plans").resolve(safe(planId) + ".json");
    }

    public Path rolloutFile(String rolloutId) {
        return derived.resolve("rollouts").resolve(safe(rolloutId) + ".json");
    }

    public Path snapshotFile(String rolloutId) {
        return derived.resolve("snapshots").resolve(safe(rolloutId) + ".json");
    }

    public Path exportFile(String rolloutId) {
        return derived.resolve("exports").resolve(safe(rolloutId) + ".json");
    }

    public Path ledgerFile(String requestId) {
        return events.resolve("ledger-" + safe(requestId) + ".json");
    }

    private static String safe(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // -------------------------------------------------------------- event log

    private final Object logLock = new Object();

    /**
     * Appends a framed event. Each event is a single JSON object on its own
     * line preceded by its byte length, so a torn tail can be detected.
     */
    public void appendEvent(String type, Map<String, Object> detail) {
        synchronized (logLock) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ts", java.time.Instant.now().toString());
            entry.put("type", type);
            entry.put("detail", detail);
            String body = Json.write(entry);
            String frame = body.length() + ":" + body + "\n";
            try {
                Files.writeString(events.resolve("events.log"), frame, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                throw new UncheckedIOException("event append failed", ex);
            }
        }
    }

    /** Replays valid frames; a partial final frame (process died mid-append) is ignored. */
    public List<Map<String, Object>> readEvents() {
        Path log = events.resolve("events.log");
        if (!Files.exists(log)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            for (String line : lines) {
                int idx = line.indexOf(':');
                if (idx <= 0) {
                    continue; // corrupted frame marker; skip
                }
                String body = line.substring(idx + 1);
                try {
                    int claimed = Integer.parseInt(line.substring(0, idx));
                    if (claimed != body.length()) {
                        continue; // torn frame
                    }
                    out.add(Json.parseObject(body));
                } catch (RuntimeException ignore) {
                    // skip unparseable frame
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("event read failed", ex);
        }
        return out;
    }

    // ------------------------------------------------------- request idempotency

    public record LedgerEntry(String requestId, String method, String path,
                              int status, String body, String ts) {
    }

    /**
     * @return the stored replay response if this request was durably completed
     *         before, so retries never create a second business result.
     */
    public Optional<LedgerEntry> replay(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return readJsonIfExists(ledgerFile(requestId)).map(m -> new LedgerEntry(
                String.valueOf(m.get("request_id")),
                String.valueOf(m.get("method")),
                String.valueOf(m.get("path")),
                m.get("status") instanceof Number n ? n.intValue() : 500,
                String.valueOf(m.getOrDefault("body", "")),
                String.valueOf(m.getOrDefault("ts", ""))));
    }

    public void recordResult(String requestId, String method, String path,
                             int status, String body) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("request_id", requestId);
        entry.put("method", method);
        entry.put("path", path);
        entry.put("status", status);
        entry.put("body", body);
        entry.put("ts", java.time.Instant.now().toString());
        writeJson(ledgerFile(requestId), entry);
    }
}
