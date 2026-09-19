package fwplan.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设备族清单：声明组件（bootloader 必须有）、支持的硬件修订，
 * 以及不可跨越版本（gates）：(组件, 从版本) 表示从该版本出发禁止向下跨越
 * （只能 >= fromVersion；用于存储格式不可逆升级与安全下限）。
 */
public record FamilyManifest(
        String familyId,
        String displayName,
        List<String> components,
        List<String> hwRevisions,
        List<Gate> gates) {

    public record Gate(String component, String fromVersion) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("component", component);
            map.put("fromVersion", fromVersion);
            return map;
        }
    }

    public FamilyManifest {
        components = List.copyOf(components);
        hwRevisions = List.copyOf(hwRevisions);
        gates = List.copyOf(gates);
    }

    public boolean hasComponent(String name) { return components.contains(name); }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("familyId", familyId);
        map.put("displayName", displayName);
        map.put("components", new ArrayList<>(components));
        map.put("hwRevisions", new ArrayList<>(hwRevisions));
        List<Object> gateList = new ArrayList<>();
        for (Gate gate : gates) gateList.add(gate.toMap());
        map.put("gates", gateList);
        return map;
    }
}
