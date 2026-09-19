package fwplan.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从现状到目标组合的规划结果：要么 feasible 且给出有序步骤，
 * 要么 infeasible 且给出最小冲突集合列表。
 */
public record Plan(
        String familyId,
        String hwRevision,
        DeviceState current,
        DeviceState target,
        boolean feasible,
        List<Step> steps,
        List<Conflict> conflicts,
        Map<String, Object> rationale) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("familyId", familyId);
        map.put("hwRevision", hwRevision);
        map.put("current", current.toMap());
        map.put("target", target.toMap());
        map.put("feasible", feasible);
        List<Object> stepList = new ArrayList<>();
        for (Step step : steps) stepList.add(step.toMap());
        map.put("steps", stepList);
        List<Object> conflictList = new ArrayList<>();
        for (Conflict conflict : conflicts) conflictList.add(conflict.toMap());
        map.put("conflicts", conflictList);
        map.put("rationale", new LinkedHashMap<>(rationale));
        return map;
    }

    @SuppressWarnings("unchecked")
    public static Plan fromMap(Map<String, Object> map) {
        java.util.List<Step> steps = new java.util.ArrayList<>();
        for (Map<String, Object> sm : fwplan.util.Json.objList(map, "steps")) {
            java.util.List<String> comps = new java.util.ArrayList<>();
            for (Object o : fwplan.util.Json.list(sm, "components")) comps.add(String.valueOf(o));
            steps.add(new Step(
                    ((Number) sm.get("index")).intValue(),
                    comps,
                    String.valueOf(sm.getOrDefault("firmwareId", "")),
                    (Map<String, String>) (Map<?, ?>) sm.getOrDefault("fromVersions", Map.of()),
                    (Map<String, String>) (Map<?, ?>) sm.getOrDefault("toVersions", Map.of()),
                    Boolean.TRUE.equals(sm.get("bundle")),
                    String.valueOf(sm.getOrDefault("note", ""))));
        }
        java.util.List<Conflict> conflicts = new java.util.ArrayList<>();
        for (Map<String, Object> cm : fwplan.util.Json.objList(map, "conflicts")) {
            java.util.List<String> involved = new java.util.ArrayList<>();
            for (Object o : fwplan.util.Json.list(cm, "involved")) involved.add(String.valueOf(o));
            conflicts.add(new Conflict(String.valueOf(cm.get("code")),
                    String.valueOf(cm.get("message")), involved));
        }
        return new Plan(
                String.valueOf(map.get("familyId")),
                String.valueOf(map.get("hwRevision")),
                DeviceState.fromMap((Map<String, Object>) map.getOrDefault("current", Map.of())),
                DeviceState.fromMap((Map<String, Object>) map.getOrDefault("target", Map.of())),
                Boolean.TRUE.equals(map.get("feasible")),
                steps, conflicts,
                (Map<String, Object>) map.getOrDefault("rationale", new LinkedHashMap<>()));
    }
}
