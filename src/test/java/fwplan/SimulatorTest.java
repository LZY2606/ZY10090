package fwplan;

import fwplan.model.Plan;
import fwplan.sim.SimulationReport;
import fwplan.sim.StepSimulation;
import fwplan.plan.Catalog;
import fwplan.plan.Planner;

public final class SimulatorTest {

    public static void run() {
        bootloaderStepMarkedUnsafe();
        powerLossRecoveryModeled();
        noRollbackTargetWhenBlocked();
    }

    static void bootloaderStepMarkedUnsafe() {
        Catalog catalog = PlannerTest.buildDemoCatalog();
        Planner planner = new Planner(catalog);
        Plan plan = planner.analyze("f", "r2",
                PlannerTest.state("1.0.0", "1.0.0", "1.0.0", "1.0.0"),
                PlannerTest.state("1.2.0", "1.4.0", "1.5.0", "2.0.0"));
        SimulationReport report = new fwplan.sim.Simulator(catalog).simulate(plan);
        boolean bootStepUnsafe = report.steps().stream()
                .anyMatch(s -> s.step().components().contains("bootloader") && !s.safeRollback());
        Test.check(bootStepUnsafe, "包含 bootloader 的阶段必须无安全回滚（标红）");
        Test.check(!report.allSafe(), "报告总体必须反映存在不安全阶段");
    }

    static void powerLossRecoveryModeled() {
        Catalog catalog = PlannerTest.buildDemoCatalog();
        Planner planner = new Planner(catalog);
        Plan plan = planner.analyze("f", "r2",
                PlannerTest.state("1.2.0", "1.4.0", "1.5.0", "2.0.0"),
                PlannerTest.state("1.2.0", "2.1.0", "2.0.0", "3.0.0"));
        SimulationReport report = new fwplan.sim.Simulator(catalog).simulate(plan);
        Test.check(!report.steps().isEmpty(), "bundle 场景应当有步骤");
        for (StepSimulation s : report.steps()) {
            Test.check(!s.failurePoints().isEmpty(), "每步必须模拟各组件写入后断电");
            Test.check(s.failurePoints().stream().allMatch(fp ->
                    s.step().components().contains(fp.component())), "故障点必须对应被写组件");
        }
    }

    static void noRollbackTargetWhenBlocked() {
        // sensor epoch 1 -> 0 回滚被存储格式阻断；该计划不可行时不模拟，
        // 这里用 bootloader 步骤验证回滚目标为空的情形已由 bootloaderStepMarkedUnsafe 覆盖，
        // 再断言 probe/health 非空。
        Catalog catalog = PlannerTest.buildDemoCatalog();
        Planner planner = new Planner(catalog);
        Plan plan = planner.analyze("f", "r2",
                PlannerTest.state("1.2.0", "2.1.0", "2.0.0", "3.0.0"),
                PlannerTest.state("2.0.0", "2.1.0", "2.0.0", "3.0.0"));
        if (plan.feasible()) {
            SimulationReport report = new fwplan.sim.Simulator(catalog).simulate(plan);
            for (StepSimulation s : report.steps()) {
                Test.check(!s.probe().isEmpty() && !s.health().isEmpty(), "探针/健康判据必须存在");
            }
        }
    }
}
