package fwplan;

import java.util.List;
import java.util.Map;

import fwplan.model.DeviceState;
import fwplan.model.FamilyManifest;
import fwplan.model.FirmwareArtifact;
import fwplan.model.Plan;
import fwplan.plan.Catalog;
import fwplan.plan.Planner;

public final class PlannerTest {

    static Catalog buildDemoCatalog() {
        Catalog catalog = new Catalog();
        catalog.putManifest(new FamilyManifest("f", "F",
                List.of("bootloader", "mcu", "radio", "sensor"),
                List.of("r1", "r2", "r3"),
                List.of(new FamilyManifest.Gate("bootloader", "1.0.0"),
                        new FamilyManifest.Gate("sensor", "3.0.0"))));
        fw(catalog, "bootloader", "1.0.0", List.of("r1", "r2", "r3"), null, Map.of(), 0);
        fw(catalog, "bootloader", "1.2.0", List.of("r1", "r2", "r3"), null, Map.of(), 0);
        fw(catalog, "bootloader", "2.0.0", List.of("r2", "r3"), null, Map.of(), 0);
        fw(catalog, "mcu", "1.0.0", List.of("r1", "r2", "r3"), "1.0.0", Map.of(), 0);
        fw(catalog, "mcu", "1.4.0", List.of("r1", "r2", "r3"), "1.0.0", Map.of("radio", "[1.0.0,2.0.0)"), 0);
        fw(catalog, "mcu", "2.1.0", List.of("r2", "r3"), "1.2.0", Map.of("radio", "[2.0.0,3.0.0)"), 0);
        fw(catalog, "radio", "1.0.0", List.of("r1", "r2", "r3"), "1.0.0", Map.of(), 0);
        fw(catalog, "radio", "1.5.0", List.of("r1", "r2", "r3"), "1.0.0", Map.of("mcu", "[1.4.0,2.1.0)"), 0);
        fw(catalog, "radio", "2.0.0", List.of("r2", "r3"), "1.2.0", Map.of("mcu", "[2.1.0,3.0.0)"), 0);
        fw(catalog, "sensor", "1.0.0", List.of("r1", "r2"), "1.0.0", Map.of(), 0);
        fw(catalog, "sensor", "2.0.0", List.of("r1", "r2"), "1.0.0", Map.of("radio", "[1.0.0,3.0.0)"), 0);
        fw(catalog, "sensor", "3.0.0", List.of("r2", "r3"), "1.2.0", Map.of("radio", "[2.0.0,3.0.0)"), 1);
        return catalog;
    }

    static void fw(Catalog catalog, String component, String version, List<String> hw,
                   String minBoot, Map<String, String> companions, int epoch) {
        String sha = "0".repeat(64) + component.replaceAll("[^a-z]", "") + version.replace(".", "");
        sha = sha.substring(sha.length() - 64);
        catalog.putArtifact(new FirmwareArtifact(
                "f", component, version, sha, "test", "sig", hw, minBoot, companions, epoch));
    }

    static DeviceState state(String boot, String mcu, String radio, String sensor) {
        return new DeviceState(Map.of("bootloader", boot, "mcu", mcu, "radio", radio, "sensor", sensor));
    }

    public static void run() {
        straightforwardPath();
        dependencyCycleNeedsBundle();
        storageEpochDowngradeConflict();
        missingCommonVersion();
        hardwareUnsupported();
        bootloaderFloorConflict();
        sameNameDifferentHashRejected();
    }

    static void straightforwardPath() {
        Planner planner = new Planner(buildDemoCatalog());
        Plan plan = planner.analyze("f", "r2",
                state("1.0.0", "1.0.0", "1.0.0", "1.0.0"),
                state("1.2.0", "1.4.0", "1.5.0", "2.0.0"));
        Test.check(plan.feasible(), "常规升级应当可行");
        Test.check(plan.steps().stream().noneMatch(s -> s.bundle()), "常规路径不应需要 bundle");
        boolean sawBootFirst = plan.steps().get(0).components().contains("bootloader");
        Test.check(sawBootFirst, "bootloader 必须先于要求 1.2.0 下限的组件升级（步骤序合法即可）");
    }

    static void dependencyCycleNeedsBundle() {
        Planner planner = new Planner(buildDemoCatalog());
        Plan plan = planner.analyze("f", "r2",
                state("1.2.0", "1.4.0", "1.5.0", "2.0.0"),
                state("1.2.0", "2.1.0", "2.0.0", "3.0.0"));
        Test.check(plan.feasible(), "依赖环场景 bundle 后应当可行");
        Test.check(plan.steps().stream().anyMatch(s -> s.bundle()), "必须出现显式标注的 bundle 步骤");
    }

    static void storageEpochDowngradeConflict() {
        Planner planner = new Planner(buildDemoCatalog());
        Plan plan = planner.analyze("f", "r2",
                state("1.2.0", "2.1.0", "2.0.0", "3.0.0"),
                state("1.2.0", "1.4.0", "1.5.0", "2.0.0"));
        Test.check(!plan.feasible(), "存储 epoch 下降必须判为不可行");
        Test.check(plan.conflicts().stream().anyMatch(c ->
                c.code().equals("STORAGE_FORMAT_BREAK") || c.code().equals("GATE_CROSSED")),
                "必须给出存储格式/gate 最小冲突");
    }

    static void missingCommonVersion() {
        Catalog catalog = buildDemoCatalog();
        String sha = "a".repeat(64);
        catalog.putArtifact(new FirmwareArtifact("f", "mcu", "9.9.9", sha, "test", "sig",
                List.of("r2"), "1.2.0", Map.of("radio", "[9.0.0,10.0.0)"), 0));
        Planner planner = new Planner(catalog);
        Plan plan = planner.analyze("f", "r2",
                state("1.2.0", "1.4.0", "1.5.0", "2.0.0"),
                state("1.2.0", "9.9.9", "2.0.0", "2.0.0"));
        Test.check(!plan.feasible(), "没有共同版本时必须不可行");
        Test.check(plan.conflicts().stream().anyMatch(c -> c.code().equals("NO_COMMON_VERSION")),
                "必须报 NO_COMMON_VERSION");
    }

    static void hardwareUnsupported() {
        Planner planner = new Planner(buildDemoCatalog());
        Plan plan = planner.analyze("f", "r3",
                state("2.0.0", "2.1.0", "2.0.0", "3.0.0"),
                state("2.0.0", "2.1.0", "2.0.0", "2.0.0"));
        Test.check(!plan.feasible(), "r3 无 sensor 2.0.0，目标不合法");
        Test.check(plan.conflicts().stream().anyMatch(c ->
                        c.code().equals("NO_SUCH_VERSION") || c.code().equals("HARDWARE_UNSUPPORTED")),
                "应报缺失版本或硬件不支持");
    }

    static void bootloaderFloorConflict() {
        Planner planner = new Planner(buildDemoCatalog());
        Plan plan = planner.analyze("f", "r2",
                state("1.0.0", "1.0.0", "1.0.0", "1.0.0"),
                state("1.0.0", "1.0.0", "1.0.0", "3.0.0"));
        Test.check(!plan.feasible(), "sensor 3 要求 bootloader 1.2，现状不满足");
        Test.check(plan.conflicts().stream().anyMatch(c -> c.code().equals("BOOTLOADER_TOO_OLD")),
                "必须报 BOOTLOADER_TOO_OLD");
    }

    static void sameNameDifferentHashRejected() {
        Catalog catalog = buildDemoCatalog();
        FirmwareArtifact other = new FirmwareArtifact("f", "mcu", "1.0.0",
                "b".repeat(64), "test", "sig", List.of("r1", "r2", "r3"), "1.0.0", Map.of(), 0);
        Test.fails(() -> catalog.putArtifact(other), RuntimeException.class, "同名包不能替换",
                "同名不同哈希必须状态冲突");
    }
}
