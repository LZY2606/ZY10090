package fwplan.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import fwplan.model.Conflict;
import fwplan.model.DeviceState;
import fwplan.model.FamilyManifest;
import fwplan.model.FirmwareArtifact;
import fwplan.model.Plan;
import fwplan.model.Step;
import fwplan.model.Version;

/**
 * 规划引擎。
 *
 * 转移：先搜索单组件刷写；不可达时允许 2/3 组件同刷的 bundle 步骤（显式标注，刻画依赖环）。
 * 规则判定全部委托 {@link Rules}（与模拟器共用）。不做“最新版优先”硬选：
 * 不可达时输出最小冲突集合。
 */
public final class Planner {

    private static final int VISIT_CAP = 200_000;

    private final Catalog catalog;

    public Planner(Catalog catalog) { this.catalog = catalog; }

    public Plan analyze(String familyId, String hwRevision, DeviceState current, DeviceState target) {
        FamilyManifest manifest = catalog.manifest(familyId)
                .orElseThrow(() -> new ValidationException("未知设备族: " + familyId));
        if (!manifest.hwRevisions().contains(hwRevision)) {
            throw new ValidationException("设备族不支持硬件修订: " + hwRevision);
        }
        String hw = hwRevision;
        Rules rules = new Rules(catalog, manifest, hw);
        Ctx ctx = new Ctx(rules);

        List<Conflict> conflicts = new ArrayList<>();
        conflicts.addAll(rules.stateConflicts(current, "现状"));
        conflicts.addAll(rules.stateConflicts(target, "目标"));
        if (!conflicts.isEmpty()) return infeasible(manifest, hw, current, target, dedupe(conflicts));

        String gateBlock = directGateBlock(rules, manifest, current, target);
        if (gateBlock != null) {
            conflicts.add(new Conflict("GATE_CROSSED", gateBlock, List.of("gate")));
            return infeasible(manifest, hw, current, target, conflicts);
        }

        SearchResult singles = bfs(rules, current, target, 1);
        if (singles.path != null) return feasible(manifest, hw, current, target, singles.path, false);
        SearchResult bundles = bfs(rules, current, target, Math.min(3, manifest.components().size()));
        if (bundles.path != null) return feasible(manifest, hw, current, target, bundles.path, true);

        conflicts.addAll(diagnose(rules, current, target, singles, bundles));
        return infeasible(manifest, hw, current, target, dedupe(conflicts));
    }

    private static Plan feasible(FamilyManifest manifest, String hw, DeviceState current,
                                 DeviceState target, List<Step> steps, boolean bundleUsed) {
        Map<String, Object> rationale = new LinkedHashMap<>();
        rationale.put("search", "BFS over component-version states");
        rationale.put("singleComponentFirst", true);
        rationale.put("maxBundleWidth", Math.min(3, manifest.components().size()));
        rationale.put("bundleUsed", bundleUsed);
        rationale.put("steps", steps.size());
        return new Plan(manifest.familyId(), hw, current, target, true, steps, List.of(), rationale);
    }

    private static Plan infeasible(FamilyManifest manifest, String hw, DeviceState current,
                                   DeviceState target, List<Conflict> conflicts) {
        Map<String, Object> rationale = new LinkedHashMap<>();
        rationale.put("search", "BFS over component-version states");
        rationale.put("conflictCount", conflicts.size());
        rationale.put("componentCount", manifest.components().size());
        return new Plan(manifest.familyId(), hw, current, target, false, List.of(), conflicts, rationale);
    }

    private static String directGateBlock(Rules rules, FamilyManifest manifest,
                                          DeviceState from, DeviceState to) {
        for (String component : manifest.components()) {
            String block = rules.gateBlocked(component, from.get(component), to.get(component));
            if (block != null) return block;
        }
        return null;
    }

    private static List<Conflict> dedupe(List<Conflict> conflicts) {
        Map<String, Conflict> seen = new LinkedHashMap<>();
        for (Conflict c : conflicts) {
            String key = c.code() + "|" + c.message() + "|" + String.join(",", c.involved());
            if (!seen.containsKey(key)) seen.put(key, c);
        }
        return new ArrayList<>(seen.values());
    }

    record SearchResult(List<Step> path, Map<String, Integer> blockReasons, boolean capped) {}

    private static final class Ctx {
        final Rules rules;
        Ctx(Rules rules) { this.rules = rules; }
    }

    // ---------- BFS ----------

    static SearchResult bfs(Rules rules, DeviceState start, DeviceState goal, int width) {
        Map<DeviceState, Step> prev = new HashMap<>();
        Set<DeviceState> visited = new HashSet<>();
        Map<String, Integer> blockReasons = new LinkedHashMap<>();
        Deque<DeviceState> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        boolean capped = false;
        while (!queue.isEmpty()) {
            DeviceState node = queue.poll();
            if (node.equals(goal)) {
                return new SearchResult(reconstruct(prev, start, goal), blockReasons, false);
            }
            for (Map.Entry<Step, DeviceState> move : legalMoves(rules, node, width, blockReasons)) {
                if (visited.size() >= VISIT_CAP) { capped = true; break; }
                DeviceState next = move.getValue();
                if (visited.add(next)) {
                    prev.put(next, move.getKey());
                    queue.add(next);
                }
            }
            if (capped) break;
        }
        return new SearchResult(null, blockReasons, capped);
    }

    private static List<Step> reconstruct(Map<DeviceState, Step> prev, DeviceState start, DeviceState goal) {
        List<Step> reversed = new ArrayList<>();
        DeviceState cur = goal;
        while (!cur.equals(start)) {
            Step step = prev.get(cur);
            if (step == null) return List.of();
            reversed.add(step);
            DeviceState back = cur;
            for (Map.Entry<String, String> e : step.toVersions().entrySet()) {
                back = back.with(e.getKey(), step.fromVersions().get(e.getKey()));
            }
            cur = back;
        }
        java.util.Collections.reverse(reversed);
        List<Step> out = new ArrayList<>();
        for (Step s : reversed) {
            out.add(new Step(out.size(), s.components(), s.firmwareId(),
                    s.fromVersions(), s.toVersions(), s.bundle(), s.note()));
        }
        return out;
    }

    static List<Map.Entry<Step, DeviceState>> legalMoves(Rules rules, DeviceState state, int width,
                                                         Map<String, Integer> blockReasons) {
        List<Map.Entry<Step, DeviceState>> moves = new ArrayList<>();
        List<String> comps = rules.manifest.components();
        List<int[]> combos = new ArrayList<>();
        for (int w = 1; w <= width; w++) combinations(comps.size(), w, combos);

        for (int[] combo : combos) {
            List<List<FirmwareArtifact>> choices = new ArrayList<>();
            boolean possible = true;
            for (int idx : combo) {
                String component = comps.get(idx);
                List<FirmwareArtifact> targets = new ArrayList<>();
                String currentVersion = state.get(component);
                for (FirmwareArtifact artifact : rules.options.getOrDefault(component, List.of())) {
                    if (artifact.version().equals(currentVersion)) continue;
                    String block = rules.gateBlocked(component, currentVersion, artifact.version());
                    if (block != null) {
                        blockReasons.merge(block, 1, Integer::sum);
                        continue;
                    }
                    targets.add(artifact);
                }
                if (targets.isEmpty()) { possible = false; break; }
                choices.add(targets);
            }
            if (!possible) continue;

            int[] cursor = new int[choices.size()];
            while (true) {
                Map<String, String> from = new LinkedHashMap<>();
                Map<String, String> to = new LinkedHashMap<>();
                List<String> changed = new ArrayList<>();
                List<String> firmwareIds = new ArrayList<>();
                for (int i = 0; i < combo.length; i++) {
                    String component = comps.get(combo[i]);
                    FirmwareArtifact artifact = choices.get(i).get(cursor[i]);
                    changed.add(component);
                    from.put(component, state.get(component));
                    to.put(component, artifact.version());
                    firmwareIds.add(artifact.id());
                }
                DeviceState next = state;
                for (Map.Entry<String, String> e : to.entrySet()) next = next.with(e.getKey(), e.getValue());
                List<Conflict> nextConflicts = rules.stateConflicts(next, "");
                if (nextConflicts.isEmpty()) {
                    Step step = new Step(0, changed, String.join("+", firmwareIds), from, to,
                            changed.size() > 1,
                            changed.size() > 1 ? "bundle：同时刷写以满足配套/打破依赖环" : "");
                    moves.add(Map.entry(step, next));
                } else if (changed.size() == 1) {
                    for (Conflict c : nextConflicts) blockReasons.merge(c.message(), 1, Integer::sum);
                }
                if (!advance(cursor, choices)) break;
            }
        }
        return moves;
    }

    private static void combinations(int n, int k, List<int[]> out) {
        int[] idx = new int[k];
        for (int i = 0; i < k; i++) idx[i] = i;
        while (true) {
            out.add(idx.clone());
            int i = k - 1;
            while (i >= 0 && idx[i] == n - k + i) i--;
            if (i < 0) return;
            idx[i]++;
            for (int j = i + 1; j < k; j++) idx[j] = idx[j - 1] + 1;
        }
    }

    private static boolean advance(int[] cursor, List<List<FirmwareArtifact>> choices) {
        int i = cursor.length - 1;
        while (i >= 0) {
            cursor[i]++;
            if (cursor[i] < choices.get(i).size()) {
                for (int j = i + 1; j < cursor.length; j++) cursor[j] = 0;
                return true;
            }
            cursor[i] = 0;
            i--;
        }
        return false;
    }

    // ---------- 不可达诊断 ----------

    @SuppressWarnings("unchecked")
    static List<Conflict> diagnose(Rules rules, DeviceState current, DeviceState target,
                                   SearchResult singles, SearchResult bundles) {
        List<Conflict> conflicts = new ArrayList<>();

        singles.blockReasons().entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .filter(m -> m.contains("跨越") || m.contains("存储格式") || m.contains("bootloader"))
                .limit(3)
                .forEach(m -> conflicts.add(new Conflict(
                        m.contains("跨越") ? "GATE_CROSSED"
                                : m.contains("存储格式") ? "STORAGE_FORMAT_BREAK" : "BOOTLOADER_DOWNGRADE",
                        m, List.of("transition"))));

        conflicts.addAll(companionCycles(rules, current, "现状"));
        conflicts.addAll(companionCycles(rules, target, "目标"));

        for (String component : rules.manifest.components()) {
            for (FirmwareArtifact a : rules.options.getOrDefault(component, List.of())) {
                for (Map.Entry<String, String> e : a.companions().entrySet()) {
                    String peer = e.getKey();
                    if (!rules.manifest.components().contains(peer)) continue;
                    Version.Range range = Version.Range.parse(e.getValue());
                    boolean any = rules.options.getOrDefault(peer, List.of()).stream()
                            .anyMatch(p -> range.contains(Version.of(p.version())));
                    if (!any) {
                        conflicts.add(new Conflict("NO_COMMON_VERSION",
                                component + " " + a.version() + " 要求 " + peer + " 落在 "
                                        + e.getValue() + "，但该硬件上没有任何满足的签名版本",
                                List.of(component, peer, e.getValue())));
                    }
                }
            }
        }

        if (conflicts.isEmpty()) {
            conflicts.add(new Conflict("NO_VALID_ORDERING",
                    "不存在从现状到目标的合法刷写次序：每一步都会破坏 bootloader 下限或配套区间",
                    List.of("ordering")));
        }
        if (singles.capped() || bundles.capped()) {
            conflicts.add(new Conflict("SEARCH_CAPPED",
                    "状态空间过大，搜索在上限处终止；请缩小目标范围", List.of("search")));
        }
        return conflicts;
    }

    private static List<Conflict> companionCycles(Rules rules, DeviceState state, String label) {
        Map<String, Map<String, String>> edges = new LinkedHashMap<>();
        for (String component : rules.manifest.components()) {
            FirmwareArtifact artifact = rules.pick(component, state.get(component));
            if (artifact == null) continue;
            for (String peer : artifact.companions().keySet()) {
                if (rules.manifest.components().contains(peer)) {
                    edges.computeIfAbsent(component, k -> new LinkedHashMap<>())
                            .put(peer, artifact.companions().get(peer));
                }
            }
        }
        List<List<String>> cycles = tarjanCycles(rules, edges);
        List<Conflict> out = new ArrayList<>();
        for (List<String> cycle : cycles) {
            List<String> involved = new ArrayList<>(cycle);
            involved.add(cycle.get(0));
            out.add(new Conflict("DEPENDENCY_CYCLE",
                    label + "存在配套依赖环: " + String.join(" -> ", involved)
                            + "（需要 bundle 同刷；若无共同版本则不可行）", involved));
        }
        return out;
    }

    private static List<List<String>> tarjanCycles(Rules rules, Map<String, Map<String, String>> edges) {
        List<List<String>> cycles = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        int[] counter = {0};
        for (String node : rules.manifest.components()) {
            if (!index.containsKey(node)) {
                strongConnect(node, edges, index, low, stack, onStack, counter, cycles);
            }
        }
        return cycles;
    }

    private static void strongConnect(String v, Map<String, Map<String, String>> edges,
                                      Map<String, Integer> index, Map<String, Integer> low,
                                      Deque<String> stack, Set<String> onStack, int[] counter,
                                      List<List<String>> cycles) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);
        for (String w : edges.getOrDefault(v, Map.of()).keySet()) {
            if (!index.containsKey(w)) {
                strongConnect(w, edges, index, low, stack, onStack, counter, cycles);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> component = new ArrayList<>();
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                component.add(w);
            } while (!w.equals(v));
            if (component.size() > 1) {
                java.util.Collections.reverse(component);
                cycles.add(component);
            }
        }
    }
}
