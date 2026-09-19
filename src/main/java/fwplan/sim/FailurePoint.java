package fwplan.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一个步骤内部“写入某组件后断电”的故障点模拟结果。 */
public record FailurePoint(
        int stepIndex,
        String component,
        String partialVersion,
        Map<String, String> partialState,
        boolean recovers,
        Map<String, String> recoveryState,
        String diagnosis) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("stepIndex", stepIndex);
        map.put("component", component);
        map.put("partialVersion", partialVersion);
        map.put("partialState", new LinkedHashMap<>(partialState));
        map.put("recovers", recovers);
        map.put("recoveryState", recoveryState == null ? null : new LinkedHashMap<>(recoveryState));
        map.put("diagnosis", diagnosis);
        return map;
    }
}
