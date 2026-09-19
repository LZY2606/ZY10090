package fw;

import fw.core.AppService;
import fw.core.Catalog;
import fw.core.Planner;
import fw.crypto.SignatureVerifier;
import fw.model.FirmwarePackage;
import fw.store.Store;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared builder for engine tests: in-memory temp store, no HTTP involved. */
final class TestWorld {

    final Store store;
    final AppService app;
    final Catalog catalog;
    final SignatureVerifier verifier;
    private int seq;

    private TestWorld(Path data) {
        this.store = new Store(data);
        this.verifier = new SignatureVerifier().registerKey("k", "secret");
        this.app = new AppService(store, verifier);
        this.catalog = app.catalog();
    }

    static TestWorld create() {
        Path dir;
        try {
            dir = java.nio.file.Files.createTempDirectory("fw-test-");
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return new TestWorld(dir);
    }

    void family(String family, String[] components, String revision, String floor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("family", family);
        m.put("name", family);
        java.util.List<Map<String, Object>> revs = new java.util.ArrayList<>();
        revs.add(Map.of("id", revision, "label", revision, "bootloader_min", floor));
        m.put("revisions", revs);
        java.util.List<Map<String, Object>> comps = new java.util.ArrayList<>();
        for (String c : components) {
            comps.add(Map.of("id", c, "label", c));
        }
        m.put("components", comps);
        app.importFamily(m);
    }

    String packageKey(String family, String component, String version) {
        return catalog.candidates(family, component).stream()
                .filter(p -> p.version().equals(version)).findFirst().orElseThrow()
                .storageKey();
    }

    String pkg(String family, String component, String pid, String version, int format,
               String revision, Map<String, String> requires, String bootMin,
               String maxFrom, boolean safeRollback) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("package_id", pid);
        m.put("component", component);
        m.put("version", version);
        m.put("family", family);
        m.put("format_version", format);
        String hashInput = family + "|" + component + "|" + pid + "|" + version + "|" + (seq++);
        m.put("sha256", fw.crypto.Hashing.sha256Hex(hashInput));
        m.put("size", 100 + seq);
        m.put("signer", "tester");
        m.put("signed_at", "2026-01-01T00:00:00Z");
        m.put("safe_rollback", safeRollback);
        m.put("hw_compatibility", revision == null ? Map.of() : Map.of(revision, "*"));
        m.put("requires", new LinkedHashMap<>(requires));
        m.put("bootloader_min", bootMin);
        m.put("max_from_version", maxFrom);
        m.put("probe", null);
        m.put("health_check", null);
        m.put("signature", SignatureVerifier.sign(m, "k", "secret"));
        var result = app.importPackage(m);
        FirmwarePackage stored = (FirmwarePackage) null;
        @SuppressWarnings("unchecked")
        Map<String, Object> pkgMap = (Map<String, Object>) result.get("package");
        return String.valueOf(pkgMap.get("package_id"));
    }

    Planner.Request request(String family, String revision,
                            Map<String, String> current, Map<String, String> targets) {
        Planner.Request req = new Planner.Request();
        req.family = family;
        req.revision = revision;
        req.current = current;
        req.targets = targets;
        return req;
    }

    Map<String, Object> evaluate(Planner.Request req) {
        return new Planner(catalog).plan(req);
    }
}
