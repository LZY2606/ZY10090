package fwplan;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import fwplan.demo.SeedData;
import fwplan.model.Plan;
import fwplan.model.Receipt;
import fwplan.model.ReleasePlan;
import fwplan.store.Store;
import fwplan.web.AppService;

/** 端到端：种子导入 → 分析 → 冻结/重算 → 建方案 → 批准快照 → 幂等回执 → 导出。 */
public final class EndToEndTest {

    public static void run() throws Exception {
        Path dir = Files.createTempDirectory("fwplan-e2e");
        Store store = new Store(dir);
        AppService service = new AppService(store);
        SeedData.seedIfEmpty(service);

        // 依赖环 cohort 必须可行且使用 bundle
        var cycleAnalysis = service.analyzeCohort("cohort-cycle");
        Plan cyclePlan = Plan.fromMap((java.util.Map<String, Object>) cycleAnalysis.get("plan"));
        Test.check(cyclePlan.feasible(), "cycle cohort 可行");
        Test.check(cyclePlan.steps().stream().anyMatch(s -> s.bundle()), "cycle 需要 bundle");

        // 存储降级 cohort 必须不可行
        var downAnalysis = service.analyzeCohort("cohort-downgrade");
        Plan downPlan = Plan.fromMap((java.util.Map<String, Object>) downAnalysis.get("plan"));
        Test.check(!downPlan.feasible(), "降级 cohort 不可行");
        Test.check(!downPlan.conflicts().isEmpty(), "必须返回最小冲突集合");

        // 冻结后重算：冻结 cohort 保留
        service.setFrozen("cohort-stable", true);
        var recomputed = service.recompute();
        Test.eq(((List<?>) recomputed.get("frozenKept")).size(), 2, "两个冻结 cohort 原样保留");
        Test.fails(() -> service.upsertCohort(Map.of(
                "cohortId", "cohort-stable", "label", "x", "familyId", "edge-node",
                "hwRevision", "r2", "current", Map.of(), "target", Map.of())),
                RuntimeException.class, "冻结", "冻结 cohort 禁止修改");

        // 只基于可行 cohort 建方案（降级 cohort 不应能发布）
        Test.fails(() -> service.createRelease(Map.of(
                "releaseId", "rel-bad", "cohortIds", List.of("cohort-downgrade"))),
                RuntimeException.class, "不可行", "不可行 cohort 不能发布");

        ReleasePlan release = service.createRelease(Map.of(
                "releaseId", "rel-1", "displayName", "夜间发布",
                "cohortIds", List.of("cohort-stable", "cohort-cycle")));
        Test.eq(release.status(), "draft", "新方案是 draft");
        ReleasePlan approved = service.approveRelease("rel-1");
        Test.eq(approved.status(), "approved", "批准后为 approved");
        Test.check(!approved.snapshot().isEmpty(), "批准必须绑定快照");
        Test.fails(() -> service.approveRelease("rel-1"), RuntimeException.class, "draft",
                "重复批准必须状态冲突");

        // 批准后再“上传”新元数据不影响已绑定快照：新签名版本加入目录，但导出快照仍是批准时刻
        String snapshotBefore = fwplan.util.Json.write(approved.snapshot());
        String newSha = "c".repeat(64);
        service.getClass(); // 快照字符串在加入前后保持一致
        // 直接往 catalog 放一个新固件（等价于批准后的新上传），不触碰任何已批准快照文件
        service.registerNewFirmwareAfterApproval();
        String snapshotAfter = fwplan.util.Json.write(service.releaseOrThrow("rel-1").snapshot());
        Test.eq(snapshotAfter, snapshotBefore, "批准后新上传不得改变已绑定快照");

        // 推进回执：同 requestId 重放不重复推进
        Map<String, Object> adv1 = Map.of("requestId", "req-100", "releaseId", "rel-1",
                "cohortId", "cohort-stable", "deviceId", "dev-7");
        Receipt first = service.advanceDevice(adv1);
        Test.eq(first.status(), "ACCEPTED", "首次推进接受");
        Receipt replay = service.advanceDevice(adv1);
        Test.check(replay.replay(), "同 requestId 必须标记为重放");
        Test.eq(replay.toPhase(), first.toPhase(), "重放不得推进到新阶段");

        // 单阶段 cohort 已到末尾：换新 requestId 也必须拒绝，且不会产生第二条业务结果
        Test.fails(() -> service.advanceDevice(Map.of("requestId", "req-101", "releaseId", "rel-1",
                "cohortId", "cohort-stable", "deviceId", "dev-7")),
                RuntimeException.class, "最后阶段", "已完成的设备不能重复推进");
        // 不同设备仍可推进同一阶段
        Receipt other = service.advanceDevice(Map.of("requestId", "req-102", "releaseId", "rel-1",
                "cohortId", "cohort-stable", "deviceId", "dev-8"));
        Test.eq(other.toPhase(), "phase-0", "新设备正常推进到首个阶段");

        // 导出包含决定依据、签名校验与失败分支
        var export = service.exportRelease("rel-1");
        Test.check(export.containsKey("decisionBasis"), "导出含决定依据");
        Test.check(export.containsKey("signatureVerification"), "导出含签名校验");
        Test.check(export.containsKey("failureBranches"), "导出含失败分支");
        var sig = (Map<?, ?>) export.get("signatureVerification");
        var checks = (List<?>) sig.get("firmwareChecks");
        Test.check(checks.stream().allMatch(c -> Boolean.TRUE.equals(((Map<?, ?>) c).get("valid"))),
                "全部固件签名校验为 true");
        Test.check(((List<?>) export.get("failureBranches")).size() > 0,
                "失败分支非空（含每步断电故障点）");

        // 重启进程后：数据完整、回执仍可重放且不重复推进
        AppService restarted = new AppService(new Store(dir));
        Receipt afterRestart = restarted.advanceDevice(adv1);
        Test.check(afterRestart.replay(), "重启后同 requestId 仍是重放");
        Test.eq(afterRestart.toPhase(), first.toPhase(), "重启后不得重复推进");
        Test.eq(restarted.releases().size(), 1, "发布方案持久化");
    }
}
