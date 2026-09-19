package fw;

import fw.json.Json;
import fw.store.Store;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StoreTest {

    @Test
    void separatesRawDerivedAndEvents() throws Exception {
        Path dir = Files.createTempDirectory("fw-layout-");
        Store store = new Store(dir);
        store.writeJson(store.familyFile("fam"), Map.of("family", "fam"));
        store.writeJson(store.planFile("plan-1"), Map.of("id", "plan-1"));
        store.appendEvent("thing", Map.of("a", 1));

        assertTrue(Files.exists(store.rawDir().resolve("families/fam.json")));
        assertTrue(Files.exists(store.derivedDir().resolve("plans/plan-1.json")));
        assertTrue(Files.exists(store.rawDir()));
        assertTrue(Files.exists(store.derivedDir()));
        assertEquals(1, store.readEvents().size());
        // physical separation: raw and derived are sibling directories
        assertNotEquals(store.rawDir(), store.derivedDir());
    }

    @Test
    void atomicWriteNeverLeavesPartialJson() throws Exception {
        Path dir = Files.createTempDirectory("fw-atomic-");
        Store store = new Store(dir);
        Path file = dir.resolve("doc.json");
        store.writeJson(file, Map.of("ok", true, "v", 1));
        for (int i = 0; i < 50; i++) {
            store.writeJson(file, Map.of("ok", true, "v", i));
            // every intermediate state must be a complete parseable document
            assertNotNull(Json.parseObject(Files.readString(file)));
        }
        assertEquals(0, Files.list(dir).filter(p -> p.getFileName().toString().startsWith(".tmp-"))
                .count());
    }

    @Test
    void requestReplayReturnsStoredResult() {
        Store store = new Store(tmpDir());
        assertTrue(store.replay("req-1").isEmpty());
        store.recordResult("req-1", "POST", "/x", 201, "{\"id\":1}");
        var replay = store.replay("req-1").orElseThrow();
        assertEquals(201, replay.status());
        assertEquals("/x", replay.path());
        assertEquals("{\"id\":1}", replay.body());
        assertTrue(store.replay(null).isEmpty());
        assertTrue(store.replay("  ").isEmpty());
    }

    @Test
    void tornEventFrameIsIgnored() throws Exception {
        Path dir = Files.createTempDirectory("fw-events-");
        Store store = new Store(dir);
        store.appendEvent("good", Map.of("n", 1));
        Path log = dir.resolve("events/events.log");
        String existing = Files.readString(log);
        // append a valid frame then a torn tail
        store.appendEvent("good2", Map.of("n", 2));
        String frame = "27:{\"ts\":\"x\",\"type\":\"p\",\"detail\":{}}\n";
        Files.writeString(log, existing + frame + "123:{\"partial",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        var events = store.readEvents();
        assertEquals(3, events.size(), "torn trailing frame must be dropped, not parsed");
    }

    private static Path tmpDir() {
        try {
            return Files.createTempDirectory("fw-ledger-");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
