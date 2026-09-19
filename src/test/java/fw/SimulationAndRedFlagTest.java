package fw;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SimulationAndRedFlagTest {

    @Test
    void powerLossBranchesMatchRecoveryModel() {
        TestWorld w = TestWorld.create();
        w.family("f", new String[]{"bootloader", "main"}, "r1", "1.0.0");
        w.pkg("f", "bootloader", "bl", "1.0.0", 1, "r1", Map.of(), null, null, true);
        w.pkg("f", "bootloader", "bl", "2.0.0", 1, "r1", Map.of(), null, null, true);
        w.pkg("f", "main", "mn", "1.0.0", 1, "r1",
                Map.of("bootloader", "1.0.0..2.0.0"), null, null, true);
        w.pkg("f", "main", "mn", "2.0.0", 2, "r1",
                Map.of("bootloader", "2.0.0..2.0.0"), "2.0.0", null, false);

        Map<String, String> cur = Map.of(
                "bootloader", w.packageKey("f", "bootloader", "1.0.0"),
                "main", w.packageKey("f", "main", "1.0.0"));
        var plan = w.evaluate(w.request("f", "r1", cur,
                Map.of("main", w.packageKey("f", "main", "2.0.0"))));
        assertTrue((Boolean) plan.get("feasible"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> simulation =
                (List<Map<String, Object>>) plan.get("simulation");
        @SuppressWarnings("unchecked")
        Map<String, Object> mainStep = simulation.stream()
                .filter(s -> "main".equals(s.get("component"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> faults =
                (List<Map<String, Object>>) mainStep.get("fault_points");
        Map<String, Map<String, Object>> byFault = new java.util.LinkedHashMap<>();
        faults.forEach(f -> byFault.put((String) f.get("fault"), f));

        var powerLoss = byFault.get("power_loss_after_write_commit");
        assertEquals("recovery_partition", powerLoss.get("recovery_state"));
        assertTrue((Boolean) powerLoss.get("requires_manual_action"));

        var health = byFault.get("post_flash_health_failed");
        assertEquals("stuck_on_bad_image", health.get("recovery_state"));
        assertFalse((Boolean) health.get("recoverable"));

        var probe = byFault.get("pre_flash_probe_failed");
        assertEquals("unchanged", probe.get("recovery_state"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> red =
                (List<Map<String, Object>>) plan.get("red_steps");
        assertTrue(red.stream().anyMatch(s -> "main".equals(s.get("component"))));
    }
}
