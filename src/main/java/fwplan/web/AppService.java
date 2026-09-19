package fwplan.web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fwplan.crypto.SignerRegistry;
import fwplan.model.Cohort;
import fwplan.model.Conflict;
import fwplan.model.DeviceState;
import fwplan.model.FamilyManifest;
import fwplan.model.FirmwareArtifact;
import fwplan.model.Plan;
import fwplan.model.Receipt;
import fwplan.model.ReleasePlan;
import fwplan.plan.Catalog;
import fwplan.plan.ConflictStateException;
import fwplan.plan.Importer;
import fwplan.plan.Planner;
import fwplan.plan.ValidationException;
import fwplan.sim.SimulationReport;
import fwplan.sim.Simulator;
import fwplan.store.Store;
import fwplan.util.Json;

/**
 * 应用服务：所有关键判定都在这里（服务端），前端只做展示。
 * 负责原始输入/派生结果/事件三类存储的编排与幂等。
 */
public final class AppService {

    private final Store store;
    private final Catalog catalog = new Catalog();
    private final SignerRegistry signers;
    private final Planner planner;
    private final Simulator simulator;

    public AppService(Store store) {
        this.store = store;
        this.signers = SignerRegistry.load(store.rawDir.resolve("trusted_signers.json"));
        this.planner = new Planner(catalog);
        this.simulator = new Simulator(catalog);
        loadAll();
    }

    private void loadAll() {
        store.startupRecover();
        for (Path file : store.listFiles(store.rawDir.resolve("manifests"), ".json")) {
            catalog.putManifest(Importer.parseManifest(store.readString(file)));
        }
        for (Path file : store.listFiles(store.rawDir.resolve("firmware"), ".json")) {
            FirmwareArtifact artifact = Importer.parseArtifact(store.readString(file), signers);
            catalog.putArtifact(artifact);
        }
    }

    // ---------- 查询 ----------

    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> families = new ArrayList<>();
        for (FamilyManifest m : catalog.manifests()) families.add(m.toMap());
        out.put("families", families);
        List<Object> artifacts = new ArrayList<>();
        for (FirmwareArtifact a : catalog.artifacts()) artifacts.add(a.toMap());
        out.put("firmware", artifacts);
        out.put("trustedSigners", new ArrayList<>(signers.describe().keySet()));
        out.put("cohorts", cohorts().stream().map(Cohort::toMap).toList());
        out.put("releases", releases().stream().map(ReleasePlan::toMap).toList());
        return out;
    }

    public synchronized boolean hasSigners() { return !signers.isEmpty(); }

    public synchronized void registerSigner(String name, java.security.PublicKey key) {
        signers.register(name, key);
        Path file = store.rawDir.resolve("trusted_signers.json");
        store.writeJsonAtomic(file, signers.describe());
        store.appendEventOnly("SIGNER_TRUSTED", Map.of("signer", name));
    }

    public synchronized boolean isEmpty() {
        return catalog.manifests().isEmpty();
    }

    /** 测试/演示辅助：批准之后再出现一个新的 mcu 签名版本，用于验证快照隔离。 */
    public synchronized void registerNewFirmwareAfterApproval() {
        fwplan.demo.SeedData.addLateSignedVersion(this);
    }

    public synchronized List<Cohort> cohorts() {
        List<Cohort> out = new ArrayList<>();
        for (Path file : store.listFiles(store.rawDir.resolve("cohorts"), ".json")) {
            out.add(Cohort.fromMap(store.readJson(file)));
        }
        return out;
    }

    public synchronized List<ReleasePlan> releases() {
        List<ReleasePlan> out = new ArrayList<>();
        for (Path file : store.listFiles(store.derivedDir.resolve("releases"), ".json")) {
            out.add(ReleasePlan.fromMap(store.readJson(file)));
        }
        return out;
    }

    // ---------- 原始输入导入 ----------

    public synchronized Map<String, Object> importManifest(String body) {
        FamilyManifest manifest = Importer.parseManifest(body);
        Path file = store.rawDir.resolve("manifests").resolve(manifest.familyId() + ".json");
        if (store.readJson(file) != null) {
            throw new ConflictStateException("设备族清单已存在: " + manifest.familyId());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("familyId", manifest.familyId());
        store.commit("MANIFEST_IMPORTED", payload, file, manifest.toMap());
        catalog.putManifest(manifest);
        return manifest.toMap();
    }

    public synchronized Map<String, Object> importFirmware(String body) {
        FirmwareArtifact artifact = Importer.parseArtifact(body, signers);
        if (catalog.manifest(artifact.familyId()).isEmpty()) {
            throw new ValidationException("必须先导入设备族清单: " + artifact.familyId());
        }
        FamilyManifest manifest = catalog.manifest(artifact.familyId()).orElseThrow();
        if (!manifest.hasComponent(artifact.component())) {
            throw new ValidationException("清单中没有组件: " + artifact.component());
        }
        for (String peer : artifact.companions().keySet()) {
            if (!manifest.hasComponent(peer)) {
                throw new ValidationException("companion 引用了清单中不存在的组件: " + peer);
            }
        }
        boolean added = catalog.putArtifact(artifact);
        if (!added) return artifact.toMap();
        Path file = store.rawDir.resolve("firmware").resolve(artifact.sha256() + ".json");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sha256", artifact.sha256());
        payload.put("name", artifact.nameKey());
        store.commit("FIRMWARE_IMPORTED", payload, file, artifact.toMap());
        return artifact.toMap();
    }

    // ---------- 规划 / 模拟 / 兼容矩阵 ----------

    public Plan analyze(String familyId, String hw, Map<String, Object> currentMap,
                        Map<String, Object> targetMap, boolean persist, String key) {
        DeviceState current = DeviceState.fromMap(currentMap);
        DeviceState target = DeviceState.fromMap(targetMap);
        Plan plan = planner.analyze(familyId, hw, current, target);
        if (persist && key != null) {
            Path dir = store.derivedDir.resolve("plans");
            store.writeJsonAtomic(dir.resolve(safe(key) + ".json"), plan.toMap());
        }
        return plan;
    }

    public SimulationReport simulate(Plan plan) {
        return simulator.simulate(plan);
    }

    public synchronized Map<String, Object> analyzeCohort(String cohortId) {
        Cohort cohort = cohortOrThrow(cohortId);
        Plan plan = planner.analyze(cohort.familyId(), cohort.hwRevision(),
                cohort.current(), cohort.target());
        SimulationReport sim = plan.feasible() ? simulator.simulate(plan) : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cohort", cohort.toMap());
        out.put("plan", plan.toMap());
        out.put("simulation", sim == null ? null : sim.toMap());
        out.put("compatibilityMatrix", compatibilityMatrix(cohort.familyId(), cohort.hwRevision()));
        Path dir = store.derivedDir.resolve("analyses");
        store.writeJsonAtomic(dir.resolve(safe(cohortId) + ".json"), out);
        return out;
    }

    public Map<String, Object> compatibilityMatrix(String familyId, String hw) {
        FamilyManifest manifest = catalog.manifest(familyId)
                .orElseThrow(() -> new ValidationException("未知设备族: " + familyId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("familyId", familyId);
        result.put("hwRevision", hw);
        List<Object> rows = new ArrayList<>();
        for (String component : manifest.components()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("component", component);
            List<Object> versionCells = new ArrayList<>();
            for (FirmwareArtifact a : catalog.versions(familyId, component)) {
                if (!a.supportsHw(hw)) continue;
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("version", a.version());
                cell.put("firmwareId", a.id());
                cell.put("sha256", a.sha256());
                cell.put("minBootloader", a.minBootloader() == null ? "" : a.minBootloader());
                cell.put("storageEpoch", a.storageEpoch());
                List<Object> comps = new ArrayList<>();
                for (Map.Entry<String, String> e : a.companions().entrySet()) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("component", e.getKey());
                    c.put("range", e.getValue());
                    List<String> satisfying = new ArrayList<>();
                    fwplan.model.Version.Range range = fwplan.model.Version.Range.parse(e.getValue());
                    for (FirmwareArtifact peer : catalog.versions(familyId, e.getKey())) {
                        if (peer.supportsHw(hw)
                                && range.contains(fwplan.model.Version.of(peer.version()))) {
                            satisfying.add(peer.version());
                        }
                    }
                    c.put("satisfyingVersions", satisfying);
                    c.put("commonVersionExists", !satisfying.isEmpty());
                    comps.add(c);
                }
                cell.put("companions", comps);
                versionCells.add(cell);
            }
            row.put("versions", versionCells);
            rows.add(row);
        }
        result.put("components", rows);
        List<Object> gates = new ArrayList<>();
        for (FamilyManifest.Gate gate : manifest.gates()) gates.add(gate.toMap());
        result.put("gates", gates);
        return result;
    }

    // ---------- cohort 冻结与重算 ----------

    public synchronized Cohort upsertCohort(Map<String, Object> body) {
        String cohortId = Json.str(body, "cohortId");
        if (cohortId == null || cohortId.isBlank()) {
            cohortId = "cohort-" + (cohorts().size() + 1) + "-" + shortId();
        }
        Cohort existing = cohortOrNull(cohortId);
        if (existing != null && existing.frozen()) {
            throw new ConflictStateException("cohort 已冻结，不能修改: " + cohortId);
        }
        String familyId = Json.requireStr(body, "familyId");
        String hw = Json.requireStr(body, "hwRevision");
        if (catalog.manifest(familyId).isEmpty()) throw new ValidationException("未知设备族: " + familyId);
        if (!catalog.manifest(familyId).orElseThrow().hwRevisions().contains(hw)) {
            throw new ValidationException("硬件修订不在清单中: " + hw);
        }
        String label = Json.str(body, "label");
        if (label == null) label = cohortId;
        DeviceState current = DeviceState.fromMap(Json.obj(body, "current"));
        DeviceState target = DeviceState.fromMap(Json.obj(body, "target"));
        boolean frozen = Boolean.TRUE.equals(body.get("frozen"));
        Cohort cohort = new Cohort(cohortId, label, familyId, hw, current, target, frozen);
        Path file = store.rawDir.resolve("cohorts").resolve(safe(cohortId) + ".json");
        store.commit("COHORT_UPSERTED", cohort.toMap(), file, cohort.toMap());
        return cohort;
    }

    public synchronized Cohort setFrozen(String cohortId, boolean frozen) {
        Cohort cohort = cohortOrThrow(cohortId);
        Cohort updated = cohort.withFrozen(frozen);
        Path file = store.rawDir.resolve("cohorts").resolve(safe(cohortId) + ".json");
        store.commit("COHORT_FROZEN", updated.toMap(), file, updated.toMap());
        return updated;
    }

    /** 重算：冻结 cohort 原样保留，只对未冻结的用最新元数据重新规划。 */
    public synchronized Map<String, Object> recompute() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> frozenKept = new ArrayList<>();
        List<Object> recomputed = new ArrayList<>();
        for (Cohort cohort : cohorts()) {
            if (cohort.frozen()) {
                frozenKept.add(cohort.toMap());
                continue;
            }
            recomputed.add(analyzeCohort(cohort.cohortId()));
        }
        out.put("frozenKept", frozenKept);
        out.put("recomputed", recomputed);
        store.appendEventOnly("BATCH_RECOMPUTED", out);
        return out;
    }

    // ---------- 发布方案 ----------

    public synchronized ReleasePlan createRelease(Map<String, Object> body) {
        String releaseId = Json.str(body, "releaseId");
        if (releaseId == null || releaseId.isBlank()) releaseId = "rel-" + shortId();
        String displayName = Json.str(body, "displayName");
        if (displayName == null) displayName = releaseId;
        List<String> cohortIds = new ArrayList<>();
        for (Object id : Json.list(body, "cohortIds")) cohortIds.add(String.valueOf(id));
        if (cohortIds.isEmpty()) throw new ValidationException("至少选择一个 cohort");
        Path existing = store.derivedDir.resolve("releases").resolve(safe(releaseId) + ".json");
        if (store.readJson(existing) != null) {
            throw new ConflictStateException("发布方案已存在: " + releaseId);
        }

        List<ReleasePlan.Phase> phases = new ArrayList<>();
        int order = 0;
        for (String cohortId : cohortIds) {
            Cohort cohort = cohortOrThrow(cohortId);
            Map<String, Object> analysis = analyzeCohort(cohortId);
            Map<String, Object> planMap = Json.obj(analysis, "plan");
            if (!Boolean.TRUE.equals(planMap.get("feasible"))) {
                throw new ConflictStateException("cohort 不可行，不能发布: " + cohortId);
            }
            Map<String, Object> simulation = Json.obj(analysis, "simulation");
            boolean allSafe = Boolean.TRUE.equals(simulation.get("allSafe"));
            List<String> updateOrder = new ArrayList<>();
            for (Object step : Json.list(simulation, "steps")) {
                Map<String, Object> stepMap = (Map<String, Object>) step;
                updateOrder.add(String.valueOf(Json.obj(stepMap, "step").get("firmwareId")));
            }
            String health = "全部步骤健康检查通过，存储自检与遥测正常";
            ReleasePlan.Phase phase = new ReleasePlan.Phase(order++, cohortId,
                    "阶段 " + order + "：" + cohort.label(), updateOrder, health,
                    cohort.current().toMap().toString(), allSafe);
            phases.add(phase);
        }
        ReleasePlan release = new ReleasePlan(releaseId, displayName, cohortIds, phases,
                "draft", store.nowIso(), null, new LinkedHashMap<>());
        Path file = store.derivedDir.resolve("releases").resolve(safe(releaseId) + ".json");
        store.commit("RELEASE_CREATED", release.toMap(), file, release.toMap());
        return release;
    }

    /** 批准：绑定清单与元数据快照；之后的新上传不影响该方案。 */
    public synchronized ReleasePlan approveRelease(String releaseId) {
        ReleasePlan release = releaseOrThrow(releaseId);
        if (!"draft".equals(release.status())) {
            throw new ConflictStateException("只有 draft 方案可以批准，当前状态: " + release.status());
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("capturedAt", store.nowIso());
        List<Object> familySnapshots = new ArrayList<>();
        List<Object> artifactSnapshots = new ArrayList<>();
        for (String cohortId : release.cohortIds()) {
            Cohort cohort = cohortOrThrow(cohortId);
            FamilyManifest manifest = catalog.manifest(cohort.familyId()).orElseThrow();
            familySnapshots.add(manifest.toMap());
            Map<String, Object> analysis = analyzeCohort(cohortId);
            Map<String, Object> cohortSnapshot = new LinkedHashMap<>();
            cohortSnapshot.put("cohort", cohort.toMap());
            cohortSnapshot.put("analysis", analysis);
            artifactSnapshots.add(cohortSnapshot);
        }
        snapshot.put("manifests", familySnapshots);
        snapshot.put("cohorts", artifactSnapshots);
        ReleasePlan approved = release.withStatus("approved", store.nowIso(), snapshot);
        Path file = store.derivedDir.resolve("releases").resolve(safe(releaseId) + ".json");
        store.commit("RELEASE_APPROVED", Map.of("releaseId", releaseId), file, approved.toMap());
        return approved;
    }

    public synchronized ReleasePlan releaseOrThrow(String releaseId) {
        Path file = store.derivedDir.resolve("releases").resolve(safe(releaseId) + ".json");
        Map<String, Object> map = store.readJson(file);
        if (map == null) throw new ValidationException("发布方案不存在: " + releaseId);
        return ReleasePlan.fromMap(map);
    }

    // ---------- 设备推进回执（幂等） ----------

    public synchronized Receipt advanceDevice(Map<String, Object> body) {
        String requestId = Json.requireStr(body, "requestId");
        String releaseId = Json.requireStr(body, "releaseId");
        String cohortId = Json.requireStr(body, "cohortId");
        String deviceId = Json.requireStr(body, "deviceId");

        Path receiptFile = store.derivedDir.resolve("receipts").resolve(safe(requestId) + ".json");
        Map<String, Object> existing = store.readJson(receiptFile);
        if (existing != null) {
            Receipt replay = Receipt.fromMap(existing).asReplay();
            store.appendEventOnly("RECEIPT_REPLAYED", replay.toMap());
            return replay;
        }

        ReleasePlan release;
        try {
            release = releaseOrThrow(releaseId);
        } catch (ValidationException e) {
            throw new ConflictStateException(e.getMessage());
        }
        if (!"approved".equals(release.status())) {
            throw new ConflictStateException("方案尚未批准，不能推进设备: " + releaseId);
        }
        if (!release.cohortIds().contains(cohortId)) {
            throw new ConflictStateException("cohort 不在该方案中: " + cohortId);
        }

        List<ReleasePlan.Phase> cohortPhases = release.phases().stream()
                .filter(p -> p.cohortId().equals(cohortId))
                .sorted(java.util.Comparator.comparingInt(ReleasePlan.Phase::order))
                .toList();
        if (cohortPhases.isEmpty()) {
            throw new ConflictStateException("cohort 在方案中没有阶段: " + cohortId);
        }
        int lastPhase = deviceLastPhase(releaseId, deviceId);
        int nextIndex;
        if (lastPhase < 0) {
            nextIndex = 0;
        } else {
            int previousIndex = -1;
            for (int i = 0; i < cohortPhases.size(); i++) {
                if (cohortPhases.get(i).order() == lastPhase) { previousIndex = i; break; }
            }
            nextIndex = previousIndex + 1;
        }
        if (nextIndex >= cohortPhases.size()) {
            throw new ConflictStateException("设备已在该 cohort 的最后阶段，不能重复推进");
        }
        ReleasePlan.Phase phase = cohortPhases.get(nextIndex);

        Receipt receipt = new Receipt(requestId, releaseId, cohortId, deviceId, "advance",
                "phase-" + lastPhase, "phase-" + phase.order(), "ACCEPTED",
                phase.safeRollback() ? "阶段推进已记录" : "阶段推进已记录；该阶段无安全回滚，已标红",
                store.nowIso(), false);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("receipt", receipt.toMap());
        store.commit("RECEIPT_RECORDED", payload, receiptFile, receipt.toMap());
        return receipt;
    }

    private int deviceLastPhase(String releaseId, String deviceId) {
        int last = -1;
        for (Path file : store.listFiles(store.derivedDir.resolve("receipts"), ".json")) {
            Receipt r = Receipt.fromMap(store.readJson(file));
            if (r.releaseId().equals(releaseId) && r.deviceId().equals(deviceId)
                    && "ACCEPTED".equals(r.status()) && r.toPhase() != null) {
                last = Math.max(last, Integer.parseInt(r.toPhase().replace("phase-", "")));
            }
        }
        return last;
    }

    // ---------- 导出 ----------

    public synchronized Map<String, Object> exportRelease(String releaseId) {
        ReleasePlan release = releaseOrThrow(releaseId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("release", release.toMap());
        out.put("decisionBasis", release.snapshot());
        Map<String, Object> signatures = new LinkedHashMap<>();
        signatures.put("scheme", "SHA256withRSA over firmware sha256");
        signatures.put("trustedSigners", signers.describe());
        List<Object> firmwareChecks = new ArrayList<>();
        for (FirmwareArtifact artifact : catalog.artifacts()) {
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("name", artifact.nameKey());
            check.put("sha256", artifact.sha256());
            check.put("signer", artifact.signer());
            check.put("valid", fwplan.crypto.Signing.verifySha256(
                    signers.key(artifact.signer()), artifact.sha256(), artifact.signature()));
            firmwareChecks.add(check);
        }
        signatures.put("firmwareChecks", firmwareChecks);
        out.put("signatureVerification", signatures);

        List<Object> failureBranches = new ArrayList<>();
        Object cohortSnapshots = release.snapshot().get("cohorts");
        if (cohortSnapshots instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> cohortSnapshot = (Map<String, Object>) item;
                Map<String, Object> analysis = Json.obj(cohortSnapshot, "analysis");
                Map<String, Object> simulation = Json.obj(analysis, "simulation");
                for (Object step : Json.list(simulation, "steps")) {
                    Map<String, Object> stepMap = (Map<String, Object>) step;
                    failureBranches.addAll(Json.list(stepMap, "failurePoints"));
                }
                Map<String, Object> planMap = Json.obj(analysis, "plan");
                failureBranches.addAll(Json.list(planMap, "conflicts"));
            }
        }
        out.put("failureBranches", failureBranches);

        Path file = store.derivedDir.resolve("exports").resolve(safe(releaseId) + ".json");
        store.writeJsonAtomic(file, out);
        store.appendEventOnly("RELEASE_EXPORTED", Map.of("releaseId", releaseId));
        return out;
    }

    // ---------- helpers ----------

    private Cohort cohortOrThrow(String cohortId) {
        Path file = store.rawDir.resolve("cohorts").resolve(safe(cohortId) + ".json");
        Map<String, Object> map = store.readJson(file);
        if (map == null) throw new ValidationException("cohort 不存在: " + cohortId);
        return Cohort.fromMap(map);
    }

    private Cohort cohortOrNull(String cohortId) {
        Path file = store.rawDir.resolve("cohorts").resolve(safe(cohortId) + ".json");
        Map<String, Object> map = store.readJson(file);
        return map == null ? null : Cohort.fromMap(map);
    }

    private static String safe(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String shortId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
