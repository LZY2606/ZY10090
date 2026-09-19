package fw;

import fw.core.AppService;
import fw.core.DemoData;
import fw.model.DeviceState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RolloutWorkflowTest {

    private AppService app;

    @BeforeEach
    void setUp() {
        var w = TestWorld.create();
        // reuse the seeded demo scenario by creating a dedicated app on a fresh store
        app = new fw.core.AppService(w.store, w.verifier);
        DemoData.seed(app, "k", "secret");
    }

    private String key(String family, String component, String version) {
        return app.catalog().candidates(family, component).stream()
                .filter(p -> p.version().equals(version)).findFirst().orElseThrow()
                .storageKey();
    }

    private Map<String, Object> canaryTargets() {
        return Map.of(
                "main", key("ctrl-a", "main", "4.0.0"),
                "radio", key("ctrl-a", "radio", "2.0.0"),
                "sensor", key("ctrl-a", "sensor", "3.0.0"));
    }

    @Test
    void approvesAndCompletesWithIdempotentReceipts() {
        var created = app.createRollout(Map.of(
                "name", "canary", "cohorts", List.of("canary"),
                "targets", canaryTargets()));
        String id = (String) created.get("id");
        assertEquals("draft", created.get("status"));

        app.approveRollout(id);
        var approved = app.getRollout(id);
        assertEquals("approved", approved.get("status"));
        assertNotNull(approved.get("snapshot_id"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stages =
                (List<Map<String, Object>>) approved.get("stages");
        for (int i = 0; i < stages.size(); i++) {
            Map<String, Object> stage = stages.get(i);
            @SuppressWarnings("unchecked")
            String device = ((List<String>) stage.get("expected_devices")).get(0);
            var first = app.receipt(id, Map.of(
                    "receipt_id", "rcpt-" + i, "device_id", device,
                    "sha256", stage.get("sha256"),
                    "version", stage.get("to_version")));
            assertFalse(Boolean.TRUE.equals(first.get("_replayed")));
            // retried receipt must not advance anything
            var replay = app.receipt(id, Map.of(
                    "receipt_id", "rcpt-" + i, "device_id", device,
                    "sha256", stage.get("sha256"),
                    "version", stage.get("to_version")));
            assertTrue(Boolean.TRUE.equals(replay.get("_replayed")));
        }
        assertEquals("complete", app.getRollout(id).get("status"));
        @SuppressWarnings("unchecked")
        List<String> receipts =
                (List<String>) app.getRollout(id).get("receipts");
        assertEquals(4, receipts.size(), "retry must not duplicate a receipt");
    }

    @Test
    void cannotApproveInfeasibleRollout() {
        var created = app.createRollout(Map.of(
                "name", "legacy", "cohorts", List.of("legacy"),
                "targets", canaryTargets()));
        assertThrows(fw.core.RolloutManager.StateConflictException.class,
                () -> app.approveRollout((String) created.get("id")));
    }

    @Test
    void frozenCohortIsExcludedFromRecompute() {
        var created = app.createRollout(Map.of(
                "name", "mixed", "cohorts", List.of("canary", "frozen-batch"),
                "targets", canaryTargets()));
        String id = (String) created.get("id");
        assertEquals(List.of("dev-frozen-04"),
                ((Map<?, ?>) created.get("draft")).get("frozen_devices"));
        app.freezeCohort(id, "canary", true);
        var recomputed = app.recomputeRollout(id);
        @SuppressWarnings("unchecked")
        List<String> pinned =
                (List<String>) ((Map<?, ?>) recomputed.get("draft")).get("pinned_by_freeze");
        assertTrue(pinned.contains("dev-charlie-03"));
        assertTrue(pinned.contains("dev-frozen-04"));
    }

    @Test
    void exportContainsBasisSignaturesAndFailureBranches() {
        var created = app.createRollout(Map.of(
                "name", "canary", "cohorts", List.of("canary"),
                "targets", canaryTargets()));
        String id = (String) created.get("id");
        app.approveRollout(id);
        var export = app.exportRollout(id);
        assertNotNull(export.get("decision_basis"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sig = (Map<String, Object>) export.get("signature_verification");
        assertTrue(((Number) sig.get("verified_package_count")).intValue() >= 4);
        @SuppressWarnings("unchecked")
        List<?> branches = (List<?>) export.get("failure_branches");
        assertFalse(branches.isEmpty());
        // every step must appear with all three fault points
        assertEquals(12, branches.size());
    }

    @Test
    void sameNameDifferentHashIsRejected() {
        Map<String, Object> body = new java.util.LinkedHashMap<>(
                app.catalog().candidates("ctrl-a", "main").stream()
                        .filter(p -> p.version().equals("2.0.0")).findFirst().orElseThrow()
                        .toMap());
        body.put("sha256", "a".repeat(64));
        body.put("signature",
                fw.crypto.SignatureVerifier.sign(body,
                        fw.Main.DEMO_KEY_ID, fw.Main.DEMO_KEY_SECRET));
        assertThrows(fw.api.Errors.ApiException.class, () -> app.importPackage(body));
    }
}
