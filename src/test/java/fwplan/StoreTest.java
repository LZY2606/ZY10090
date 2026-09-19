package fwplan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import fwplan.store.Store;

public final class StoreTest {

    public static void run() throws Exception {
        Path dir = Files.createTempDirectory("fwplan-store");
        Store store = new Store(dir);
        Path file = store.derivedDir.resolve("x.json");

        for (int i = 0; i < 100; i++) {
            store.writeJsonAtomic(file, Map.of("n", i));
        }
        Test.eq(store.readJson(file).get("n"), 99, "原子写最终值正确（0..99 最后一次）");
        Test.check(Files.list(store.tmpDir).count() == 0, "tmp 不应残留");

        // 模拟崩溃：状态文件缺失但事件日志已写入（RECEIPT_RECORDED），重启应重建
        Path receipts = store.derivedDir.resolve("receipts");
        Files.createDirectories(receipts);
        Map<String, Object> receipt = new java.util.LinkedHashMap<>();
        receipt.put("requestId", "req-1");
        receipt.put("releaseId", "r");
        receipt.put("cohortId", "c");
        receipt.put("deviceId", "d");
        receipt.put("action", "advance");
        receipt.put("status", "ACCEPTED");
        receipt.put("fromPhase", "");
        receipt.put("toPhase", "phase-0");
        receipt.put("message", "m");
        receipt.put("recordedAt", "2026-01-01T00:00:00Z");
        receipt.put("replay", false);
        Map<String, Object> payload = Map.of("receipt", receipt);
        store.appendEventOnly("RECEIPT_RECORDED", payload);
        Files.deleteIfExists(receipts.resolve("req-1.json"));
        new Store(dir).startupRecover();
        Test.check(Files.exists(receipts.resolve("req-1.json")), "事件重放必须重建丢失的回执状态");

        // 三类数据目录分离
        Test.check(Files.isDirectory(dir.resolve("raw")), "raw 目录存在");
        Test.check(Files.isDirectory(dir.resolve("derived")), "derived 目录存在");
        Test.check(Files.isDirectory(dir.resolve("events")), "events 目录存在");
    }
}
