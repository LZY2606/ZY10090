package fwplan.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fwplan.model.Conflict;
import fwplan.model.DeviceState;
import fwplan.model.FamilyManifest;
import fwplan.model.FirmwareArtifact;
import fwplan.model.Plan;
import fwplan.model.Step;
import fwplan.plan.Catalog;
import fwplan.plan.Rules;

/**
 * 断电/回滚模拟器。
 *
 * 刷写次序：非 bootloader 组件按清单声明顺序，bootloader 永远最后刷。
 * 每个组件写入后都注入“立即断电”：重启时回滚本步骤已写组件（逆序逐个撤销），
 * 落到第一个合法的可定义恢复状态；任何撤法都不合法即为不可恢复（砖）。
 * 步骤回滚目标为步骤前状态；bootloader 步骤、gate/存储格式阻断、
 * 或存在不可恢复故障点时，该阶段无安全回滚，必须红色展示。
 */
public final class Simulator {

    private final Catalog catalog;

    public Simulator(Catalog catalog) { this.catalog = catalog; }

    public SimulationReport simulate(Plan plan) {
        FamilyManifest manifest = catalog.manifest(plan.familyId())
                .orElseThrow(() -> new IllegalStateException("missing manifest"));
        Rules rules = new Rules(catalog, manifest, plan.hwRevision());

        List<StepSimulation> simulations = new ArrayList<>();
        DeviceState cursor = plan.current();
        for (Step step : plan.steps()) {
            List<String> writeOrder = writeOrder(manifest, step);

            List<String> probe = new ArrayList<>();
            probe.add("当前版本与登记现状一致: " + cursor.toMap());
            probe.add("电力充足且刷写会话已加锁");
            probe.add("全部目标工件签名与哈希已校验: " + step.firmwareId());
            for (String component : step.components()) {
                FirmwareArtifact target = rules.pick(component, step.toVersions().get(component));
                if (target != null && target.minBootloader() != null) {
                    probe.add(component + " 要求 bootloader >= " + target.minBootloader()
                            + "（当前 " + cursor.get(FirmwareArtifact.BOOTLOADER) + "）");
                }
            }

            List<FailurePoint> failures = new ArrayList<>();
            DeviceState partial = cursor;
            for (String component : writeOrder) {
                partial = partial.with(component, step.toVersions().get(component));
                Map<String, String> partialSnapshot = new LinkedHashMap<>(partial.versions());
                DeviceState recovered = rollbackUntilStable(rules, partial, cursor, step, writeOrder, component);
                boolean recovers = recovered != null;
                String diagnosis;
                if (recovers) {
                    diagnosis = "重启时回滚本步骤已写组件，恢复到: " + recovered.toMap();
                } else {
                    diagnosis = "写入 " + component + " " + step.toVersions().get(component)
                            + " 后断电：任何回滚次序都留下不合法状态（砖化，无恢复状态）";
                }
                failures.add(new FailurePoint(step.index(), component,
                        step.toVersions().get(component), partialSnapshot,
                        recovers, recovered == null ? null : recovered.versions(), diagnosis));
            }

            DeviceState after = cursor;
            for (Map.Entry<String, String> e : step.toVersions().entrySet()) {
                after = after.with(e.getKey(), e.getValue());
            }

            List<String> health = new ArrayList<>();
            List<Conflict> afterConflicts = rules.stateConflicts(after, "");
            health.add("版本核对: " + after.toMap());
            health.add(afterConflicts.isEmpty()
                    ? "bootloader 下限与全部配套区间检查通过"
                    : "健康检查失败: " + afterConflicts.get(0).message());
            health.add("存储自检与关键遥测读数在阈值内");

            DeviceState rollbackTarget = cursor;
            boolean safe = true;
            String reason = "可回滚到步骤前状态: " + cursor.toMap();

            if (step.components().contains(FirmwareArtifact.BOOTLOADER)) {
                safe = false;
                reason = "包含 bootloader 刷写：bootloader 无安全降级路径";
            } else {
                for (String component : step.components()) {
                    String block = rules.gateBlocked(component,
                            step.toVersions().get(component), step.fromVersions().get(component));
                    if (block != null) {
                        safe = false;
                        reason = "回滚被阻断: " + block;
                        break;
                    }
                }
            }
            for (FailurePoint fp : failures) {
                if (!fp.recovers()) {
                    safe = false;
                    reason = "存在不可恢复故障点: " + fp.component()
                            + " 写入后断电无定义恢复状态";
                    break;
                }
            }

            simulations.add(new StepSimulation(step, cursor, after, probe, health,
                    safe ? rollbackTarget : (safeRollbackStateExists(rules, cursor, step) ? rollbackTarget : null),
                    safe, reason, failures));
            cursor = after;
        }

        boolean allSafe = simulations.stream().allMatch(stepSimulation -> stepSimulation.safeRollback());
        return new SimulationReport(plan.familyId(), plan.hwRevision(), allSafe, simulations);
    }

    private List<String> writeOrder(FamilyManifest manifest, Step step) {
        List<String> order = new ArrayList<>();
        for (String component : manifest.components()) {
            if (step.components().contains(component)
                    && !FirmwareArtifact.BOOTLOADER.equals(component)) {
                order.add(component);
            }
        }
        if (step.components().contains(FirmwareArtifact.BOOTLOADER)) {
            order.add(FirmwareArtifact.BOOTLOADER);
        }
        return order;
    }

    /** 断电恢复：把本步骤已写的组件逆序回滚，找第一个合法状态。 */
    private DeviceState rollbackUntilStable(Rules rules, DeviceState partial, DeviceState before,
                                            Step step, List<String> writeOrder, String lastWritten) {
        DeviceState candidate = partial;
        if (rules.stateConflicts(candidate, "").isEmpty()) return candidate;
        for (int i = writeOrder.indexOf(lastWritten); i >= 0; i--) {
            String component = writeOrder.get(i);
            candidate = candidate.with(component, before.get(component));
            if (rules.stateConflicts(candidate, "").isEmpty()) return candidate;
        }
        return null;
    }

    private boolean safeRollbackStateExists(Rules rules, DeviceState before, Step step) {
        return rules.stateConflicts(before, "").isEmpty();
    }
}
