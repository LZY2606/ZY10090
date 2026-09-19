package fwplan.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小冲突集合：每个 conflict 给出一个独立的、无法满足的事实/约束集合，
 * 前端用于红色展示；规划器不能因为“最新版优先”而悄悄绕开冲突。
 */
public record Conflict(String code, String message, List<String> involved) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", code);
        map.put("message", message);
        map.put("involved", new ArrayList<>(involved));
        return map;
    }
}
