package fwplan.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一批硬件：族 + 硬件修订 + 现状 + 目标。
 * frozen=true 时后续重算必须原样保留；approved 方案冻结其全部输入快照。
 */
public record Cohort(
        String cohortId,
        String label,
        String familyId,
        String hwRevision,
        DeviceState current,
        DeviceState target,
        boolean frozen) {

    public Cohort withFrozen(boolean value) {
        return new Cohort(cohortId, label, familyId, hwRevision, current, target, value);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("cohortId", cohortId);
        map.put("label", label);
        map.put("familyId", familyId);
        map.put("hwRevision", hwRevision);
        map.put("current", current.toMap());
        map.put("target", target.toMap());
        map.put("frozen", frozen);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Cohort fromMap(Map<String, Object> map) {
        return new Cohort(
                String.valueOf(map.get("cohortId")),
                String.valueOf(map.get("label")),
                String.valueOf(map.get("familyId")),
                String.valueOf(map.get("hwRevision")),
                DeviceState.fromMap((Map<String, Object>) map.get("current")),
                DeviceState.fromMap((Map<String, Object>) map.get("target")),
                Boolean.TRUE.equals(map.get("frozen")));
    }
}
