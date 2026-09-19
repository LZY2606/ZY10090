package fwplan.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fwplan.model.DeviceState;
import fwplan.model.Step;

/**
 * 单个步骤的完整模拟：
 * probe —— 前置探针判据；health —— 刷写后健康判据；
 * rollbackTarget —— 回滚目标状态；safeRollback —— 是否存在安全回滚（false 必须红色展示）。
 */
public record StepSimulation(
        Step step,
        DeviceState beforeState,
        DeviceState afterState,
        List<String> probe,
        List<String> health,
        DeviceState rollbackTarget,
        boolean safeRollback,
        String rollbackReason,
        List<FailurePoint> failurePoints) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("step", step.toMap());
        map.put("beforeState", beforeState.toMap());
        map.put("afterState", afterState.toMap());
        map.put("probe", new ArrayList<>(probe));
        map.put("health", new ArrayList<>(health));
        map.put("rollbackTarget", rollbackTarget == null ? null : rollbackTarget.toMap());
        map.put("safeRollback", safeRollback);
        map.put("rollbackReason", rollbackReason);
        List<Object> fps = new ArrayList<>();
        for (FailurePoint fp : failurePoints) fps.add(fp.toMap());
        map.put("failurePoints", fps);
        return map;
    }
}
