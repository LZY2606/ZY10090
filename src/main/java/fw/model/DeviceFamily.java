package fw.model;

import fw.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imported device-family manifest: hardware revisions, per-revision bootloader
 * floors, the component universe and the default flash/update order.
 */
public record DeviceFamily(
        String family,
        String name,
        List<String> componentOrder,
        Map<String, Revision> revisions,
        Map<String, ComponentInfo> components
) {

    public record Revision(String label, String bootloaderMin) {
    }

    public record ComponentInfo(String label, String defaultProbe, String defaultHealth) {
    }

    public DeviceFamily {
        revisions = Map.copyOf(revisions);
        components = Map.copyOf(components);
        componentOrder = List.copyOf(componentOrder);
    }

    @SuppressWarnings("unchecked")
    public static DeviceFamily fromMap(Map<String, Object> m) {
        String family = Json.reqString(m, "family");
        String name = Json.str(m, "name", family);

        Map<String, Revision> revisions = new LinkedHashMap<>();
        for (Object o : Json.reqList(m, "revisions")) {
            if (!(o instanceof Map<?, ?> rm)) {
                throw new Json.JsonException("Each revision must be an object");
            }
            Map<String, Object> rm2 = (Map<String, Object>) rm;
            String id = Json.reqString(rm2, "id");
            String bootMin = Json.reqString(rm2, "bootloader_min");
            if (!Version.isValid(bootMin)) {
                throw new Json.JsonException("Invalid bootloader_min for revision " + id);
            }
            revisions.put(id, new Revision(Json.str(rm2, "label", id), bootMin));
        }

        List<String> order = new ArrayList<>();
        Map<String, ComponentInfo> components = new LinkedHashMap<>();
        Object compObj = m.get("components");
        List<Object> compList;
        if (compObj instanceof List<?> l) {
            compList = (List<Object>) l;
        } else if (compObj instanceof Map<?, ?> cm) {
            // object form: keys define flash order
            compList = new ArrayList<>();
            for (Map.Entry<?, ?> e : cm.entrySet()) {
                Map<String, Object> merged = new LinkedHashMap<>();
                merged.put("id", String.valueOf(e.getKey()));
                if (e.getValue() instanceof Map<?, ?> vm) {
                    merged.putAll((Map<String, Object>) vm);
                }
                compList.add(merged);
            }
        } else {
            throw new Json.JsonException("Family must define 'components'");
        }
        for (Object o : compList) {
            if (!(o instanceof Map<?, ?> cm)) {
                throw new Json.JsonException("Each component must be an object");
            }
            Map<String, Object> cm2 = (Map<String, Object>) cm;
            String id = Json.reqString(cm2, "id");
            if (order.contains(id)) {
                throw new Json.JsonException("Duplicate component " + id);
            }
            order.add(id);
            components.put(id, new ComponentInfo(
                    Json.str(cm2, "label", id),
                    cm2.get("probe") instanceof String s && !s.isEmpty() ? s : null,
                    cm2.get("health_check") instanceof String h && !h.isEmpty() ? h : null));
        }
        return new DeviceFamily(family, name, order, revisions, components);
    }

    public boolean hasRevision(String rev) {
        return revisions.containsKey(rev);
    }

    public String bootloaderFloorFor(String rev) {
        Revision r = revisions.get(rev);
        return r == null ? null : r.bootloaderMin();
    }

    public String probeFor(String component, FirmwarePackage pkg) {
        if (pkg != null && pkg.probe() != null) {
            return pkg.probe();
        }
        ComponentInfo info = components.get(component);
        if (info != null && info.defaultProbe() != null) {
            return info.defaultProbe();
        }
        return "probe:" + component + ":version";
    }

    public String healthFor(String component, FirmwarePackage pkg) {
        if (pkg != null && pkg.healthCheck() != null) {
            return pkg.healthCheck();
        }
        ComponentInfo info = components.get(component);
        if (info != null && info.defaultHealth() != null) {
            return info.defaultHealth();
        }
        return "health:" + component + ":ok";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("family", family);
        m.put("name", name);
        List<Map<String, Object>> revList = new ArrayList<>();
        revisions.forEach((id, r) -> {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", id);
            rm.put("label", r.label());
            rm.put("bootloader_min", r.bootloaderMin());
            revList.add(rm);
        });
        m.put("revisions", revList);
        List<Map<String, Object>> compList = new ArrayList<>();
        for (String id : componentOrder) {
            ComponentInfo info = components.get(id);
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", id);
            cm.put("label", info.label());
            cm.put("probe", info.defaultProbe());
            cm.put("health_check", info.defaultHealth());
            compList.add(cm);
        }
        m.put("components", compList);
        return m;
    }
}
