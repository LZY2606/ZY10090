package fwplan.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备推进回执。(releaseId, deviceId, requestId) 幂等：
 * 同一 requestId 重放永远返回首次结果，绝不重复推进阶段。
 */
public record Receipt(
        String requestId,
        String releaseId,
        String cohortId,
        String deviceId,
        String action,
        String fromPhase,
        String toPhase,
        String status,
        String message,
        String recordedAt,
        boolean replay) {

    public Receipt asReplay() {
        return new Receipt(requestId, releaseId, cohortId, deviceId, action,
                fromPhase, toPhase, status, message, recordedAt, true);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requestId", requestId);
        map.put("releaseId", releaseId);
        map.put("cohortId", cohortId);
        map.put("deviceId", deviceId);
        map.put("action", action);
        map.put("fromPhase", fromPhase == null ? "" : fromPhase);
        map.put("toPhase", toPhase == null ? "" : toPhase);
        map.put("status", status);
        map.put("message", message);
        map.put("recordedAt", recordedAt);
        map.put("replay", replay);
        return map;
    }

    public static Receipt fromMap(Map<String, Object> map) {
        String from = map.get("fromPhase") == null ? null : String.valueOf(map.get("fromPhase"));
        String to = map.get("toPhase") == null ? null : String.valueOf(map.get("toPhase"));
        return new Receipt(
                String.valueOf(map.get("requestId")),
                String.valueOf(map.get("releaseId")),
                String.valueOf(map.get("cohortId")),
                String.valueOf(map.get("deviceId")),
                String.valueOf(map.getOrDefault("action", "advance")),
                from.isEmpty() ? null : from,
                to == null || to.isEmpty() ? null : to,
                String.valueOf(map.get("status")),
                String.valueOf(map.getOrDefault("message", "")),
                String.valueOf(map.getOrDefault("recordedAt", "")),
                Boolean.TRUE.equals(map.get("replay")));
    }
}
