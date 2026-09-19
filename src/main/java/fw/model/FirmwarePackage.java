package fw.model;

import fw.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Signed firmware metadata record.
 *
 * <p>Identity of a binary is the tuple (signer, sha256); name/version are
 * descriptive only. Two packages with the same {@code package_id} + version but
 * different hashes are different binaries and may not silently replace each
 * other (see the planner's {@code name_tamper} guard).</p>
 */
public record FirmwarePackage(
        String packageId,
        String component,
        String version,
        String family,
        int formatVersion,
        String sha256,
        long size,
        String signer,
        String signature,
        String signedAt,
        boolean safeRollback,
        Map<String, VersionRange> requires,
        Map<String, String> hwCompatibility,
        String bootloaderMin,
        String maxFromVersion,
        String probe,
        String healthCheck
) {

    public FirmwarePackage {
        requires = Map.copyOf(requires);
        hwCompatibility = Map.copyOf(hwCompatibility);
    }

    /** Builds from already-verified metadata JSON (signature block retained). */
    @SuppressWarnings("unchecked")
    public static FirmwarePackage fromVerified(Map<String, Object> m) {
        String pid = Json.reqString(m, "package_id");
        String component = Json.reqString(m, "component");
        String version = Json.reqString(m, "version");
        String family = Json.reqString(m, "family");
        String hash = Json.reqString(m, "sha256");
        String signer = Json.str(m, "signer", "");
        String signature = Json.str(m, "signature", "");
        String signedAt = Json.str(m, "signed_at", "");
        long size = m.get("size") instanceof Number n ? n.longValue() : 0L;
        int formatVersion = Json.optInt(m, "format_version", 1);
        boolean safeRollback = Json.optBool(m, "safe_rollback", false);
        String bootMin = m.get("bootloader_min") instanceof String s && !s.isEmpty() ? s : null;
        String maxFrom = m.get("max_from_version") instanceof String s && !s.isEmpty() ? s : null;
        String probe = m.get("probe") instanceof String s && !s.isEmpty() ? s : null;
        String health = m.get("health_check") instanceof String s && !s.isEmpty() ? s : null;

        Map<String, VersionRange> req = new LinkedHashMap<>();
        Object requiresObj = m.get("requires");
        if (requiresObj instanceof Map<?, ?> rm) {
            for (Map.Entry<?, ?> e : rm.entrySet()) {
                req.put(String.valueOf(e.getKey()),
                        VersionRange.parse(String.valueOf(e.getValue())));
            }
        }
        Map<String, String> hw = new LinkedHashMap<>();
        Object hwObj = m.get("hw_compatibility");
        if (hwObj instanceof Map<?, ?> hm) {
            for (Map.Entry<?, ?> e : hm.entrySet()) {
                hw.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        return new FirmwarePackage(pid, component, version, family, formatVersion, hash, size,
                signer, signature, signedAt, safeRollback, req, hw, bootMin, maxFrom,
                probe, health);
    }

    /**
     * Canonical payload that signatures cover: the metadata object with the
     * signature-related fields removed and keys deterministically sorted.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> unsignedPayload(Map<String, Object> raw) {
        Map<String, Object> copy = new LinkedHashMap<>(raw);
        copy.remove("signature");
        return copy;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("package_id", packageId);
        m.put("component", component);
        m.put("version", version);
        m.put("family", family);
        m.put("format_version", formatVersion);
        m.put("sha256", sha256);
        m.put("size", size);
        m.put("signer", signer);
        m.put("signature", signature);
        m.put("signed_at", signedAt);
        m.put("safe_rollback", safeRollback);
        Map<String, Object> req = new LinkedHashMap<>();
        requires.forEach((k, v) -> req.put(k, v.toString()));
        m.put("requires", req);
        Map<String, Object> hw = new LinkedHashMap<>(hwCompatibility);
        m.put("hw_compatibility", hw);
        m.put("bootloader_min", bootloaderMin);
        m.put("max_from_version", maxFromVersion);
        m.put("probe", probe);
        m.put("health_check", healthCheck);
        return m;
    }

    public boolean hwOk(String revision) {
        if (hwCompatibility.isEmpty()) {
            return true;
        }
        String allow = hwCompatibility.get(revision);
        if (allow == null) {
            return false;
        }
        return "*".equals(allow) || allow.equals(revision);
    }

    /** Stable storage key: same name+version but different hash is a distinct record. */
    public String storageKey() {
        return packageId + "__" + version + "__" + sha256.substring(0, Math.min(12, sha256.length()));
    }
}
