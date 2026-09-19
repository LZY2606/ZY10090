package fw.core;

import fw.model.DeviceFamily;
import fw.model.FirmwarePackage;
import fw.model.Version;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Failure-point simulator for a concrete flash sequence.
 *
 * <p>Every step is forked into three branches: a power cut after the component
 * write commits, a failing pre-flash probe and a failing post-flash health
 * check. The power-cut branch computes the reboot state: dual-bank images
 * return to the previous slot, A/B-capable images land in a defined recovery
 * state, and a single-bank image that cannot survive a torn write bricks the
 * component. Steps without a safe rollback target are surfaced as
 * {@code no_safe_rollback} and the UI must render them red.</p>
 */
public final class Simulator {

    private final Catalog catalog;

    public Simulator(Catalog catalog) {
        this.catalog = catalog;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> simulateSteps(DeviceFamily family, String revision,
                                                   List<Map<String, Object>> steps,
                                                   Map<String, Object> startState) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("order", step.get("order"));
            result.put("component", step.get("component"));
            result.put("package_id", step.get("package_id"));
            result.put("to_version", step.get("to_version"));
            result.put("fault_points", List.of(
                    powerCut(family, revision, step, startState),
                    probeFailure(step),
                    healthFailure(step)));
            result.put("no_safe_rollback", !Boolean.TRUE.equals(step.get("safe_rollback")));
            out.add(result);
        }
        return out;
    }

    private Map<String, Object> powerCut(DeviceFamily family, String revision,
                                         Map<String, Object> step, Map<String, Object> startState) {
        String component = String.valueOf(step.get("component"));
        String toVersion = String.valueOf(step.get("to_version"));
        String fromVersion = step.get("from_version") == null
                ? null : String.valueOf(step.get("from_version"));
        FirmwarePackage incoming = findPackage(family.family(), component, toVersion);
        boolean safe = Boolean.TRUE.equals(step.get("safe_rollback"));

        RecoveryOutcome outcome = rebootOutcome(family, revision, incoming, fromVersion, safe);
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("fault", "power_loss_after_write_commit");
        branch.put("recovery_state", outcome.state);
        branch.put("rebooted_component_version", outcome.version);
        branch.put("recoverable", outcome.recoverable);
        branch.put("requires_manual_action", outcome.manual);
        branch.put("explanation", outcome.explanation);
        return branch;
    }

    private RecoveryOutcome rebootOutcome(DeviceFamily family, String revision,
                                          FirmwarePackage incoming, String previousVersion,
                                          boolean safeRollback) {
        if (incoming == null) {
            return new RecoveryOutcome("unknown", null, false, true,
                    "incoming package not found in signed catalog");
        }
        if ("bootloader".equals(incoming.component())) {
            // Bootloader A/B is modelled via safe_rollback; an irreversible
            // bootloader flash mid-write is the catastrophic red case.
            if (safeRollback) {
                return new RecoveryOutcome("previous_bootloader_slot", previousVersion,
                        true, false,
                        "dual-slot bootloader reverts to the previously committed slot");
            }
            return new RecoveryOutcome("brick", null, false, true,
                    "single-bank bootloader write interrupted: device does not reboot");
        }
        if (safeRollback) {
            return new RecoveryOutcome("previous_app_slot", previousVersion, true, false,
                    "A/B swap never committed; bootloader boots the old bank");
        }
        if (incoming.formatVersion() >= 2) {
            return new RecoveryOutcome("recovery_partition", "recovery", true, true,
                    "device boots the recovery partition; storage migration is rolled back");
        }
        return new RecoveryOutcome("torn_single_bank", null, false, true,
                "single-bank component without retained slot; reflash required");
    }

    private Map<String, Object> probeFailure(Map<String, Object> step) {
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("fault", "pre_flash_probe_failed");
        branch.put("probe", step.get("pre_flash_probe"));
        branch.put("recovery_state", "unchanged");
        branch.put("recoverable", true);
        branch.put("requires_manual_action", false);
        branch.put("explanation", "write is gated behind the probe; nothing was flashed");
        return branch;
    }

    private Map<String, Object> healthFailure(Map<String, Object> step) {
        Map<String, Object> rollback = step.get("rollback_target") instanceof Map<?, ?> rb
                ? (Map<String, Object>) rb : Map.of();
        boolean reachable = Boolean.TRUE.equals(rollback.get("reachable"));
        Map<String, Object> branch = new LinkedHashMap<>();
        branch.put("fault", "post_flash_health_failed");
        branch.put("health_check", step.get("health_check"));
        branch.put("recovery_state", reachable ? "rolled_back" : "stuck_on_bad_image");
        branch.put("recoverable", reachable);
        branch.put("requires_manual_action", !reachable);
        branch.put("rollback_to", rollback.get("version"));
        branch.put("explanation", reachable
                ? "orchestrator reverts to the retained slot and re-runs health check"
                : "no safe rollback target; stage halts and must be flagged red");
        return branch;
    }

    private FirmwarePackage findPackage(String family, String component, String version) {
        for (FirmwarePackage p : catalog.candidates(family, component)) {
            if (p.version().equals(version)) {
                return p;
            }
        }
        return null;
    }

    private record RecoveryOutcome(String state, String version, boolean recoverable,
                                   boolean manual, String explanation) {
    }
}
