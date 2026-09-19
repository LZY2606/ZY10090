package fwplan.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 签名固件元数据。固件二进制身份只由 sha256 + signer + 签名决定；
 * 同名（family/component/version 相同）但哈希不同的包是不同工件，禁止互相替换。
 */
public record FirmwareArtifact(
        String familyId,
        String component,
        String version,
        String sha256,
        String signer,
        String signature,
        List<String> hwRevisions,
        String minBootloader,
        Map<String, String> companions,
        int storageEpoch) {

    public static final String BOOTLOADER = "bootloader";

    public FirmwareArtifact {
        hwRevisions = List.copyOf(hwRevisions);
        companions = Map.copyOf(companions);
    }

    public boolean isBootloader() { return BOOTLOADER.equals(component); }

    public boolean supportsHw(String hw) { return hwRevisions.isEmpty() || hwRevisions.contains(hw); }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("familyId", familyId);
        map.put("component", component);
        map.put("version", version);
        map.put("sha256", sha256);
        map.put("signer", signer);
        map.put("signature", signature);
        map.put("hwRevisions", new ArrayList<>(hwRevisions));
        map.put("minBootloader", minBootloader == null ? "" : minBootloader);
        map.put("companions", new LinkedHashMap<>(companions));
        map.put("storageEpoch", storageEpoch);
        return map;
    }

    /** 被签名/校验的规范字节：不含 signature 字段，键名固定、顺序固定。 */
    public byte[] canonicalBytes() {
        StringBuilder sb = new StringBuilder();
        sb.append("familyId=").append(familyId).append('\n');
        sb.append("component=").append(component).append('\n');
        sb.append("version=").append(version).append('\n');
        sb.append("sha256=").append(sha256).append('\n');
        sb.append("signer=").append(signer).append('\n');
        sb.append("hwRevisions=").append(String.join(",", hwRevisions)).append('\n');
        sb.append("minBootloader=").append(minBootloader == null ? "" : minBootloader).append('\n');
        List<String> keys = new ArrayList<>(companions.keySet());
        CollectionsSort(keys);
        for (String key : keys) sb.append("companion:").append(key).append('=').append(companions.get(key)).append('\n');
        sb.append("storageEpoch=").append(storageEpoch).append('\n');
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void CollectionsSort(List<String> keys) {
        keys.sort(null);
    }

    /** 身份键：同 family+component+version 视为同名包；同名不同哈希必须拒绝导入。 */
    public String nameKey() {
        return familyId + "|" + component + "|" + version;
    }

    public String id() {
        return component + "@" + version + ":" + (sha256.length() >= 12 ? sha256.substring(0, 12) : sha256);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FirmwareArtifact f
                && Objects.equals(sha256, f.sha256)
                && Objects.equals(signer, f.signer)
                && Objects.equals(component, f.component)
                && Objects.equals(version, f.version)
                && Objects.equals(familyId, f.familyId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(familyId, component, version, sha256, signer);
    }
}
