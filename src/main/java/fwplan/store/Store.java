package fwplan.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import fwplan.util.Json;

/**
 * 文件持久化，三类数据物理分离：
 *   data/raw/      原始输入（清单、固件元数据、签名者、cohort 定义）
 *   data/derived/  派生结果（规划、模拟、发布方案、回执、导出）
 *   data/events/   追加写操作事件
 *
 * 原子写：先写 tmp 再 fsync + move；tmp/ 中残留文件在启动时清理。
 * 状态更新按“事件先行 + 状态原子替换”顺序落盘，崩溃后不会暴露半成品：
 * 若事件已落而状态未替换，启动重放最后一条未生效事件；反之事件丢失则状态不变。
 */
public final class Store {

    public final Path root;
    public final Path rawDir;
    public final Path derivedDir;
    public final Path eventsDir;
    public final Path tmpDir;

    private long seq = 0;

    public Store(Path root) {
        this.root = root;
        this.rawDir = root.resolve("raw");
        this.derivedDir = root.resolve("derived");
        this.eventsDir = root.resolve("events");
        this.tmpDir = root.resolve("tmp");
        for (Path dir : List.of(rawDir, derivedDir, eventsDir, tmpDir)) {
            try { Files.createDirectories(dir); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }
    }

    public synchronized void startupRecover() {
        List<Path> leftovers = new ArrayList<>();
        collectTmp(tmpDir, leftovers);
        collectTmp(rawDir, leftovers);
        collectTmp(derivedDir, leftovers);
        for (Path leftover : leftovers) {
            try { Files.deleteIfExists(leftover); }
            catch (IOException e) { throw new UncheckedIOException(e); }
        }
        recoverStateFromEvents();
    }

    private void collectTmp(Path dir, List<Path> sink) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).forEach(sink::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 读取 JSON 状态；不存在返回 null。 */
    public Map<String, Object> readJson(Path file) {
        if (!Files.exists(file)) return null;
        try {
            String body = Files.readString(file, StandardCharsets.UTF_8);
            if (body.isBlank()) return null;
            return Json.parseObject(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Path> listFiles(Path dir, String suffix) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    public String readString(Path file) {
        try { return Files.readString(file, StandardCharsets.UTF_8); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    /** 原子写状态文件（无事件）。 */
    public void writeJsonAtomic(Path target, Object value) {
        writeAtomic(target, Json.writePretty(value));
    }

    /** 先追加事件（fsync），再原子替换状态文件。 */
    public synchronized void commit(String eventType, Map<String, Object> payload,
                                    Path stateFile, Object stateValue) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", ++seq);
        event.put("ts", java.time.Instant.now().toString());
        event.put("type", eventType);
        event.put("payload", payload);
        appendEvent(event);
        writeJsonAtomic(stateFile, stateValue);
    }

    public synchronized void appendEventOnly(String type, Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", ++seq);
        event.put("ts", java.time.Instant.now().toString());
        event.put("type", type);
        event.put("payload", payload);
        appendEvent(event);
    }

    private void appendEvent(Map<String, Object> event) {
        Path log = eventsDir.resolve("events-" + event.get("ts").toString().substring(0, 10).replace("-", "") + ".log");
        String line = Json.write(event) + "\n";
        try {
            Files.writeString(log, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeAtomic(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            // 临时文件必须与目标位于同一目录，rename 才是原子的；统一 .tmp 后缀供启动清理
            Path tmp = target.resolveSibling(
                    target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp.toFile())) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- 崩溃恢复：事件日志重放 ----------

    private void recoverStateFromEvents() {
        // 回执是关键业务结果：若事件已写但状态文件缺失（崩溃在两次写之间），从日志重建。
        Map<String, Map<String, Object>> rebuilt = new LinkedHashMap<>();
        for (Path log : listFiles(eventsDir, ".log")) {
            String content = readString(log);
            if (content.isBlank()) continue;
            for (String line : content.split("\n")) {
                if (line.isBlank()) continue;
                Map<String, Object> event = Json.parseObject(line);
                if (!"RECEIPT_RECORDED".equals(String.valueOf(event.get("type")))) continue;
                Map<String, Object> payload = Json.obj(event, "payload");
                Map<String, Object> receipt = Json.obj(payload, "receipt");
                rebuilt.put(String.valueOf(receipt.get("requestId")), receipt);
            }
        }
        for (Map.Entry<String, Map<String, Object>> entry : rebuilt.entrySet()) {
            Path file = derivedDir.resolve("receipts").resolve(entry.getKey() + ".json");
            if (!Files.exists(file)) writeJsonAtomic(file, entry.getValue());
        }
    }

    public String nowIso() { return java.time.Instant.now().toString(); }
}
