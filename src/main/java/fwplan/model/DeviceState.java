package fwplan.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设备上各组件的当前版本（组件名 -> 版本字符串）。
 * bootloader 为特殊组件，其余为可更新组件。
 */
public final class DeviceState {

    private final Map<String, String> versions;

    public DeviceState(Map<String, String> versions) {
        this.versions = Map.copyOf(versions);
    }

    public String get(String component) { return versions.get(component); }

    public Map<String, String> versions() { return versions; }

    public DeviceState with(String component, String version) {
        Map<String, String> next = new LinkedHashMap<>(versions);
        if (version == null) next.remove(component);
        else next.put(component, version);
        return new DeviceState(next);
    }

    public Map<String, Object> toMap() {
        return new LinkedHashMap<>(versions);
    }

    public static DeviceState fromMap(Map<String, Object> map) {
        Map<String, String> versions = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            versions.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        return new DeviceState(versions);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DeviceState d && versions.equals(d.versions);
    }

    @Override
    public int hashCode() { return versions.hashCode(); }

    @Override
    public String toString() { return versions.toString(); }
}
