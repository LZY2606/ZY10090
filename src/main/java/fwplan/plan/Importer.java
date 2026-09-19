package fwplan.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fwplan.crypto.SignerRegistry;
import fwplan.crypto.Signing;
import fwplan.model.FamilyManifest;
import fwplan.model.FirmwareArtifact;
import fwplan.model.Version;
import fwplan.util.Json;
import fwplan.util.Json.JsonException;

/** 把原始 JSON 解析成领域对象，负责全部结构/语义/签名校验。 */
public final class Importer {

    private Importer() {}

    public static FamilyManifest parseManifest(String body) {
        Map<String, Object> map;
        try {
            map = Json.parseObject(body);
        } catch (JsonException e) {
            throw new ValidationException("manifest 不是合法 JSON: " + e.getMessage());
        }
        String familyId = requireField(map, "familyId");
        String displayName = Json.str(map, "displayName");
        if (displayName == null) displayName = familyId;
        List<String> components = stringList(map, "components");
        if (!components.contains(FirmwareArtifact.BOOTLOADER)) {
            throw new ValidationException("components 必须包含 bootloader");
        }
        if (components.stream().distinct().count() != components.size()) {
            throw new ValidationException("components 存在重复");
        }
        List<String> hw = stringList(map, "hwRevisions");
        if (hw.isEmpty()) throw new ValidationException("hwRevisions 至少需要一个硬件修订");
        if (hw.stream().distinct().count() != hw.size()) {
            throw new ValidationException("hwRevisions 存在重复");
        }
        List<FamilyManifest.Gate> gates = new ArrayList<>();
        for (Map<String, Object> gateMap : Json.objList(map, "gates")) {
            String component = Json.requireStr(gateMap, "component");
            String fromVersion = Json.requireStr(gateMap, "fromVersion");
            if (!components.contains(component)) {
                throw new ValidationException("gate 引用了未知组件: " + component);
            }
            Version.of(fromVersion);
            gates.add(new FamilyManifest.Gate(component, fromVersion));
        }
        return new FamilyManifest(familyId, displayName, components, hw, gates);
    }

    public static FirmwareArtifact parseArtifact(String body, SignerRegistry signers) {
        Map<String, Object> map;
        try {
            map = Json.parseObject(body);
        } catch (JsonException e) {
            throw new ValidationException("firmware metadata 不是合法 JSON: " + e.getMessage());
        }
        String familyId = requireField(map, "familyId");
        String component = requireField(map, "component");
        String version = requireField(map, "version");
        String sha256 = requireField(map, "sha256");
        String signer = requireField(map, "signer");
        String signature = requireField(map, "signature");
        Version.of(version);
        if (!sha256.matches("[0-9a-fA-F]{64}")) {
            throw new ValidationException("sha256 必须是 64 位十六进制");
        }
        sha256 = sha256.toLowerCase();
        List<String> hw = stringList(map, "hwRevisions");
        hw = new ArrayList<>(hw.stream().map(String::toLowerCase).toList());
        String minBootloader = Json.str(map, "minBootloader");
        if (minBootloader != null && !minBootloader.isBlank()) Version.of(minBootloader);
        else minBootloader = null;
        Map<String, String> companions = new LinkedHashMap<>();
        Map<String, Object> compRaw = Json.obj(map, "companions");
        for (Map.Entry<String, Object> e : compRaw.entrySet()) {
            String rangeExpr = String.valueOf(e.getValue());
            Version.Range.parse(rangeExpr);
            companions.put(e.getKey(), rangeExpr);
        }
        int storageEpoch = Json.intVal(map, "storageEpoch", 0);
        FirmwareArtifact artifact = new FirmwareArtifact(
                familyId, component, version, sha256, signer, signature,
                hw, minBootloader, companions, storageEpoch);
        if (!signers.isTrusted(signer)) {
            throw new ValidationException("签名者不受信任: " + signer);
        }
        boolean ok = Signing.verifySha256(signers.key(signer), sha256, signature);
        if (!ok) {
            throw new ValidationException("固件签名校验失败: " + artifact.id());
        }
        return artifact;
    }

    private static String requireField(Map<String, Object> map, String key) {
        String value = Json.str(map, key);
        if (value == null || value.isBlank()) {
            throw new ValidationException("缺少必填字段: " + key);
        }
        return value.trim();
    }

    private static List<String> stringList(Map<String, Object> map, String key) {
        List<String> out = new ArrayList<>();
        for (Object item : Json.list(map, key)) {
            if (item == null) throw new ValidationException(key + " 含空元素");
            out.add(String.valueOf(item).trim());
        }
        return out;
    }
}
