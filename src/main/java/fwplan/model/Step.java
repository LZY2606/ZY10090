package fwplan.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 升级路径中的一个原子步骤：把 components 中的组件从当前版本刷写为新版本。
 * bundle 步骤一次改多个组件（用于打破依赖环）。
 */
public record Step(
        int index,
        List<String> components,
        String firmwareId,
        Map<String, String> fromVersions,
        Map<String, String> toVersions,
        boolean bundle,
        String note) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("index", index);
        map.put("components", new ArrayList<>(components));
        map.put("firmwareId", firmwareId);
        map.put("fromVersions", new LinkedHashMap<>(fromVersions));
        map.put("toVersions", new LinkedHashMap<>(toVersions));
        map.put("bundle", bundle);
        map.put("note", note == null ? "" : note);
        return map;
    }
}
