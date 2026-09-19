package fw.core;

import fw.json.Json;
import fw.model.DeviceState;
import fw.model.FirmwarePackage;
import fw.store.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cohort/stage rollout state machine.
 *
 * <p>A rollout is created against a draft plan bundle for a set of cohorts.
 * Approval binds an immutable manifest+package snapshot: newly uploaded
 * metadata afterwards cannot change the approved steps. Device receipts are
 * idempotent per {@code receipt_id}; a retried receipt never advances a stage
 * twice. Frozen cohorts are held at their current state while the others are
 * recomputed.</p>
 */
public final class RolloutManager {

    private final Store store;
    private final Planner planner;
    private final Catalog catalog;

    public RolloutManager(Store store, Planner planner, Catalog catalog) {
        this.store = store;
        this.planner = planner;
        this.catalog = catalog;
    }

    // ------------------------------------------------------------ draft bundle

    /**
     * Computes a per-device draft for every unfrozen device matching the
     * cohorts. Frozen devices are reported but left out of the stages.
     */
    public Map<String, Object> draft(List<DeviceState> devices, Map<String, String> targets,
                                     Set<String> cohorts) {
        Map<String, Object> draft = new LinkedHashMap<>();
        List<Map<String, Object>> devicePlans = new ArrayList<>();
        List<String> frozenDevices = new ArrayList<>();
        boolean allFeasible = true;
        int included = 0;

        for (DeviceState device : devices) {
            if (!cohorts.contains(device.cohort())) {
                continue;
            }
            if (device.frozen()) {
                frozenDevices.add(device.deviceId());
                continue;
            }
            included++;
            Planner.Request req = new Planner.Request();
            req.family = device.family();
            req.revision = device.revision();
            req.current = device.components();
            req.targets = targets;
            Map<String, Object> result = planner.plan(req);
            result.put("device_id", device.deviceId());
            result.put("cohort", device.cohort());
            devicePlans.add(result);
            if (!Boolean.TRUE.equals(result.get("feasible"))) {
                allFeasible = false;
            }
        }

        draft.put("cohorts", new ArrayList<>(cohorts));
        draft.put("included_device_count", included);
        draft.put("frozen_devices", frozenDevices);
        draft.put("all_feasible", allFeasible);
        draft.put("device_plans", devicePlans);
        draft.put("stages", allFeasible ? buildStages(devicePlans) : List.of());
        return draft;
    }

    /**
     * Stages group the same (component, target version) across devices so one
     * stage completes when every included device has a receipt for it.
     */
    private List<Map<String, Object>> buildStages(List<Map<String, Object>> devicePlans) {
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> plan : devicePlans) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> steps = (List<Map<String, Object>>) plan.get("steps");
            for (Map<String, Object> step : steps) {
                String key = step.get("component") + "@" + step.get("to_version");
                Map<String, Object> stage = byKey.computeIfAbsent(key, k -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("component", step.get("component"));
                    s.put("to_version", step.get("to_version"));
                    s.put("package_id", step.get("package_id"));
                    s.put("sha256", step.get("sha256"));
                    s.put("pre_flash_probe", step.get("pre_flash_probe"));
                    s.put("update_action", step.get("update_action"));
                    s.put("health_check", step.get("health_check"));
                    s.put("safe_rollback", step.get("safe_rollback"));
                    s.put("rollback_target", step.get("rollback_target"));
                    s.put("devices", new LinkedHashSet<String>());
                    return s;
                });
                @SuppressWarnings("unchecked")
                Set<String> devs = (Set<String>) stage.get("devices");
                devs.add(String.valueOf(plan.get("device_id")));
            }
        }
        List<Map<String, Object>> stages = new ArrayList<>();
        int order = 1;
        for (Map<String, Object> stage : byKey.values()) {
            stage.put("order", order++);
            @SuppressWarnings("unchecked")
            Set<String> devs = (Set<String>) stage.remove("devices");
            stage.put("expected_devices", new ArrayList<>(devs));
            stage.put("acked_devices", new ArrayList<String>());
            stages.add(stage);
        }
        return stages;
    }

    // -------------------------------------------------------------- create etc

    public Map<String, Object> createRollout(String name, List<DeviceState> devices,
                                             Map<String, String> targets, Set<String> cohorts) {
        String id = "rollout-" + UUID.randomUUID();
        Map<String, Object> draft = draft(devices, targets, cohorts);
        Map<String, Object> rollout = new LinkedHashMap<>();
        rollout.put("id", id);
        rollout.put("name", name);
        rollout.put("status", "draft");
        rollout.put("created_at", java.time.Instant.now().toString());
        rollout.put("cohorts", new ArrayList<>(cohorts));
        rollout.put("frozen_cohorts", new ArrayList<String>());
        rollout.put("targets", new LinkedHashMap<>(targets));
        rollout.put("draft", draft);
        rollout.put("stages", draft.get("stages"));
        rollout.put("stage_index", 0);
        rollout.put("receipts", new LinkedHashSet<String>());
        rollout.put("device_acks", new LinkedHashMap<String, Object>());
        rollout.put("snapshot_id", null);
        persist(rollout);
        store.appendEvent("rollout_created", Map.of(
                "rollout_id", id, "name", name,
                "all_feasible", draft.get("all_feasible")));
        return rollout;
    }

    /**
     * Approving binds a snapshot of the exact family manifests and verified
     * packages used by every step. Later uploads never alter the rollout.
     */
    public Map<String, Object> approve(String rolloutId) {
        Map<String, Object> rollout = load(rolloutId);
        if (!"draft".equals(rollout.get("status"))) {
            throw new StateConflictException("rollout is " + rollout.get("status")
                    + ", only draft rollouts can be approved");
        }
        if (!Boolean.TRUE.equals(rollout.get("draft") instanceof Map<?, ?> d
                ? d.get("all_feasible") : false)) {
            throw new StateConflictException("rollout contains infeasible devices; resolve the conflict set first");
        }
        Map<String, Object> snapshot = buildSnapshot(rollout);
        String snapshotId = "snapshot-" + UUID.randomUUID();
        snapshot.put("snapshot_id", snapshotId);
        snapshot.put("bound_at", java.time.Instant.now().toString());
        store.writeJson(store.snapshotFile(rolloutId), snapshot);

        rollout.put("status", "approved");
        rollout.put("approved_at", java.time.Instant.now().toString());
        rollout.put("snapshot_id", snapshotId);
        persist(rollout);
        store.appendEvent("rollout_approved", Map.of(
                "rollout_id", rolloutId, "snapshot_id", snapshotId));
        return rollout;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildSnapshot(Map<String, Object> rollout) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("families", new LinkedHashMap<String, Object>());
        snapshot.put("packages", new LinkedHashMap<String, Object>());
        Map<String, Object> draft = (Map<String, Object>) rollout.get("draft");
        List<Map<String, Object>> plans = (List<Map<String, Object>>) draft.get("device_plans");
        Set<String> familyIds = new LinkedHashSet<>();
        Set<String> packageKeys = new LinkedHashSet<>();
        for (Map<String, Object> plan : plans) {
            familyIds.add(String.valueOf(plan.get("family")));
            for (Object sObj : (List<?>) plan.get("steps")) {
                Map<String, Object> step = (Map<String, Object>) sObj;
                packageKeys.add(String.valueOf(step.get("package_id")) + "__"
                        + step.get("to_version") + "__"
                        + String.valueOf(step.get("sha256")).substring(0, 12));
            }
        }
        Map<String, Object> fams = (Map<String, Object>) snapshot.get("families");
        for (String fid : familyIds) {
            store.readJsonIfExists(store.familyFile(fid))
                    .ifPresent(f -> fams.put(fid, f));
        }
        Map<String, Object> pkgs = (Map<String, Object>) snapshot.get("packages");
        for (String key : packageKeys) {
            FirmwarePackage pkg = catalog.byStorageKey(key);
            if (pkg != null) {
                pkgs.put(key, pkg.toMap());
            }
        }
        snapshot.put("targets", new LinkedHashMap<>((Map<?, ?>) rollout.get("targets")));
        snapshot.put("stages", rollout.get("stages"));
        return snapshot;
    }

    /**
     * Records a device receipt against the current stage.
     *
     * <p>Same {@code receipt_id} replays are acknowledged with no state change.
     * A receipt for a device that is not part of the current stage, or a
     * duplicate of a stage already advanced, is rejected as a state conflict
     * rather than silently moving the rollout.</p>
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> receipt(String rolloutId, Map<String, Object> body) {
        String receiptId = Json.reqString(body, "receipt_id");
        String deviceId = Json.reqString(body, "device_id");
        String reportedHash = body.get("sha256") instanceof String s ? s : null;
        String reportedVersion = body.get("version") instanceof String s ? s : null;

        Map<String, Object> rollout = load(rolloutId);
        Set<String> seenReceipts = new LinkedHashSet<>(
                (List<String>) rollout.getOrDefault("receipts", List.of()));
        if (seenReceipts.contains(receiptId)) {
            Map<String, Object> replay = new LinkedHashMap<>(rollout);
            replay.put("_replayed", true);
            replay.put("_note", "receipt already applied; no stage advancement");
            return replay;
        }
        if (!"approved".equals(rollout.get("status"))) {
            throw new StateConflictException("rollout is " + rollout.get("status")
                    + "; receipts are only accepted for approved rollouts");
        }
        List<Map<String, Object>> stages = (List<Map<String, Object>>) rollout.get("stages");
        int idx = ((Number) rollout.get("stage_index")).intValue();
        if (idx >= stages.size()) {
            throw new StateConflictException("all stages already complete");
        }
        Map<String, Object> stage = stages.get(idx);
        List<String> expected = (List<String>) stage.get("expected_devices");
        if (!expected.contains(deviceId)) {
            throw new StateConflictException("device " + deviceId
                    + " is not expected at stage " + (idx + 1));
        }
        if (reportedHash != null && !reportedHash.equals(stage.get("sha256"))) {
            throw new StateConflictException("receipt hash does not match the snapshot binary");
        }
        if (reportedVersion != null && !reportedVersion.equals(stage.get("to_version"))) {
            throw new StateConflictException("receipt version does not match the snapshot target");
        }
        List<String> acked = new ArrayList<>(
                (List<String>) stage.getOrDefault("acked_devices", List.of()));
        if (!acked.contains(deviceId)) {
            acked.add(deviceId);
            stage.put("acked_devices", acked);
        }
        seenReceipts.add(receiptId);
        rollout.put("receipts", new ArrayList<>(seenReceipts));

        boolean advanced = false;
        if (new LinkedHashSet<>(acked).containsAll(expected)) {
            stage.put("status", "complete");
            rollout.put("stage_index", idx + 1);
            if (idx + 1 >= stages.size()) {
                rollout.put("status", "complete");
                rollout.put("completed_at", java.time.Instant.now().toString());
            }
            advanced = true;
        } else {
            stage.put("status", "in_progress");
        }
        persist(rollout);
        store.appendEvent("receipt_recorded", Map.of(
                "rollout_id", rolloutId, "receipt_id", receiptId,
                "device_id", deviceId, "stage", idx + 1, "advanced", advanced));
        rollout.put("_replayed", false);
        rollout.put("_advanced", advanced);
        return rollout;
    }

    /** Freezes a cohort: its devices are pinned out of subsequent recomputation. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> freezeCohort(String rolloutId, String cohort, boolean frozen) {
        Map<String, Object> rollout = load(rolloutId);
        List<String> frozenCohorts = new ArrayList<>(
                (List<String>) rollout.getOrDefault("frozen_cohorts", List.of()));
        if (frozen && !frozenCohorts.contains(cohort)) {
            frozenCohorts.add(cohort);
        } else if (!frozen) {
            frozenCohorts.remove(cohort);
        }
        rollout.put("frozen_cohorts", frozenCohorts);
        persist(rollout);
        store.appendEvent("cohort_freeze_changed", Map.of(
                "rollout_id", rolloutId, "cohort", cohort, "frozen", frozen));
        return rollout;
    }

    /** Recomputes the draft for all non-frozen cohorts without touching the frozen ones. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> recompute(String rolloutId, List<DeviceState> allDevices) {
        Map<String, Object> rollout = load(rolloutId);
        if ("approved".equals(rollout.get("status")) || "complete".equals(rollout.get("status"))) {
            throw new StateConflictException("approved rollouts are bound to their snapshot");
        }
        Set<String> cohorts = new LinkedHashSet<>(
                (List<String>) rollout.getOrDefault("cohorts", List.of()));
        List<String> frozenCohortNames = (List<String>) rollout.get("frozen_cohorts");
        Map<String, String> targets = (Map<String, String>) rollout.get("targets");

        List<DeviceState> movable = new ArrayList<>();
        List<String> pinned = new ArrayList<>();
        for (DeviceState device : allDevices) {
            if (!cohorts.contains(device.cohort())) {
                continue;
            }
            if (device.frozen() || frozenCohortNames.contains(device.cohort())) {
                pinned.add(device.deviceId());
            } else {
                movable.add(device);
            }
        }
        Map<String, Object> draft = draft(movable, targets, cohorts);
        draft.put("pinned_by_freeze", pinned);
        rollout.put("draft", draft);
        rollout.put("stages", draft.get("stages"));
        rollout.put("stage_index", 0);
        persist(rollout);
        store.appendEvent("rollout_recomputed", Map.of(
                "rollout_id", rolloutId, "pinned_device_count", pinned.size()));
        return rollout;
    }

    // ----------------------------------------------------------------- export

    @SuppressWarnings("unchecked")
    public Map<String, Object> buildExport(String rolloutId) {
        Map<String, Object> rollout = load(rolloutId);
        Map<String, Object> snapshot = store.readJsonIfExists(store.snapshotFile(rolloutId))
                .orElse(Map.of());
        Map<String, Object> draft = (Map<String, Object>) rollout.get("draft");
        List<Map<String, Object>> failureBranches = new ArrayList<>();
        List<Map<String, Object>> conflictDevices = new ArrayList<>();
        for (Map<String, Object> plan :
                (List<Map<String, Object>>) draft.get("device_plans")) {
            String deviceId = String.valueOf(plan.get("device_id"));
            if (Boolean.TRUE.equals(plan.get("feasible"))) {
                for (Object simObj : (List<?>) plan.getOrDefault("simulation", List.of())) {
                    Map<String, Object> sim = (Map<String, Object>) simObj;
                    for (Object fpObj : (List<?>) sim.get("fault_points")) {
                        Map<String, Object> fp = (Map<String, Object>) fpObj;
                        Map<String, Object> branch = new LinkedHashMap<>();
                        branch.put("device_id", deviceId);
                        branch.put("step_order", sim.get("order"));
                        branch.put("component", sim.get("component"));
                        branch.putAll(fp);
                        failureBranches.add(branch);
                    }
                }
            } else {
                Map<String, Object> conflict = new LinkedHashMap<>();
                conflict.put("device_id", deviceId);
                conflict.put("kind", plan.get("kind"));
                conflict.put("conflict_set", plan.get("conflict_set"));
                conflictDevices.add(conflict);
            }
        }
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("rollout_id", rolloutId);
        export.put("exported_at", java.time.Instant.now().toString());
        export.put("status", rollout.get("status"));
        export.put("decision_basis", Map.of(
                "targets", rollout.get("targets"),
                "cohorts", rollout.get("cohorts"),
                "frozen_cohorts", rollout.get("frozen_cohorts"),
                "snapshot_id", rollout.get("snapshot_id"),
                "selection_policy",
                "dependency-interval BFS over signed packages; never newest-version-first"));
        export.put("signature_verification", verificationSummary(snapshot));
        export.put("stages", rollout.get("stages"));
        export.put("receipts", rollout.get("receipts"));
        export.put("failure_branches", failureBranches);
        export.put("conflict_devices", conflictDevices);
        export.put("bound_snapshot", snapshot);
        store.writeJson(store.exportFile(rolloutId), export);
        store.appendEvent("rollout_exported", Map.of("rollout_id", rolloutId,
                "failure_branch_count", failureBranches.size()));
        return export;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> verificationSummary(Map<String, Object> snapshot) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> packages = (Map<String, Object>) snapshot.getOrDefault("packages",
                Map.of());
        int valid = 0;
        List<Map<String, Object>> details = new ArrayList<>();
        for (Object value : packages.values()) {
            Map<String, Object> pkg = (Map<String, Object>) value;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("package_id", pkg.get("package_id"));
            d.put("version", pkg.get("version"));
            d.put("sha256", pkg.get("sha256"));
            d.put("signer", pkg.get("signer"));
            d.put("signature_present", pkg.get("signature") instanceof String s && !s.isEmpty());
            details.add(d);
            valid++;
        }
        summary.put("scheme", fw.crypto.SignatureVerifier.SCHEME);
        summary.put("verified_package_count", valid);
        summary.put("packages", details);
        summary.put("note", "records in the snapshot were all signature-verified on import");
        return summary;
    }

    // -------------------------------------------------------------- internals

    public Map<String, Object> load(String rolloutId) {
        return store.readJsonIfExists(store.rolloutFile(rolloutId))
                .orElseThrow(() -> new StateConflictException("unknown rollout " + rolloutId));
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PathHolder holder : listFiles()) {
            store.readJsonIfExists(holder.path).ifPresent(out::add);
        }
        return out;
    }

    private record PathHolder(Path path) {
    }

    private List<PathHolder> listFiles() {
        List<PathHolder> out = new ArrayList<>();
        store.list(store.derivedDir().resolve("rollouts"))
                .forEach(p -> out.add(new PathHolder(p)));
        return out;
    }

    private void persist(Map<String, Object> rollout) {
        String id = String.valueOf(rollout.get("id"));
        store.writeJson(store.rolloutFile(id), rollout);
    }

    public static final class StateConflictException extends RuntimeException {
        public StateConflictException(String message) {
            super(message);
        }
    }
}
