package fw.model;

import fw.json.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Current or desired firmware combination for one device: a map of
 * component -&gt; installed package key (packageId#version#hash8).
 */
public record DeviceState(
        String deviceId,
        String family,
        String revision,
        String cohort,
        boolean frozen,
        Map<String, String> components
) {

    public DeviceState {
        components = Map.copyOf(components);
    }

    public static DeviceState fromMap(Map<String, Object> m) {
        String id = Json.reqString(m, "device_id");
        String family = Json.reqString(m, "family");
        String revision = Json.reqString(m, "revision");
        String cohort = Json.str(m, "cohort", "default");
        boolean frozen = Json.optBool(m, "frozen", false);
        Map<String, String> comps = new LinkedHashMap<>();
        Object co = Json.reqObject(m, "components");
        for (Map.Entry<?, ?> e : ((Map<?, ?>) co).entrySet()) {
            comps.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        return new DeviceState(id, family, revision, cohort, frozen, comps);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("device_id", deviceId);
        m.put("family", family);
        m.put("revision", revision);
        m.put("cohort", cohort);
        m.put("frozen", frozen);
        m.put("components", new LinkedHashMap<>(components));
        return m;
    }

    public DeviceState withFrozen(boolean value) {
        return new DeviceState(deviceId, family, revision, cohort, value, components);
    }

    public String get(String component) {
        return components.get(component);
    }
}
