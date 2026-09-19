package fwplan.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public record SimulationReport(String familyId, String hwRevision, boolean allSafe,
                               List<StepSimulation> steps) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("familyId", familyId);
        map.put("hwRevision", hwRevision);
        map.put("allSafe", allSafe);
        List<Object> list = new ArrayList<>();
        for (StepSimulation s : steps) list.add(s.toMap());
        map.put("steps", list);
        return map;
    }
}
