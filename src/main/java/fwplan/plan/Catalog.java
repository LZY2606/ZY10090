package fwplan.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import fwplan.model.FamilyManifest;
import fwplan.model.FirmwareArtifact;

/**
 * 已导入数据的内存索引：设备族清单 + 按身份存储的固件工件。
 * 同名（family/component/version）但哈希不同的包禁止替换。
 */
public final class Catalog {

    private final Map<String, FamilyManifest> families = new LinkedHashMap<>();
    private final Map<String, FirmwareArtifact> artifactsById = new LinkedHashMap<>();
    private final Map<String, List<FirmwareArtifact>> byName = new LinkedHashMap<>();

    public synchronized void putManifest(FamilyManifest manifest) {
        families.put(manifest.familyId(), manifest);
    }

    public synchronized Optional<FamilyManifest> manifest(String familyId) {
        return Optional.ofNullable(families.get(familyId));
    }

    public synchronized List<FamilyManifest> manifests() {
        return new ArrayList<>(families.values());
    }

    /** @return 同名但哈希不同时抛状态冲突；同一工件重复导入为幂等 no-op。 */
    public synchronized boolean putArtifact(FirmwareArtifact artifact) {
        String idKey = artifact.familyId() + "|" + artifact.sha256();
        if (artifactsById.containsKey(idKey)) return false;
        String nameKey = artifact.nameKey();
        List<FirmwareArtifact> sameName = byName.getOrDefault(nameKey, List.of());
        for (FirmwareArtifact existing : sameName) {
            if (!existing.sha256().equals(artifact.sha256())) {
                throw new ConflictStateException(
                        "同名包不能替换: " + nameKey + " 已存在哈希 "
                                + existing.sha256().substring(0, 12)
                                + "，新包哈希 " + artifact.sha256().substring(0, 12));
            }
        }
        artifactsById.put(idKey, artifact);
        List<FirmwareArtifact> list = new ArrayList<>(sameName);
        list.add(artifact);
        byName.put(nameKey, list);
        return true;
    }

    public synchronized Optional<FirmwareArtifact> find(String familyId, String component, String version) {
        List<FirmwareArtifact> list = byName.get(familyId + "|" + component + "|" + version);
        if (list == null || list.isEmpty()) return Optional.empty();
        return Optional.of(list.get(0));
    }

    public synchronized List<FirmwareArtifact> versions(String familyId, String component) {
        List<FirmwareArtifact> out = new ArrayList<>();
        String prefix = familyId + "|" + component + "|";
        for (Map.Entry<String, List<FirmwareArtifact>> e : byName.entrySet()) {
            if (e.getKey().startsWith(prefix) && !e.getValue().isEmpty()) {
                out.add(e.getValue().get(0));
            }
        }
        return out;
    }

    public synchronized List<FirmwareArtifact> artifacts() {
        return new ArrayList<>(artifactsById.values());
    }
}
