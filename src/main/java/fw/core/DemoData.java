package fw.core;

import fw.crypto.SignatureVerifier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signed reference dataset used for the first-run demo.
 *
 * <p>Family {@code ctrl-a} demonstrates a feasible multi-component upgrade
 * with bootloader floors, companion windows, a no-skip ceiling and storage
 * format bumps. Family {@code edge-b} contains deliberately unsatisfiable
 * cases: hardware revisions with no common firmware, and a storage-format
 * downgrade barrier.</p>
 */
public final class DemoData {

    private DemoData() {
    }

    public static void seed(AppService app, String keyId, String secret) {
        seedControlFamily(app, keyId, secret);
        seedEdgeFamily(app, keyId, secret);
    }

    private static void postFamily(AppService app, Map<String, Object> family) {
        app.importFamily(family);
    }

    private static Map<String, Object> signedPackage(AppService app, String keyId, String secret,
                                                     Map<String, Object> payload) {
        payload.remove("signature");
        payload.put("signature", SignatureVerifier.sign(payload, keyId, secret));
        app.importPackage(payload);
        return payload;
    }

    private static void postDevice(AppService app, Map<String, Object> device) {
        app.upsertDevice(device);
    }

    private static void seedControlFamily(AppService app, String keyId, String secret) {
        postFamily(app, familyControl());
        // bootloader packages
        signedPackage(app, keyId, secret, base("ctrl-bl", "bootloader", "1.0.0", "ctrl-a", 1,
                Map.of("rev-a", "*", "rev-b", "*", "rev-c", "*"),
                Map.of(), null, null, true));
        signedPackage(app, keyId, secret, base("ctrl-bl", "bootloader", "2.0.0", "ctrl-a", 1,
                Map.of("rev-b", "*", "rev-c", "*"),
                Map.of(), null, null, true));
        signedPackage(app, keyId, secret, base("ctrl-bl", "bootloader", "2.1.0", "ctrl-a", 1,
                Map.of("rev-c", "*"),
                Map.of(), null, null, true));
        // radio packages
        signedPackage(app, keyId, secret, base("ctrl-radio", "radio", "1.2.0", "ctrl-a", 1,
                Map.of("rev-a", "*", "rev-b", "*", "rev-c", "*"),
                Map.of("bootloader", "1.0.0..2.1.0"), null, null, true));
        signedPackage(app, keyId, secret, base("ctrl-radio", "radio", "1.4.0", "ctrl-a", 1,
                Map.of("rev-a", "*", "rev-b", "*", "rev-c", "*"),
                Map.of("bootloader", "1.0.0..2.1.0"), null, "1.2.0", true));
        signedPackage(app, keyId, secret, base("ctrl-radio", "radio", "2.0.0", "ctrl-a", 2,
                Map.of("rev-b", "*", "rev-c", "*"),
                Map.of("bootloader", "2.0.0..2.1.0", "main", "3.0.0..4.9.9"),
                null, null, false));
        // main packages
        signedPackage(app, keyId, secret, base("ctrl-main", "main", "2.0.0", "ctrl-a", 1,
                Map.of("rev-a", "*", "rev-b", "*", "rev-c", "*"),
                Map.of("bootloader", "1.0.0..2.1.0", "radio", "1.0.0..1.9.9"),
                null, null, true));
        signedPackage(app, keyId, secret, base("ctrl-main", "main", "3.0.0", "ctrl-a", 1,
                Map.of("rev-b", "*", "rev-c", "*"),
                Map.of("bootloader", "2.0.0..2.1.0", "radio", "1.2.0..2.0.0"),
                "2.0.0", null, true));
        signedPackage(app, keyId, secret, base("ctrl-main", "main", "4.0.0", "ctrl-a", 2,
                Map.of("rev-c", "*"),
                Map.of("bootloader", "2.1.0..2.1.0", "radio", "2.0.0..2.0.0"),
                "2.1.0", null, false));
        // format v3 follow-up: lets the demo show a genuine same-hardware
        // storage-format downgrade barrier (4.1 -> 4.0/3.0 is unsafe).
        signedPackage(app, keyId, secret, base("ctrl-main", "main", "4.1.0", "ctrl-a", 3,
                Map.of("rev-c", "*"),
                Map.of("bootloader", "2.1.0..2.1.0", "radio", "2.0.0..2.0.0"),
                "2.1.0", "4.0.0", false));
        // sensor and board management
        signedPackage(app, keyId, secret, base("ctrl-sensor", "sensor", "1.0.0", "ctrl-a", 1,
                Map.of("rev-a", "*", "rev-b", "*", "rev-c", "*"),
                Map.of(), null, null, true));
        signedPackage(app, keyId, secret, base("ctrl-sensor", "sensor", "3.0.0", "ctrl-a", 1,
                Map.of("rev-b", "*", "rev-c", "*"),
                Map.of("radio", "2.0.0..2.9.9"), null, null, true));
        signedPackage(app, keyId, secret, base("ctrl-bm", "board-mgmt", "1.0.0", "ctrl-a", 1,
                Map.of("rev-a", "*", "rev-b", "*", "rev-c", "*"),
                Map.of(), null, null, true));
        signedPackage(app, keyId, secret, base("ctrl-bm", "board-mgmt", "1.2.0", "ctrl-a", 1,
                Map.of("rev-a", "*", "rev-c", "*"),
                Map.of(), null, null, true));
        // devices
        postDevice(app, device("dev-alpha-01", "ctrl-a", "rev-a", "legacy", false,
                Map.of("bootloader", "1.0.0", "main", "2.0.0", "radio", "1.2.0",
                        "sensor", "1.0.0", "board-mgmt", "1.0.0")));
        postDevice(app, device("dev-bravo-02", "ctrl-a", "rev-b", "early", false,
                Map.of("bootloader", "1.0.0", "main", "2.0.0", "radio", "1.2.0",
                        "sensor", "1.0.0", "board-mgmt", "1.0.0")));
        postDevice(app, device("dev-charlie-03", "ctrl-a", "rev-c", "canary", false,
                Map.of("bootloader", "2.0.0", "main", "3.0.0", "radio", "1.4.0",
                        "sensor", "1.0.0", "board-mgmt", "1.2.0")));
        postDevice(app, device("dev-frozen-04", "ctrl-a", "rev-c", "frozen-batch", true,
                Map.of("bootloader", "2.0.0", "main", "3.0.0", "radio", "1.4.0",
                        "sensor", "1.0.0", "board-mgmt", "1.2.0")));
    }

    private static void seedEdgeFamily(AppService app, String keyId, String secret) {
        Map<String, Object> family = new LinkedHashMap<>();
        family.put("family", "edge-b");
        family.put("name", "Edge Sensor Node B");
        family.put("revisions", List.of(
                Map.of("id", "r1", "label", "R1 2023 silicon", "bootloader_min", "1.0.0"),
                Map.of("id", "r2", "label", "R2 2024 silicon", "bootloader_min", "3.0.0")));
        family.put("components", List.of(
                component("bootloader", "Bootloader", null, null),
                component("main", "Main controller", null, null),
                component("radio", "Radio module", null, null)));
        postFamily(app, family);

        // r1-compatible main is format 1 only; r2-compatible main is format 2.
        signedPackage(app, keyId, secret, base("edge-bl", "bootloader", "1.0.0", "edge-b", 1,
                Map.of("r1", "*"), Map.of(), null, null, true));
        signedPackage(app, keyId, secret, base("edge-bl", "bootloader", "3.0.0", "edge-b", 1,
                Map.of("r2", "*"), Map.of(), null, null, true));
        signedPackage(app, keyId, secret, base("edge-main", "main", "1.0.0", "edge-b", 1,
                Map.of("r1", "*"), Map.of(),
                null, null, true));
        signedPackage(app, keyId, secret, base("edge-main", "main", "2.0.0", "edge-b", 2,
                Map.of("r2", "*"), Map.of("bootloader", "3.0.0..3.9.9",
                        "radio", "2.0.0..2.9.9"),
                null, null, false));
        // radio 2.x deliberately only supports r1 - so an r2 target main 2.0.0
        // has no common-version solution across its companion window.
        signedPackage(app, keyId, secret, base("edge-radio", "radio", "1.0.0", "edge-b", 1,
                Map.of("r1", "*"), Map.of(), null, null, true));
        signedPackage(app, keyId, secret, base("edge-radio", "radio", "2.0.0", "edge-b", 2,
                Map.of("r1", "*"), Map.of(), null, null, true));

        // r1 device currently on main 2.0.0? impossible by construction; instead
        // demonstrate the format downgrade: r2 device with main 2.0 asked to
        // return to main 1.0.0 (r1-only and format 2 -> 1).
        postDevice(app, device("edge-r1-01", "edge-b", "r1", "edge-canary", false,
                Map.of("bootloader", "1.0.0", "main", "1.0.0", "radio", "1.0.0")));
        postDevice(app, device("edge-r2-02", "edge-b", "r2", "edge-canary", false,
                Map.of("bootloader", "3.0.0", "main", "2.0.0", "radio", "2.0.0")));
    }

    // -------------------------------------------------------------- builders

    private static Map<String, Object> familyControl() {
        Map<String, Object> family = new LinkedHashMap<>();
        family.put("family", "ctrl-a");
        family.put("name", "Main Controller A");
        family.put("revisions", List.of(
                Map.of("id", "rev-a", "label", "Rev A 2022 silicon",
                        "bootloader_min", "1.0.0"),
                Map.of("id", "rev-b", "label", "Rev B 2023 silicon",
                        "bootloader_min", "2.0.0"),
                Map.of("id", "rev-c", "label", "Rev C 2025 silicon",
                        "bootloader_min", "2.1.0")));
        family.put("components", List.of(
                component("bootloader", "Bootloader",
                        "probe:bootloader:slot", "health:bootloader:handshake"),
                component("radio", "Radio module",
                        "probe:radio:link", "health:radio:rssi"),
                component("main", "Main controller",
                        "probe:main:version", "health:main:watchdog"),
                component("sensor", "Sensor co-processor",
                        "probe:sensor:i2c", "health:sensor:stream"),
                component("board-mgmt", "Board management",
                        "probe:bm:power", "health:bm:temps")));
        return family;
    }

    private static Map<String, Object> component(String id, String label,
                                                 String probe, String health) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("label", label);
        c.put("probe", probe);
        c.put("health_check", health);
        return c;
    }

    private static Map<String, Object> base(String packageId, String component, String version,
                                            String family, int formatVersion,
                                            Map<String, String> hw,
                                            Map<String, String> requires,
                                            String bootloaderMin, String maxFrom,
                                            boolean safeRollback) {
        Map<String, Object> m = new LinkedHashMap<>();
        String hashInput = family + "|" + component + "|" + packageId + "|" + version;
        m.put("package_id", packageId);
        m.put("component", component);
        m.put("version", version);
        m.put("family", family);
        m.put("format_version", formatVersion);
        m.put("sha256", fw.crypto.Hashing.sha256Hex(hashInput));
        m.put("size", 1024L * (version.hashCode() & 0x3ff) + 4096L);
        m.put("signer", "release-robot@example.com");
        m.put("signed_at", "2026-09-01T00:00:00Z");
        m.put("safe_rollback", safeRollback);
        m.put("hw_compatibility", new LinkedHashMap<>(hw));
        Map<String, Object> req = new LinkedHashMap<>();
        req.putAll(requires);
        m.put("requires", req);
        m.put("bootloader_min", bootloaderMin);
        m.put("max_from_version", maxFrom);
        m.put("probe", null);
        m.put("health_check", null);
        return m;
    }

    private static Map<String, Object> device(String id, String family, String revision,
                                              String cohort, boolean frozen,
                                              Map<String, String> components) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("device_id", id);
        d.put("family", family);
        d.put("revision", revision);
        d.put("cohort", cohort);
        d.put("frozen", frozen);
        // resolve package refs into storage keys (id__version__hash12) by reusing
        // the same hash formula the package builder uses
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : components.entrySet()) {
            String comp = e.getKey();
            String version = e.getValue();
            String pkgId = switch (comp) {
                case "bootloader" -> family.equals("ctrl-a") ? "ctrl-bl" : "edge-bl";
                case "main" -> family.equals("ctrl-a") ? "ctrl-main" : "edge-main";
                case "radio" -> family.equals("ctrl-a") ? "ctrl-radio" : "edge-radio";
                case "sensor" -> "ctrl-sensor";
                case "board-mgmt" -> "ctrl-bm";
                default -> comp;
            };
            String hash = fw.crypto.Hashing.sha256Hex(family + "|" + comp + "|" + pkgId + "|" + version);
            resolved.put(comp, pkgId + "__" + version + "__" + hash.substring(0, 12));
        }
        d.put("components", resolved);
        return d;
    }
}
