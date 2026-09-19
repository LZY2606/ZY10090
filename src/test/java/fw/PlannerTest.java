package fw;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlannerTest {

    private TestWorld world() {
        TestWorld w = TestWorld.create();
        w.family("f", new String[]{"bootloader", "radio", "main"}, "r1", "1.0.0");
        // bootloader 1 and 2 (2 is A/B safe)
        w.pkg("f", "bootloader", "bl", "1.0.0", 1, "r1", Map.of(), null, null, true);
        w.pkg("f", "bootloader", "bl", "2.0.0", 1, "r1", Map.of(), null, null, true);
        // radio 1 (fmt1, safe) and 2 (fmt2, no rollback), radio 2 needs bl >= 2
        w.pkg("f", "radio", "rd", "1.0.0", 1, "r1",
                Map.of("bootloader", "1.0.0..2.0.0"), null, null, true);
        w.pkg("f", "radio", "rd", "2.0.0", 2, "r1",
                Map.of("bootloader", "2.0.0..2.0.0"), "2.0.0", "1.0.0", false);
        // main 1 needs radio 1; main 2 needs bl2 + radio2 and has format bump
        w.pkg("f", "main", "mn", "1.0.0", 1, "r1",
                Map.of("radio", "1.0.0..1.9.9"), null, null, true);
        w.pkg("f", "main", "mn", "2.0.0", 2, "r1",
                Map.of("bootloader", "2.0.0..2.0.0", "radio", "2.0.0..2.9.9"),
                "2.0.0", "1.0.0", false);
        return w;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> steps(Map<String, Object> plan) {
        return (List<Map<String, Object>>) plan.get("steps");
    }

    @Test
    void ordersInterdependentUpdatesCorrectly() {
        TestWorld w = world();
        Map<String, String> cur = Map.of(
                "bootloader", w.packageKey("f", "bootloader", "1.0.0"),
                "radio", w.packageKey("f", "radio", "1.0.0"),
                "main", w.packageKey("f", "main", "1.0.0"));
        Map<String, String> target = Map.of(
                "main", w.packageKey("f", "main", "2.0.0"));
        var plan = w.evaluate(w.request("f", "r1", cur, target));
        assertTrue((Boolean) plan.get("feasible"), () -> plan.get("kind") + "");
        var steps = steps(plan);
        assertEquals(3, steps.size());
        assertEquals("bootloader", steps.get(0).get("component"));
        assertEquals("radio", steps.get(1).get("component"));
        assertEquals("main", steps.get(2).get("component"));
        // format bumps have no safe rollback and must be red
        assertFalse((Boolean) steps.get(1).get("safe_rollback"));
        assertFalse((Boolean) steps.get(2).get("safe_rollback"));
        assertTrue((Boolean) steps.get(0).get("safe_rollback"));
    }

    @Test
    void flagsStorageFormatDowngradeWithMinimalSet() {
        TestWorld w = world();
        // radio stays at a format-1 version that main 1's window accepts,
        // so the ONLY blocker is the main storage-format downgrade.
        w.pkg("f", "radio", "rd", "1.5.0", 1, "r1",
                Map.of("bootloader", "1.0.0..2.0.0"), null, null, true);
        Map<String, String> cur = Map.of(
                "bootloader", w.packageKey("f", "bootloader", "2.0.0"),
                "radio", w.packageKey("f", "radio", "1.5.0"),
                "main", w.packageKey("f", "main", "2.0.0"));
        Map<String, String> target = Map.of(
                "main", w.packageKey("f", "main", "1.0.0"));
        var plan = w.evaluate(w.request("f", "r1", cur, target));
        assertFalse((Boolean) plan.get("feasible"));
        assertEquals("storage_format_break", plan.get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts =
                (List<Map<String, Object>>) plan.get("conflict_set");
        assertEquals(1, conflicts.size(), "conflict set must be minimal");
        assertEquals("format_downgrade", conflicts.get(0).get("gate"));
    }

    @Test
    void enforcesNoSkipCeiling() {
        // Dedicated family without companion windows, so the only gate is the
        // package's own no-skip ceiling.
        TestWorld w = TestWorld.create();
        w.family("h", new String[]{"radio"}, "r1", "1.0.0");
        w.pkg("h", "radio", "rd", "1.0.0", 1, "r1", Map.of(), null, null, true);
        w.pkg("h", "radio", "rd", "2.0.0", 1, "r1", Map.of(), null, "1.0.0", true);
        // 3.0 may only be entered from 1.0.0 or older: 1 -> 3 is legal per the
        // ceiling, so add 4.0 which only accepts <= 2.0.0 predecessor, forcing
        // a mandatory stop at 3.0; jumping 2 -> 4 must be refused.
        w.pkg("h", "radio", "rd", "3.0.0", 1, "r1", Map.of(), null, "2.0.0", true);
        w.pkg("h", "radio", "rd", "4.0.0", 1, "r1", Map.of(), null, "2.0.0", true);
        Map<String, String> cur = Map.of(
                "radio", w.packageKey("h", "radio", "3.0.0"));
        var blocked = w.evaluate(w.request("h", "r1", cur,
                Map.of("radio", w.packageKey("h", "radio", "4.0.0"))));
        assertFalse((Boolean) blocked.get("feasible"));
        assertEquals("no_skip_violation", blocked.get("kind"));
        // the legal path 1 -> 2 -> 3 exists
        var legal = w.evaluate(w.request("h", "r1",
                Map.of("radio", w.packageKey("h", "radio", "1.0.0")),
                Map.of("radio", w.packageKey("h", "radio", "3.0.0"))));
        assertTrue((Boolean) legal.get("feasible"), String.valueOf(legal.get("conflict_set")));
    }

    @Test
    void rejectsUnknownFamilyAndRevision() {
        TestWorld w = world();
        var plan = w.evaluate(w.request("nope", "r1", Map.of(), Map.of()));
        assertEquals("invalid_request", plan.get("kind"));
        var plan2 = w.evaluate(w.request("f", "zzz", Map.of(), Map.of()));
        assertEquals("invalid_request", plan2.get("kind"));
    }

    @Test
    void noCommonVersionProducesIrreducibleConflictSet() {
        TestWorld w = TestWorld.create();
        w.family("g", new String[]{"bootloader", "main"}, "r1", "1.0.0");
        w.pkg("g", "bootloader", "bl", "1.0.0", 1, "r1", Map.of(), null, null, true);
        // main requires bootloader 3+, which does not exist -> no common version
        w.pkg("g", "main", "mn", "1.0.0", 1, "r1",
                Map.of("bootloader", "3.0.0..3.9.9"), null, null, true);
        Map<String, String> cur = Map.of(
                "bootloader", w.packageKey("g", "bootloader", "1.0.0"));
        Map<String, String> target = Map.of(
                "main", w.packageKey("g", "main", "1.0.0"));
        var plan = w.evaluate(w.request("g", "r1", cur, target));
        assertFalse((Boolean) plan.get("feasible"));
        assertEquals("no_common_version", plan.get("kind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts =
                (List<Map<String, Object>>) plan.get("conflict_set");
        assertFalse(conflicts.isEmpty());
        assertTrue(conflicts.stream().anyMatch(c -> "package_requires".equals(c.get("gate"))));
    }

    @Test
    void neverPicksNewestVersionForIntermediates() {
        // Even when a newer bootloader exists, closure must select the LOWEST
        // version satisfying every window (2.0.0), not the newest.
        TestWorld w = world();
        w.pkg("f", "bootloader", "bl", "2.5.0", 1, "r1", Map.of(), null, null, true);
        Map<String, String> cur = Map.of(
                "bootloader", w.packageKey("f", "bootloader", "1.0.0"),
                "radio", w.packageKey("f", "radio", "1.0.0"),
                "main", w.packageKey("f", "main", "1.0.0"));
        Map<String, String> target = Map.of(
                "main", w.packageKey("f", "main", "2.0.0"));
        var plan = w.evaluate(w.request("f", "r1", cur, target));
        assertTrue((Boolean) plan.get("feasible"), String.valueOf(plan.get("conflict_set")));
        var steps = steps(plan);
        assertEquals("2.0.0", steps.get(0).get("to_version"),
                "must derive the lowest compatible bootloader, not newest 2.5.0");
    }
}
