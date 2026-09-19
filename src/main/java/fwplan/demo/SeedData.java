package fwplan.demo;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fwplan.crypto.Hashes;
import fwplan.crypto.Signing;
import fwplan.web.AppService;

/**
 * 演示种子数据：首次启动（且库为空）时生成一个演示 RSA 签名者，
 * 用其私钥对一组固件元数据进行真实签名后导入，并创建四个 cohort：
 * 常规升级、依赖环 bundle、存储格式降级冲突、冻结批次。
 */
public final class SeedData {

    public static final String SIGNER = "embedded-demo";

    private static KeyPair demoPair;

    private SeedData() {}

    /** 演示：模拟“批准之后”才出现的新签名固件（使用演示私钥真实签名）。 */
    public static void addLateSignedVersion(AppService service) {
        try {
            KeyPair pair = demoPair != null ? demoPair : generatePair();
            Map<String, Object> meta = new LinkedHashMap<>();
            String content = "edge-node|mcu|9.9.9|late-binary-blob";
            String sha = Hashes.sha256Hex(content.getBytes(StandardCharsets.UTF_8));
            meta.put("familyId", "edge-node");
            meta.put("component", "mcu");
            meta.put("version", "9.9.9");
            meta.put("sha256", sha);
            meta.put("signer", SIGNER);
            meta.put("signature", Signing.signSha256(pair.getPrivate(), sha));
            meta.put("hwRevisions", List.of("r2"));
            meta.put("minBootloader", "1.2.0");
            meta.put("companions", Map.of("radio", "[2.0.0,3.0.0)"));
            meta.put("storageEpoch", 0);
            service.importFirmware(fwplan.util.Json.write(meta));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generatePair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    public static void seedIfEmpty(AppService service) throws Exception {
        if (!service.isEmpty()) return;

        KeyPair pair;
        try { pair = generatePair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
        demoPair = pair;
        service.registerSigner(SIGNER, pair.getPublic());

        // ---- edge-node 设备族 ----
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("familyId", "edge-node");
        manifest.put("displayName", "边缘节点 (MCU/Radio/Sensor)");
        manifest.put("components", List.of("bootloader", "mcu", "radio", "sensor"));
        manifest.put("hwRevisions", List.of("r1", "r2", "r3"));
        manifest.put("gates", List.of(
                Map.of("component", "bootloader", "fromVersion", "1.0.0"),
                Map.of("component", "sensor", "fromVersion", "3.0.0")));
        service.importManifest(toJson(manifest));

        // ---- bootloader ----
        fw(service, pair, "edge-node", "bootloader", "1.0.0",
                List.of("r1", "r2", "r3"), null, Map.of(), 0);
        fw(service, pair, "edge-node", "bootloader", "1.2.0",
                List.of("r1", "r2", "r3"), null, Map.of(), 0);
        fw(service, pair, "edge-node", "bootloader", "2.0.0",
                List.of("r2", "r3"), null, Map.of(), 0);

        // ---- mcu：2.1.0 与 radio 2.0.0 构成相互要求的依赖环 ----
        fw(service, pair, "edge-node", "mcu", "1.0.0",
                List.of("r1", "r2", "r3"), "1.0.0", Map.of(), 0);
        fw(service, pair, "edge-node", "mcu", "1.4.0",
                List.of("r1", "r2", "r3"), "1.0.0",
                Map.of("radio", "[1.0.0,2.0.0)"), 0);
        fw(service, pair, "edge-node", "mcu", "2.1.0",
                List.of("r2", "r3"), "1.2.0",
                Map.of("radio", "[2.0.0,3.0.0)"), 0);

        // ---- radio ----
        fw(service, pair, "edge-node", "radio", "1.0.0",
                List.of("r1", "r2", "r3"), "1.0.0", Map.of(), 0);
        fw(service, pair, "edge-node", "radio", "1.5.0",
                List.of("r1", "r2", "r3"), "1.0.0",
                Map.of("mcu", "[1.4.0,2.1.0)"), 0);
        fw(service, pair, "edge-node", "radio", "2.0.0",
                List.of("r2", "r3"), "1.2.0",
                Map.of("mcu", "[2.1.0,3.0.0)"), 0);

        // ---- sensor：3.0.0 提升存储 epoch（不可降级）；r3 上没有任何 2.x ----
        fw(service, pair, "edge-node", "sensor", "1.0.0",
                List.of("r1", "r2"), "1.0.0", Map.of(), 0);
        fw(service, pair, "edge-node", "sensor", "2.0.0",
                List.of("r1", "r2"), "1.0.0",
                Map.of("radio", "[1.0.0,3.0.0)"), 0);
        fw(service, pair, "edge-node", "sensor", "3.0.0",
                List.of("r2", "r3"), "1.2.0",
                Map.of("radio", "[2.0.0,3.0.0)"), 1);

        // ---- cohorts ----
        cohort(service, "cohort-stable", "r2 常规升级（含 bootloader，回滚标红）",
                "r2",
                Map.of("bootloader", "1.0.0", "mcu", "1.0.0", "radio", "1.0.0", "sensor", "1.0.0"),
                Map.of("bootloader", "1.2.0", "mcu", "1.4.0", "radio", "1.5.0", "sensor", "2.0.0"),
                false);

        cohort(service, "cohort-cycle", "r2 依赖环升级（必须 mcu+radio bundle）",
                "r2",
                Map.of("bootloader", "1.2.0", "mcu", "1.4.0", "radio", "1.5.0", "sensor", "2.0.0"),
                Map.of("bootloader", "1.2.0", "mcu", "2.1.0", "radio", "2.0.0", "sensor", "3.0.0"),
                false);

        cohort(service, "cohort-downgrade", "r2 存储格式降级（不可行：最小冲突集）",
                "r2",
                Map.of("bootloader", "1.2.0", "mcu", "2.1.0", "radio", "2.0.0", "sensor", "3.0.0"),
                Map.of("bootloader", "1.2.0", "mcu", "1.4.0", "radio", "1.5.0", "sensor", "2.0.0"),
                false);

        cohort(service, "cohort-frozen", "r3 冻结批次（重算时原样保留）",
                "r3",
                Map.of("bootloader", "2.0.0", "mcu", "2.1.0", "radio", "2.0.0", "sensor", "3.0.0"),
                Map.of("bootloader", "2.0.0", "mcu", "2.1.0", "radio", "2.0.0", "sensor", "3.0.0"),
                true);
    }

    private static void fw(AppService service, KeyPair pair, String family, String component,
                           String version, List<String> hw, String minBoot,
                           Map<String, String> companions, int epoch) {
        String content = family + "|" + component + "|" + version + "|binary-blob";
        String sha = Hashes.sha256Hex(content.getBytes(StandardCharsets.UTF_8));
        String signature = Signing.signSha256(pair.getPrivate(), sha);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("familyId", family);
        meta.put("component", component);
        meta.put("version", version);
        meta.put("sha256", sha);
        meta.put("signer", SIGNER);
        meta.put("signature", signature);
        meta.put("hwRevisions", hw);
        meta.put("minBootloader", minBoot == null ? "" : minBoot);
        meta.put("companions", companions);
        meta.put("storageEpoch", epoch);
        service.importFirmware(toJson(meta));
    }

    private static void cohort(AppService service, String id, String label, String hw,
                               Map<String, String> current, Map<String, String> target, boolean frozen) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cohortId", id);
        body.put("label", label);
        body.put("familyId", "edge-node");
        body.put("hwRevision", hw);
        body.put("current", current);
        body.put("target", target);
        body.put("frozen", frozen);
        service.upsertCohort(body);
    }

    private static String toJson(Map<String, Object> map) {
        return fwplan.util.Json.write(map);
    }

    public static String encodePrivate(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    }
}
