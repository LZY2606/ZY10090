package fwplan.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fwplan.model.Conflict;
import fwplan.model.DeviceState;
import fwplan.model.FamilyManifest;
import fwplan.model.FirmwareArtifact;
import fwplan.model.Version;

/** 共享判定逻辑：状态合法性、转移合法性。规划器与模拟器共用同一套规则。 */
public final class Rules {

    private final Catalog catalog;
    final FamilyManifest manifest;
    final String hw;
    final Map<String, List<FirmwareArtifact>> options = new LinkedHashMap<>();

    public Rules(Catalog catalog, FamilyManifest manifest, String hw) {
        this.catalog = catalog;
        this.manifest = manifest;
        this.hw = hw;
        for (String component : manifest.components()) {
            List<FirmwareArtifact> usable = catalog.versions(manifest.familyId(), component).stream()
                    .filter(a -> a.supportsHw(hw))
                    .sorted((x, y) -> Version.of(x.version()).compareTo(Version.of(y.version())))
                    .toList();
            options.put(component, usable);
        }
    }

    public FirmwareArtifact pick(String component, String version) {
        return version == null ? null
                : catalog.find(manifest.familyId(), component, version).orElse(null);
    }

    /** 部分写入状态的恢复判定：返回该状态下可恢复到的最近稳定状态（可能是自身），不可恢复返回 null。 */
    public DeviceState recoverFrom(DeviceState partial) {
        DeviceState recovered = partial;
        boolean changed;
        do {
            changed = false;
            for (String component : manifest.components()) {
                String version = recovered.get(component);
                if (version == null || pick(component, version) == null
                        || (pick(component, version) != null && !pick(component, version).supportsHw(hw))) {
                    return null;
                }
            }
            String bootVersion = recovered.get(FirmwareArtifact.BOOTLOADER);
            if (bootVersion == null) return null;
            Version boot = Version.of(bootVersion);
            for (String component : manifest.components()) {
                if (FirmwareArtifact.BOOTLOADER.equals(component)) continue;
                FirmwareArtifact artifact = pick(component, recovered.get(component));
                if (artifact != null && artifact.minBootloader() != null
                        && boot.compareTo(Version.of(artifact.minBootloader())) < 0) {
                    return null;
                }
            }
        } while (changed);
        return recovered;
    }

    public List<Conflict> stateConflicts(DeviceState state, String label) {
        List<Conflict> conflicts = new ArrayList<>();
        for (String component : manifest.components()) {
            String version = state.get(component);
            if (version == null) {
                conflicts.add(new Conflict("MISSING_COMPONENT",
                        label + "缺少组件: " + component, List.of(component)));
                continue;
            }
            FirmwareArtifact artifact = pick(component, version);
            if (artifact == null) {
                conflicts.add(new Conflict("NO_SUCH_VERSION",
                        label + "组件 " + component + " 没有已签名版本 " + version,
                        List.of(component, version)));
                continue;
            }
            if (!artifact.supportsHw(hw)) {
                conflicts.add(new Conflict("HARDWARE_UNSUPPORTED",
                        label + artifact.id() + " 不支持硬件修订 " + hw,
                        List.of(component, version, hw)));
            }
        }
        if (!conflicts.isEmpty()) return conflicts;

        Version boot = Version.of(state.get(FirmwareArtifact.BOOTLOADER));
        for (String component : manifest.components()) {
            if (FirmwareArtifact.BOOTLOADER.equals(component)) continue;
            FirmwareArtifact artifact = pick(component, state.get(component));
            if (artifact.minBootloader() != null
                    && boot.compareTo(Version.of(artifact.minBootloader())) < 0) {
                conflicts.add(new Conflict("BOOTLOADER_TOO_OLD",
                        label + component + " " + artifact.version() + " 需要 bootloader >= "
                                + artifact.minBootloader(),
                        List.of(FirmwareArtifact.BOOTLOADER, component, artifact.minBootloader())));
            }
        }
        for (String component : manifest.components()) {
            FirmwareArtifact artifact = pick(component, state.get(component));
            for (Map.Entry<String, String> entry : artifact.companions().entrySet()) {
                String peer = entry.getKey();
                if (!manifest.components().contains(peer)) continue;
                Version.Range range = Version.Range.parse(entry.getValue());
                String peerVersion = state.get(peer);
                if (peerVersion != null && !range.contains(Version.of(peerVersion))) {
                    boolean anyCommon = options.getOrDefault(peer, List.of()).stream()
                            .anyMatch(p -> range.contains(Version.of(p.version())));
                    conflicts.add(new Conflict(anyCommon ? "COMPANION_NOT_MET" : "NO_COMMON_VERSION",
                            label + component + " " + artifact.version() + " 要求 " + peer
                                    + " 落在 " + entry.getValue() + "，但 " + peer + " 为 " + peerVersion,
                            List.of(component, peer, entry.getValue())));
                }
            }
        }
        return conflicts;
    }

    /** 组件级转移阻断原因；无阻断返回 null。 */
    public String gateBlocked(String component, String fromVersion, String toVersion) {
        if (fromVersion == null || toVersion == null) return null;
        Version from = Version.of(fromVersion);
        Version to = Version.of(toVersion);
        if (FirmwareArtifact.BOOTLOADER.equals(component) && to.compareTo(from) < 0) {
            return "bootloader 不允许降级: " + fromVersion + " -> " + toVersion;
        }
        FirmwareArtifact a = pick(component, fromVersion);
        FirmwareArtifact b = pick(component, toVersion);
        if (a != null && b != null && b.storageEpoch() < a.storageEpoch()) {
            return "降级会破坏存储格式: " + component + " " + fromVersion + "(epoch "
                    + a.storageEpoch() + ") -> " + toVersion + "(epoch " + b.storageEpoch() + ")";
        }
        for (FamilyManifest.Gate gate : manifest.gates()) {
            if (!gate.component().equals(component)) continue;
            Version floor = Version.of(gate.fromVersion());
            if (from.compareTo(floor) >= 0 && to.compareTo(floor) < 0) {
                return "跨越不可跨越版本: " + component + " 一旦达到 " + gate.fromVersion()
                        + " 就不能回到 " + toVersion;
            }
        }
        return null;
    }
}
