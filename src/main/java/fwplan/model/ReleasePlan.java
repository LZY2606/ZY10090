package fwplan.model;

import fwplan.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 发布方案：跨 cohort 分阶段推进；approved 后绑定清单/元数据快照，新上传不改变它。 */
public record ReleasePlan(
        String releaseId,
        String displayName,
        List<String> cohortIds,
        List<Phase> phases,
        String status,
        String createdAt,
        String approvedAt,
        Map<String, Object> snapshot) {

    /** 阶段：更新次序 + 健康判据 + 回滚目标（在导出的 plan/simulation 中携带）。 */
    public record Phase(int order, String cohortId, String title,
                        List<String> updateOrder, String healthCriteria,
                        String rollbackTarget, boolean safeRollback) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("order", order);
            map.put("cohortId", cohortId);
            map.put("title", title);
            map.put("updateOrder", new ArrayList<>(updateOrder));
            map.put("healthCriteria", healthCriteria);
            map.put("rollbackTarget", rollbackTarget);
            map.put("safeRollback", safeRollback);
            return map;
        }

        @SuppressWarnings("unchecked")
        public static Phase fromMap(Map<String, Object> map) {
            List<String> order = new ArrayList<>();
            for (Object o : (List<Object>) map.getOrDefault("updateOrder", List.of())) order.add(String.valueOf(o));
            return new Phase(
                    ((Number) map.get("order")).intValue(),
                    String.valueOf(map.get("cohortId")),
                    String.valueOf(map.get("title")),
                    order,
                    String.valueOf(map.getOrDefault("healthCriteria", "")),
                    map.get("rollbackTarget") == null ? null : String.valueOf(map.get("rollbackTarget")),
                    Boolean.TRUE.equals(map.get("safeRollback")));
        }
    }

    public ReleasePlan withStatus(String value, String approvedAt, Map<String, Object> newSnapshot) {
        return new ReleasePlan(releaseId, displayName, cohortIds, phases, value,
                createdAt, approvedAt, newSnapshot != null ? newSnapshot : snapshot);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("releaseId", releaseId);
        map.put("displayName", displayName);
        map.put("cohortIds", new ArrayList<>(cohortIds));
        List<Object> phaseList = new ArrayList<>();
        for (Phase phase : phases) phaseList.add(phase.toMap());
        map.put("phases", phaseList);
        map.put("status", status);
        map.put("createdAt", createdAt);
        map.put("approvedAt", approvedAt == null ? "" : approvedAt);
        map.put("snapshot", snapshot);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static ReleasePlan fromMap(Map<String, Object> map) {
        List<String> cohortIds = new ArrayList<>();
        for (Object o : (List<Object>) map.getOrDefault("cohortIds", List.of())) cohortIds.add(String.valueOf(o));
        List<Phase> phases = new ArrayList<>();
        for (Map<String, Object> p : Json.objList(map, "phases")) phases.add(Phase.fromMap(p));
        String approvedAt = Json.str(map, "approvedAt");
        return new ReleasePlan(
                String.valueOf(map.get("releaseId")),
                String.valueOf(map.get("displayName")),
                cohortIds, phases,
                String.valueOf(map.getOrDefault("status", "draft")),
                String.valueOf(map.getOrDefault("createdAt", "")),
                approvedAt == null || approvedAt.isEmpty() ? null : approvedAt,
                (Map<String, Object>) map.getOrDefault("snapshot", new LinkedHashMap<>()));
    }
}
