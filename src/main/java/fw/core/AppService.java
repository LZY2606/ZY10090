package fw.core;

import fw.api.Errors;
import fw.crypto.SignatureVerifier;
import fw.json.Json;
import fw.model.DeviceFamily;
import fw.model.DeviceState;
import fw.model.FirmwarePackage;
import fw.store.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Application boundary: every decision (signature verification, dependency
 * interval solving, conflict-set extraction, rollout state transitions) happens
 * here on the server. The browser is a thin view over these operations.
 */
public final class AppService {

    private final Store store;
    private final Catalog catalog;
    private final Planner planner;
    private final SignatureVerifier verifier;
    private final RolloutManager rollouts;

    public AppService(Store store, SignatureVerifier verifier) {
        this.store = store;
        this.verifier = verifier;
        this.catalog = new Catalog();
        this.planner = new Planner(catalog);
        this.rollouts = new RolloutManager(store, planner, catalog);
        bootstrap();
    }

    // ------------------------------------------------------------ bootstrapping

    private void bootstrap() {
        for (var file : store.list(store.rawDir().resolve("families"))) {
            store.readJsonIfExists(file).ifPresent(m ->
                    catalog.addFamily(DeviceFamily.fromMap(m)));
        }
        for (var file : store.list(store.rawDir().resolve("packages"))) {
            store.readJsonIfExists(file).ifPresent(m ->
                    catalog.addPackage(FirmwarePackage.fromVerified(m)));
        }
    }

    public Catalog catalog() {
        return catalog;
    }

    public Store store() {
        return store;
    }

    public SignatureVerifier verifier() {
        return verifier;
    }

    // --------------------------------------------------------------- imports

    public synchronized Map<String, Object> importFamily(Map<String, Object> body) {
        DeviceFamily family;
        try {
            family = DeviceFamily.fromMap(body);
        } catch (RuntimeException ex) {
            throw new Errors.BadRequest("invalid_family_manifest",
                    "family manifest invalid: " + ex.getMessage());
        }
        var existing = store.familyFile(family.family());
        if (java.nio.file.Files.exists(existing)) {
            throw new Errors.StateConflict("family '" + family.family()
                    + "' already exists; imports are immutable");
        }
        Map<String, Object> record = family.toMap();
        record.put("imported_at", java.time.Instant.now().toString());
        store.writeJson(existing, record);
        catalog.addFamily(family);
        store.appendEvent("family_imported", Map.of("family", family.family()));
        return record;
    }

    public synchronized Map<String, Object> importPackage(Map<String, Object> body) {
        SignatureVerifier.Result result;
        try {
            result = verifier.verify(body);
        } catch (RuntimeException ex) {
            throw new Errors.BadRequest("signature_check_error",
                    "malformed signature block: " + ex.getMessage());
        }
        if (!result.ok()) {
            throw new Errors.BadRequest("bad_signature",
                    "firmware metadata failed signature verification: " + result.detail());
        }
        FirmwarePackage pkg;
        try {
            pkg = FirmwarePackage.fromVerified(body);
        } catch (RuntimeException ex) {
            throw new Errors.BadRequest("invalid_package_metadata", ex.getMessage());
        }
        if (!pkg.signer().isEmpty() && result.signer() != null
                && !result.signer().equals(pkg.signer())
                && !result.signer().equals(verifierKeyFallback(pkg))) {
            throw new Errors.BadRequest("signer_mismatch",
                    "signature key does not match declared signer " + pkg.signer());
        }
        validatePackageReferences(pkg);
        Catalog.PutResult put = catalog.addPackage(pkg);
        if ("name_tamper".equals(put.status())) {
            throw new Errors.StateConflict("package " + pkg.packageId() + " " + pkg.version()
                    + " already exists with a different sha256 ("
                    + put.pkg().sha256().substring(0, 12)
                    + "); same-name binaries may not replace each other");
        }
        if ("added".equals(put.status())) {
            Map<String, Object> record = pkg.toMap();
            record.put("imported_at", java.time.Instant.now().toString());
            record.put("signature_scheme", result.scheme());
            record.put("signature_detail", result.detail());
            store.writeJson(store.packageFile(pkg.storageKey()), record);
            store.appendEvent("package_imported", Map.of(
                    "package_id", pkg.packageId(), "version", pkg.version(),
                    "sha256", pkg.sha256(), "signer", String.valueOf(result.signer())));
        }
        return Map.of("status", put.status(), "package", pkg.toMap(),
                "signature", Map.of("valid", true, "scheme", result.scheme(),
                        "signer", result.signer()));
    }

    private String verifierKeyFallback(FirmwarePackage pkg) {
        return pkg.signer();
    }

    private void validatePackageReferences(FirmwarePackage pkg) {
        DeviceFamily family = catalog.family(pkg.family());
        if (family == null) {
            throw new Errors.BadRequest("unknown_family",
                    "package references unknown family '" + pkg.family() + "'");
        }
        if (!family.components().containsKey(pkg.component())) {
            throw new Errors.BadRequest("unknown_component",
                    "family " + pkg.family() + " has no component '" + pkg.component() + "'");
        }
        for (String required : pkg.requires().keySet()) {
            if (!family.components().containsKey(required)) {
                throw new Errors.BadRequest("unknown_requirement",
                        "requires references unknown component '" + required + "'");
            }
        }
        if (pkg.bootloaderMin() != null && !fw.model.Version.isValid(pkg.bootloaderMin())) {
            throw new Errors.BadRequest("invalid_bootloader_min", pkg.bootloaderMin());
        }
        if (pkg.maxFromVersion() != null && !fw.model.Version.isValid(pkg.maxFromVersion())) {
            throw new Errors.BadRequest("invalid_max_from_version", pkg.maxFromVersion());
        }
        for (String rev : pkg.hwCompatibility().values()) {
            if (!"*".equals(rev) && !fw.model.Version.isValid(rev)
                    && !family.hasRevision(rev)) {
                throw new Errors.BadRequest("invalid_hw_compatibility",
                        "'" + rev + "' is not a revision of family " + pkg.family());
            }
        }
    }

    // ---------------------------------------------------------------- devices

    public synchronized Map<String, Object> upsertDevice(Map<String, Object> body) {
        DeviceState device;
        try {
            device = DeviceState.fromMap(body);
        } catch (RuntimeException ex) {
            throw new Errors.BadRequest("invalid_device", ex.getMessage());
        }
        DeviceFamily family = catalog.family(device.family());
        if (family == null) {
            throw new Errors.BadRequest("unknown_family",
                    "device references unknown family '" + device.family() + "'");
        }
        if (!family.hasRevision(device.revision())) {
            throw new Errors.BadRequest("unknown_revision",
                    "family has no revision '" + device.revision() + "'");
        }
        for (Map.Entry<String, String> e : device.components().entrySet()) {
            if (!family.components().containsKey(e.getKey())) {
                throw new Errors.BadRequest("unknown_component", e.getKey());
            }
            FirmwarePackage pkg = catalog.resolve(device.family(), e.getKey(), e.getValue());
            if (pkg == null) {
                throw new Errors.BadRequest("unverified_package",
                        "component " + e.getKey() + " references unknown/unverified package '"
                                + e.getValue() + "'");
            }
        }
        boolean existed = java.nio.file.Files.exists(store.deviceFile(device.deviceId()));
        Map<String, Object> record = device.toMap();
        record.put("updated_at", java.time.Instant.now().toString());
        store.writeJson(store.deviceFile(device.deviceId()), record);
        store.appendEvent(existed ? "device_updated" : "device_registered",
                Map.of("device_id", device.deviceId(), "cohort", device.cohort()));
        return record;
    }

    public synchronized List<Map<String, Object>> listDevices() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var file : store.list(store.derivedDir().resolve("devices"))) {
            store.readJsonIfExists(file).ifPresent(out::add);
        }
        return out;
    }

    public synchronized Map<String, Object> setDeviceFrozen(String deviceId, boolean frozen) {
        DeviceState device = loadDevice(deviceId);
        DeviceState updated = device.withFrozen(frozen);
        Map<String, Object> record = updated.toMap();
        record.put("updated_at", java.time.Instant.now().toString());
        store.writeJson(store.deviceFile(deviceId), record);
        store.appendEvent("device_freeze_changed",
                Map.of("device_id", deviceId, "frozen", frozen));
        return record;
    }

    private DeviceState loadDevice(String deviceId) {
        Map<String, Object> m = store.readJsonIfExists(store.deviceFile(deviceId))
                .orElseThrow(() -> new Errors.NotFound("unknown device " + deviceId));
        return DeviceState.fromMap(m);
    }

    // ------------------------------------------------------------------ plans

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> computePlan(Map<String, Object> body) {
        Planner.Request req = new Planner.Request();
        try {
            req.family = Json.reqString(body, "family");
            req.revision = Json.reqString(body, "revision");
            Object cur = Json.reqObject(body, "current");
            for (Map.Entry<?, ?> e : ((Map<?, ?>) cur).entrySet()) {
                req.current.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            Object tgt = Json.reqObject(body, "targets");
            for (Map.Entry<?, ?> e : ((Map<?, ?>) tgt).entrySet()) {
                req.targets.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        } catch (RuntimeException ex) {
            throw new Errors.BadRequest("invalid_plan_request", ex.getMessage());
        }
        Map<String, Object> result = planner.plan(req);
        String planId = "plan-" + UUID.randomUUID();
        result.put("plan_id", planId);
        result.put("computed_at", java.time.Instant.now().toString());
        store.writeJson(store.planFile(planId), result);
        store.appendEvent("plan_computed", Map.of(
                "plan_id", planId, "family", req.family, "revision", req.revision,
                "feasible", result.get("feasible"), "kind", result.get("kind")));
        return result;
    }

    public synchronized Map<String, Object> matrix(String family) {
        if (catalog.family(family) == null) {
            throw new Errors.NotFound("unknown family " + family);
        }
        return planner.matrix(family);
    }

    // ---------------------------------------------------------------- rollouts

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> createRollout(Map<String, Object> body) {
        String name = Json.str(body, "name", "rollout");
        Map<String, String> targets = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : Json.reqObject(body, "targets").entrySet()) {
            targets.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        List<String> cohortList = new ArrayList<>();
        for (Object o : Json.reqList(body, "cohorts")) {
            cohortList.add(String.valueOf(o));
        }
        Set<String> cohorts = new LinkedHashSet<>(cohortList);
        List<DeviceState> devices = listDevices().stream().map(DeviceState::fromMap).toList();
        return rollouts.createRollout(name, devices, targets, cohorts);
    }

    public Map<String, Object> approveRollout(String id) {
        return rollouts.approve(id);
    }

    public Map<String, Object> receipt(String id, Map<String, Object> body) {
        try {
            return rollouts.receipt(id, body);
        } catch (RolloutManager.StateConflictException ex) {
            throw new Errors.StateConflict(ex.getMessage());
        }
    }

    public Map<String, Object> freezeCohort(String id, String cohort, boolean frozen) {
        try {
            return rollouts.freezeCohort(id, cohort, frozen);
        } catch (RolloutManager.StateConflictException ex) {
            throw new Errors.StateConflict(ex.getMessage());
        }
    }

    public Map<String, Object> recomputeRollout(String id) {
        List<DeviceState> devices = listDevices().stream().map(DeviceState::fromMap).toList();
        try {
            return rollouts.recompute(id, devices);
        } catch (RolloutManager.StateConflictException ex) {
            throw new Errors.StateConflict(ex.getMessage());
        }
    }

    public Map<String, Object> getRollout(String id) {
        try {
            return rollouts.load(id);
        } catch (RolloutManager.StateConflictException ex) {
            throw new Errors.NotFound(ex.getMessage());
        }
    }

    public List<Map<String, Object>> listRollouts() {
        return rollouts.list();
    }

    public Map<String, Object> exportRollout(String id) {
        getRollout(id);
        return rollouts.buildExport(id);
    }

    // ------------------------------------------------------------------ events

    public List<Map<String, Object>> events() {
        return store.readEvents();
    }

    public Map<String, Object> overview() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> families = new ArrayList<>();
        for (DeviceFamily f : catalog.families()) {
            families.add(f.toMap());
        }
        m.put("families", families);
        List<Map<String, Object>> packages = new ArrayList<>();
        for (FirmwarePackage p : catalog.allPackages()) {
            packages.add(p.toMap());
        }
        m.put("packages", packages);
        m.put("devices", listDevices());
        m.put("rollouts", listRollouts());
        return m;
    }
}
