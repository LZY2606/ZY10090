package fw.core;

import fw.model.DeviceFamily;
import fw.model.FirmwarePackage;
import fw.model.Version;
import fw.model.VersionRange;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Feasibility engine: computes the concrete flash sequence from a device's
 * current component combination to the requested target combination.
 *
 * <p>The planner deliberately has no notion of "newest wins". It explores the
 * full space of legal single-component flashes (BFS, so the returned sequence
 * is shortest), honouring bootloader floors, hardware whitelists, companion
 * version windows, no-skip ceilings and the storage-format downgrade barrier.
 * When the target is unreachable it extracts a minimal irreducible set of the
 * conflicting input facts instead of guessing an alternative target.</p>
 */
public final class Planner {

    public static final List<String> GATE_ORDER = List.of(
            "hardware_revision", "bootloader_floor", "package_requires",
            "max_from_version", "format_downgrade", "target_floor");

    private final Catalog catalog;

    public Planner(Catalog catalog) {
        this.catalog = catalog;
    }

    // ------------------------------------------------------------- fact model

    /** One relaxable input constraint. Minimal conflict sets are subsets hereof. */
    record Fact(String id, String gate, String description, Map<String, Object> evidence) {
    }

    private final class FactBook {
        final Map<String, Fact> facts = new LinkedHashMap<>();

        Fact fact(String id, String gate, String description, Map<String, Object> evidence) {
            return facts.computeIfAbsent(id, k -> new Fact(k, gate, description, evidence));
        }
    }

    // ---------------------------------------------------------------- request

    public static final class Request {
        public String family;
        public String revision;
        public Map<String, String> current = new LinkedHashMap<>();
        public Map<String, String> targets = new LinkedHashMap<>();
        public Set<String> relaxed = Set.of();
    }

    // ------------------------------------------------------------------ node

    private static String stateKey(Map<String, String> state) {
        StringBuilder sb = new StringBuilder();
        state.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            sb.append(e.getKey()).append('=').append(e.getValue() == null ? "-" : e.getValue());
            sb.append(';');
        });
        return sb.toString();
    }

    private static Map<String, String> copy(Map<String, String> s) {
        return new LinkedHashMap<>(s);
    }

    // ----------------------------------------------------------------- entry

    public Map<String, Object> plan(Request req) {
        DeviceFamily family = catalog.family(req.family);
        Map<String, Object> errors = validateRequest(req, family);
        if (!errors.isEmpty()) {
            return Map.of("feasible", false, "kind", "invalid_request", "reasons", errors);
        }

        Map<String, String> start = resolveAll(family, req.current, errors, "current");
        Map<String, String> wanted = resolveAll(family, req.targets, errors, "target");
        if (!errors.isEmpty()) {
            return Map.of("feasible", false, "kind", "invalid_request", "reasons", errors);
        }
        // Close the goal under the packages' companion windows: a target
        // combination is implicitly required to be mutually consistent, so an
        // unmentioned component whose current version violates a target
        // package window is derived (lowest version that satisfies EVERY
        // target window and the hardware floor - never "latest wins").
        Map<String, Object> closureErrors = new LinkedHashMap<>();
        Map<String, String> derivedTargets = new LinkedHashMap<>();
        Map<String, String> goal = closeGoal(family, req.revision, start, wanted,
                derivedTargets, closureErrors);
        if (!closureErrors.isEmpty()) {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("feasible", false);
            bad.put("kind", "no_common_version");
            bad.put("family", req.family);
            bad.put("revision", req.revision);
            bad.put("derived_targets", derivedTargets);
            bad.put("reasons", closureErrors);
            bad.put("steps", List.of());
            bad.put("simulation", List.of());
            bad.put("conflict_set", closureConflictFacts(family, req.revision, start, wanted));
            bad.put("red_steps", List.of());
            return bad;
        }

        FactBook book = new FactBook();
        SearchResult full = bfs(family, req.revision, start, goal, wanted.keySet(), book, Set.of());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("family", req.family);
        result.put("revision", req.revision);
        result.put("start", presentState(family, start));
        result.put("target", presentState(family, goal));
        result.put("requested_targets", presentState(family, wanted));
        result.put("derived_targets", derivedTargets);

        if (full.path != null) {
            result.put("feasible", true);
            result.put("kind", "feasible");
            List<Map<String, Object>> presented = presentSteps(family, full.path);
            result.put("steps", presented);
            result.put("simulation", new Simulator(catalog).simulateSteps(
                    family, req.revision, presented, Map.of()));
            result.put("red_steps", redSteps(family, full.path));
            result.put("conflict_set", List.of());
            return result;
        }

        // Unreachable: derive a minimal irreducible conflict set.
        List<Fact> minimal = minimalConflictSet(family, req.revision, start, goal,
                wanted.keySet(), new ArrayList<>(book.facts.values()));
        result.put("feasible", false);
        result.put("kind", classify(minimal));
        result.put("steps", List.of());
        result.put("simulation", List.of());
        result.put("conflict_set", presentFacts(minimal));
        result.put("all_blocking_facts", presentFacts(new ArrayList<>(book.facts.values())));
        return result;
    }

    private Map<String, String> resolveAll(DeviceFamily family, Map<String, String> refs,
                                           Map<String, Object> errors, String where) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : refs.entrySet()) {
            String comp = e.getKey();
            if (!family.components().containsKey(comp)) {
                errors.put(where + "." + comp, "unknown component for family " + family.family());
                continue;
            }
            FirmwarePackage pkg = catalog.resolve(family.family(), comp, e.getValue());
            if (pkg == null) {
                errors.put(where + "." + comp,
                        "no verified package matches '" + e.getValue() + "'");
                continue;
            }
            out.put(comp, pkg.storageKey());
        }
        return out;
    }

    private Map<String, Object> validateRequest(Request req, DeviceFamily family) {
        Map<String, Object> errors = new LinkedHashMap<>();
        if (family == null) {
            errors.put("family", "unknown device family '" + req.family + "'");
            return errors;
        }
        if (req.revision == null || !family.hasRevision(req.revision)) {
            errors.put("revision", "unknown hardware revision '" + req.revision + "'");
        }
        if (req.current == null) {
            errors.put("current", "missing current state");
        }
        if (req.targets == null) {
            errors.put("targets", "missing target components");
        }
        return errors;
    }

    private String classify(List<Fact> facts) {
        Set<String> gates = new LinkedHashSet<>();
        for (Fact f : facts) {
            gates.add(f.gate);
        }
        if (detectCycle(facts)) {
            return "dependency_cycle";
        }
        // A storage-format barrier is never satisfiable by choosing another
        // package version, so it outranks the companion-window classification.
        if (gates.contains("format_downgrade")) {
            return "storage_format_break";
        }
        if (gates.contains("max_from_version")
                && !gates.contains("package_requires")
                && !gates.contains("hardware_revision")) {
            return "no_skip_violation";
        }
        if (gates.contains("target_floor")) {
            return "bootloader_below_floor";
        }
        if (gates.contains("package_requires") || gates.contains("hardware_revision")) {
            return "no_common_version";
        }
        if (gates.contains("max_from_version")) {
            return "no_skip_violation";
        }
        return "unreachable";
    }

    // ------------------------------------------------------------------- BFS

    private record Edge(Map<String, String> from, Map<String, String> to,
                        FirmwarePackage pkg) {
    }

    private record SearchResult(List<Edge> path) {
    }

    private SearchResult bfs(DeviceFamily family, String revision,
                             Map<String, String> start, Map<String, String> goal,
                             Set<String> explicitTargets,
                             FactBook book, Set<String> relaxedFactIds) {
        String goalKey = stateKey(goal);
        if (stateKey(start).equals(goalKey)) {
            return new SearchResult(List.of());
        }
        Deque<Map<String, String>> queue = new ArrayDeque<>();
        Map<String, Edge> parent = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        queue.add(start);
        seen.add(stateKey(start));
        // Bound the exploration. Dependency windows in this domain are small;
        // a reachable target never needs tens of thousands of states. The cap
        // also turns pathological input into a reported conflict instead of an
        // out-of-memory crash.
        int budget = 50_000;

        while (!queue.isEmpty()) {
            Map<String, String> cur = queue.poll();
            for (String comp : family.componentOrder()) {
                String curRef = cur.get(comp);
                String wantRef = goal.get(comp);
                for (FirmwarePackage candidate : catalog.candidates(family.family(), comp)) {
                    String nextRef = candidate.storageKey();
                    if (nextRef.equals(curRef)) {
                        continue;
                    }
                    // Monotonicity for goal-independent components: components
                    // not mentioned in the request and not derived by the
                    // compatibility closure may never be moved at all. An
                    // explicitly requested component may move in either
                    // direction (downgrades are checked by the format barrier);
                    // a closure-derived component may be raised to satisfy a
                    // window but never above the lowest version the closure
                    // selected.
                    boolean explicit = explicitTargets.contains(comp);
                    FirmwarePackage curPkg = curRef == null ? null
                            : catalog.byStorageKey(curRef);
                    if (!explicit) {
                        FirmwarePackage goalPkg = wantRef == null ? null
                                : catalog.byStorageKey(wantRef);
                        if (goalPkg == null) {
                            continue;
                        }
                        if (curPkg != null && Version.of(candidate.version())
                                .isLessThan(Version.of(curPkg.version()))) {
                            continue;
                        }
                        if (Version.of(candidate.version())
                                .isGreaterThan(Version.of(goalPkg.version()))) {
                            continue;
                        }
                    }
                    if (explicit) {
                        FirmwarePackage goalPkg = wantRef == null ? null
                                : catalog.byStorageKey(wantRef);
                        if (goalPkg != null && Version.of(candidate.version())
                                .isLessThan(Version.of(goalPkg.version()))) {
                            continue;
                        }
                        if (goalPkg != null && curPkg == null
                                && Version.of(candidate.version())
                                        .isGreaterThan(Version.of(goalPkg.version()))) {
                            continue;
                        }
                    }
                    // Prune: never flash a package that is neither currently wanted nor
                    // an intermediate explicitly needed to satisfy another component.
                    // Because we keep all candidates, intermediates (e.g. an older
                    // bootloader-compatible radio) are reachable automatically.
                    Fact gate = checkGate(family, revision, cur, candidate, book);
                    if (gate != null && !relaxedFactIds.contains(gate.id())) {
                        continue;
                    }
                    Map<String, String> next = copy(cur);
                    next.put(comp, nextRef);
                    String nk = stateKey(next);
                    if (!seen.add(nk)) {
                        continue;
                    }
                    if (seen.size() > budget) {
                        return new SearchResult(null);
                    }
                    Edge edge = new Edge(cur, next, candidate);
                    parent.put(nk, edge);
                    if (nk.equals(goalKey)) {
                        return new SearchResult(reconstruct(parent, start, next));
                    }
                    queue.add(next);
                }
            }
        }
        return new SearchResult(null);
    }

    private List<Edge> reconstruct(Map<String, Edge> parent,
                                   Map<String, String> start, Map<String, String> end) {
        List<Edge> path = new ArrayList<>();
        Map<String, String> cursor = end;
        while (!stateKey(cursor).equals(stateKey(start))) {
            Edge edge = parent.get(stateKey(cursor));
            if (edge == null) {
                throw new IllegalStateException("broken parent chain");
            }
            path.add(0, edge);
            cursor = edge.from();
        }
        return path;
    }

    /** @return null if the flash is legal, otherwise the blocking fact. */
    private Fact checkGate(DeviceFamily family, String revision,
                           Map<String, String> state, FirmwarePackage pkg, FactBook book) {
        // 1. Hardware whitelist.
        Map<String, String> hw = pkg.hwCompatibility();
        if (!hw.isEmpty()) {
            String allow = hw.get(revision);
            if (allow == null) {
                return book.fact("hw:" + pkg.storageKey() + ":" + revision,
                        "hardware_revision",
                        pkg.packageId() + " " + pkg.version() + " has no compatibility entry "
                                + "for hardware revision " + revision,
                        hwEvidence(pkg, revision));
            }
            if (!"*".equals(allow) && !allow.equals(revision) && !Version.isValid(allow)) {
                return book.fact("hw:" + pkg.storageKey() + ":" + revision,
                        "hardware_revision",
                        pkg.packageId() + " " + pkg.version() + " rejects hardware revision "
                                + revision,
                        hwEvidence(pkg, revision));
            }
        }
        // 2. Bootloader floor required by the payload.
        String blRef = state.get("bootloader");
        FirmwarePackage bl = blRef == null ? null : catalog.byStorageKey(blRef);
        if (pkg.bootloaderMin() != null && !"bootloader".equals(pkg.component())) {
            if (bl == null || Version.of(bl.version()).isLessThan(Version.of(pkg.bootloaderMin()))) {
                return book.fact("blmin:" + pkg.storageKey()
                                + ":" + (bl == null ? "none" : bl.version()),
                        "bootloader_floor",
                        pkg.component() + " " + pkg.version() + " requires bootloader >= "
                                + pkg.bootloaderMin() + " but "
                                + (bl == null ? "none is installed" : "bootloader " + bl.version()),
                        floorEvidence(pkg, bl, pkg.bootloaderMin()));
            }
        }
        // 2b. When flashing the bootloader itself, every already-installed
        // component that declared a bootloader window must still accept the new
        // bootloader - otherwise updating the bootloader first would invalidate
        // a running companion (this is what makes "raise floor first" orderings
        // correct rather than guessed).
        if ("bootloader".equals(pkg.component())) {
            for (String otherComp : family.componentOrder()) {
                if ("bootloader".equals(otherComp)) {
                    continue;
                }
                String otherRef = state.get(otherComp);
                FirmwarePackage other = otherRef == null ? null : catalog.byStorageKey(otherRef);
                if (other == null) {
                    continue;
                }
                VersionRange window = other.requires().get("bootloader");
                if (window != null && !window.contains(pkg.version())) {
                    return book.fact("req:other:" + other.storageKey() + ":bootloader:"
                                    + pkg.version(),
                            "package_requires",
                            other.component() + " " + other.version() + " requires bootloader "
                                    + window + " so bootloader " + pkg.version()
                                    + " cannot be flashed while it is running",
                            reverseRequiresEvidence(other, pkg, window));
                }
            }
        }
        // 3. Companion version windows.
        for (Map.Entry<String, VersionRange> need : pkg.requires().entrySet()) {
            String otherComp = need.getKey();
            String otherRef = state.get(otherComp);
            FirmwarePackage other = otherRef == null ? null : catalog.byStorageKey(otherRef);
            VersionRange range = need.getValue();
            if (other == null) {
                return book.fact("req:" + pkg.storageKey() + ":" + otherComp + ":absent",
                        "package_requires",
                        pkg.component() + " " + pkg.version() + " requires " + otherComp
                                + " " + range + " but no " + otherComp + " is installed",
                        requiresEvidence(pkg, otherComp, range, null));
            }
            if (!range.contains(other.version())) {
                return book.fact("req:" + pkg.storageKey() + ":"
                                + otherComp + ":" + other.version(),
                        "package_requires",
                        pkg.component() + " " + pkg.version() + " requires " + otherComp
                                + " " + range + " but " + other.version() + " is installed",
                        requiresEvidence(pkg, otherComp, range, other));
            }
        }
        // 4. No-skip ceiling (upgrades only).
        FirmwarePackage current = state.containsKey(pkg.component())
                ? catalog.byStorageKey(state.get(pkg.component())) : null;
        if (current != null && pkg.maxFromVersion() != null
                && Version.of(pkg.version()).isGreaterThan(Version.of(current.version()))) {
            if (Version.of(current.version()).isGreaterThan(Version.of(pkg.maxFromVersion()))) {
                return book.fact("skip:" + current.storageKey() + "->" + pkg.storageKey(),
                        "max_from_version",
                        pkg.component() + " cannot jump from " + current.version() + " to "
                                + pkg.version() + "; highest allowed predecessor is "
                                + pkg.maxFromVersion(),
                        skipEvidence(current, pkg));
            }
        }
        // 5. Storage-format downgrade barrier.
        if (current != null
                && pkg.formatVersion() < current.formatVersion()) {
            return book.fact("fmt:" + current.storageKey() + "->" + pkg.storageKey(),
                    "format_downgrade",
                    pkg.component() + " downgrade " + current.version() + " -> " + pkg.version()
                            + " crosses storage format " + current.formatVersion() + " -> "
                            + pkg.formatVersion() + ", which would break on-disk data",
                    formatEvidence(current, pkg));
        }
        // 6. Bootloader floor declared by the hardware revision itself.
        String familyFloor = family.bootloaderFloorFor(revision);
        if ("bootloader".equals(pkg.component())
                && Version.of(pkg.version()).isLessThan(Version.of(familyFloor))) {
            return book.fact("tgtfloor:" + pkg.storageKey() + ":" + revision,
                    "target_floor",
                    "bootloader " + pkg.version() + " is below the hardware floor "
                            + familyFloor + " for revision " + revision,
                    familyFloorEvidence(pkg, family, revision));
        }
        return null;
    }

    // ----------------------------------------------------- minimal conflict set

    /**
     * Greedy removal of irrelevant blocking facts: each remaining fact is
     * necessary for infeasibility (removing it alone opens a path) unless the
     * set contains a hard ordering cycle, in which case every edge of the cycle
     * is required. The result is irreducible even if it is not the unique
     * smallest set — callers get the actual decision basis instead of a
     * newest-version guess.
     */
    private List<Fact> minimalConflictSet(DeviceFamily family, String revision,
                                         Map<String, String> start, Map<String, String> goal,
                                         Set<String> explicitTargets,
                                         List<Fact> allFacts) {
        // Only facts touching components that actually differ between start and
        // goal (or a component demanded by one of those packages) can explain
        // the failure. Facts describing unrelated downgrades BFS merely probed
        // are excluded up front.
        Set<String> relevant = new LinkedHashSet<>();
        for (String comp : family.componentOrder()) {
            String a = start.get(comp);
            String b = goal.get(comp);
            if (!java.util.Objects.equals(a, b)) {
                relevant.add(comp);
                FirmwarePackage gp = b == null ? null : catalog.byStorageKey(b);
                if (gp != null) {
                    relevant.addAll(gp.requires().keySet());
                }
            }
        }
        List<Fact> filtered = new ArrayList<>();
        for (Fact f : allFacts) {
            Object comp = f.evidence().get("package_component");
            Object ec = f.evidence().get("component");
            if (comp == null && ec == null) {
                filtered.add(f);
            } else if (comp != null && relevant.contains(String.valueOf(comp))) {
                filtered.add(f);
            } else if (ec != null && relevant.contains(String.valueOf(ec))) {
                filtered.add(f);
            }
        }
        if (filtered.isEmpty()) {
            filtered = new ArrayList<>(allFacts);
        }
        // Greedy removal: a fact stays only if dropping it on its own makes the
        // goal reachable. The resulting set is irreducible (no fact can be
        // removed without restoring feasibility).
        List<Fact> active = new ArrayList<>(filtered);
        int i = 0;
        while (i < active.size()) {
            Fact candidate = active.remove(i);
            Set<String> relaxed = new LinkedHashSet<>();
            for (Fact f : active) {
                relaxed.add(f.id());
            }
            SearchResult probe = bfs(family, revision, start, goal, explicitTargets, new FactBook(), relaxed);
            if (probe.path != null) {
                active.add(i, candidate);
                i++;
            }
        }
        if (active.isEmpty()) {
            active = new ArrayList<>(filtered);
        }
        return active;
    }

    /**
     * A dependency cycle exists when the package requires-edges among the
     * conflict facts contain a directed loop (A needs companion window only B2
     * provides, B2 needs window only A1 provides, ...).
     */
    private boolean detectCycle(List<Fact> facts) {
        Set<String> reqEdges = new LinkedHashSet<>();
        for (Fact f : facts) {
            if (f.gate().equals("package_requires") && f.evidence().get("package_key") != null) {
                Object target = f.evidence().get("companion_component");
                Object range = f.evidence().get("required_range");
                reqEdges.add(f.evidence().get("package_key") + "->" + target + "#" + range);
            }
        }
        // Direct cycle over companion constraints between two components.
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (Fact f : facts) {
            if (f.gate().equals("package_requires")) {
                String from = String.valueOf(f.evidence().get("package_component"));
                String to = String.valueOf(f.evidence().get("companion_component"));
                graph.computeIfAbsent(from, k -> new LinkedHashSet<>()).add(to);
            }
        }
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();
        for (String node : graph.keySet()) {
            if (dfsCycle(node, graph, visited, stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean dfsCycle(String node, Map<String, Set<String>> graph,
                             Set<String> visited, Set<String> stack) {
        if (stack.contains(node)) {
            return true;
        }
        if (visited.contains(node)) {
            return false;
        }
        visited.add(node);
        stack.add(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            if (dfsCycle(next, graph, visited, stack)) {
                return true;
            }
        }
        stack.remove(node);
        return false;
    }

    // -------------------------------------------------------------- evidence

    private Map<String, Object> hwEvidence(FirmwarePackage pkg, String revision) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("package_id", pkg.packageId());
        m.put("package_component", pkg.component());
        m.put("package_version", pkg.version());
        m.put("package_key", pkg.storageKey());
        m.put("hardware_revision", revision);
        m.put("compatibility_table", new LinkedHashMap<>(pkg.hwCompatibility()));
        return m;
    }

    private Map<String, Object> floorEvidence(FirmwarePackage pkg, FirmwarePackage bl, String need) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("package_id", pkg.packageId());
        m.put("package_component", pkg.component());
        m.put("package_version", pkg.version());
        m.put("package_key", pkg.storageKey());
        m.put("required_bootloader_min", need);
        m.put("installed_bootloader", bl == null ? null : bl.version());
        return m;
    }

    private Map<String, Object> requiresEvidence(FirmwarePackage pkg, String comp,
                                                 VersionRange range, FirmwarePackage installed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("package_id", pkg.packageId());
        m.put("package_component", pkg.component());
        m.put("package_version", pkg.version());
        m.put("package_key", pkg.storageKey());
        m.put("companion_component", comp);
        m.put("required_range", range.toString());
        m.put("installed_companion", installed == null ? null : installed.version());
        return m;
    }

    private Map<String, Object> reverseRequiresEvidence(FirmwarePackage installed,
                                                         FirmwarePackage incomingBoot,
                                                         VersionRange window) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("package_id", installed.packageId());
        m.put("package_component", installed.component());
        m.put("package_version", installed.version());
        m.put("package_key", installed.storageKey());
        m.put("companion_component", "bootloader");
        m.put("required_range", window.toString());
        m.put("incoming_bootloader", incomingBoot.version());
        return m;
    }

    private Map<String, Object> skipEvidence(FirmwarePackage from, FirmwarePackage to) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("component", to.component());
        m.put("from_version", from.version());
        m.put("to_version", to.version());
        m.put("max_allowed_from", to.maxFromVersion());
        m.put("from_key", from.storageKey());
        m.put("to_key", to.storageKey());
        return m;
    }

    private Map<String, Object> formatEvidence(FirmwarePackage from, FirmwarePackage to) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("component", to.component());
        m.put("from_version", from.version());
        m.put("to_version", to.version());
        m.put("from_format", from.formatVersion());
        m.put("to_format", to.formatVersion());
        m.put("from_key", from.storageKey());
        m.put("to_key", to.storageKey());
        return m;
    }

    private Map<String, Object> familyFloorEvidence(FirmwarePackage pkg,
                                                    DeviceFamily family, String revision) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("package_version", pkg.version());
        m.put("package_key", pkg.storageKey());
        m.put("hardware_revision", revision);
        m.put("family_bootloader_floor", family.bootloaderFloorFor(revision));
        return m;
    }

    // ------------------------------------------------------------- rendering

    private Map<String, Object> presentState(DeviceFamily family, Map<String, String> state) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String comp : family.componentOrder()) {
            String ref = state.get(comp);
            FirmwarePackage pkg = ref == null ? null : catalog.byStorageKey(ref);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("component", comp);
            entry.put("package_id", pkg == null ? null : pkg.packageId());
            entry.put("version", pkg == null ? null : pkg.version());
            entry.put("format_version", pkg == null ? null : pkg.formatVersion());
            entry.put("sha256", pkg == null ? null : pkg.sha256());
            entry.put("key", ref);
            out.put(comp, entry);
        }
        return out;
    }

    private List<Map<String, Object>> presentSteps(DeviceFamily family, List<Edge> path) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            Edge edge = path.get(i);
            FirmwarePackage pkg = edge.pkg();
            FirmwarePackage previous = catalog.byStorageKey(edge.from().get(pkg.component()));
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("order", i + 1);
            step.put("component", pkg.component());
            step.put("package_id", pkg.packageId());
            step.put("from_version", previous == null ? null : previous.version());
            step.put("to_version", pkg.version());
            step.put("format_version", pkg.formatVersion());
            step.put("sha256", pkg.sha256());
            step.put("pre_flash_probe", family.probeFor(pkg.component(), pkg));
            step.put("update_action", "flash " + pkg.packageId() + "@" + pkg.version()
                    + " sha256=" + pkg.sha256().substring(0, 12));
            step.put("health_check", family.healthFor(pkg.component(), pkg));
            step.put("rollback_target", rollbackTarget(family, edge, previous));
            step.put("safe_rollback", isSafeRollback(previous, pkg));
            steps.add(step);
        }
        return steps;
    }

    private Map<String, Object> rollbackTarget(DeviceFamily family, Edge edge,
                                               FirmwarePackage previous) {
        FirmwarePackage pkg = edge.pkg();
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("component", pkg.component());
        target.put("package_id", previous == null ? null : previous.packageId());
        target.put("version", previous == null ? null : previous.version());
        target.put("sha256", previous == null ? null : previous.sha256());
        target.put("reachable", isSafeRollback(previous, pkg));
        target.put("reason", rollbackReason(previous, pkg));
        return target;
    }

    private String rollbackReason(FirmwarePackage previous, FirmwarePackage next) {
        if (previous == null) {
            return "no previous firmware slot available";
        }
        if (next.formatVersion() > previous.formatVersion()) {
            return "rollback crosses storage format " + next.formatVersion() + " -> "
                    + previous.formatVersion();
        }
        if (!next.safeRollback()) {
            return "package metadata declares safe_rollback=false";
        }
        return "previous slot retained and format-compatible";
    }

    /** A step is red-flagged unless a concrete, format-compatible rollback exists. */
    private boolean isSafeRollback(FirmwarePackage previous, FirmwarePackage next) {
        if (previous == null) {
            return false;
        }
        if (next.formatVersion() > previous.formatVersion()) {
            return false;
        }
        return next.safeRollback();
    }

    private List<Map<String, Object>> redSteps(DeviceFamily family, List<Edge> path) {
        List<Map<String, Object>> red = new ArrayList<>();
        List<Map<String, Object>> presented = presentSteps(family, path);
        for (Map<String, Object> s : presented) {
            if (!Boolean.TRUE.equals(s.get("safe_rollback"))) {
                Map<String, Object> flag = new LinkedHashMap<>();
                flag.put("order", s.get("order"));
                flag.put("component", s.get("component"));
                flag.put("to_version", s.get("to_version"));
                flag.put("reason", ((Map<?, ?>) s.get("rollback_target")).get("reason"));
                red.add(flag);
            }
        }
        return red;
    }

    private List<Map<String, Object>> presentFacts(List<Fact> facts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Fact f : facts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gate", f.gate());
            m.put("description", f.description());
            m.put("evidence", f.evidence());
            out.add(m);
        }
        return out;
    }

    // -------------------------------------------------------- compatibility API

    /** Builds the compatibility matrix for a family: package x revision + companion. */
    public Map<String, Object> matrix(String familyId) {
        DeviceFamily family = catalog.family(familyId);
        if (family == null) {
            return Map.of("error", "unknown family");
        }
        Map<String, Object> matrix = new LinkedHashMap<>();
        matrix.put("family", family.toMap());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String comp : family.componentOrder()) {
            for (FirmwarePackage pkg : catalog.candidates(familyId, comp)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("component", comp);
                row.put("package_id", pkg.packageId());
                row.put("version", pkg.version());
                row.put("format_version", pkg.formatVersion());
                row.put("sha256", pkg.sha256());
                row.put("safe_rollback", pkg.safeRollback());
                row.put("bootloader_min", pkg.bootloaderMin());
                row.put("max_from_version", pkg.maxFromVersion());
                Map<String, Object> revisions = new LinkedHashMap<>();
                for (String rev : family.revisions().keySet()) {
                    Map<String, String> hw = pkg.hwCompatibility();
                    revisions.put(rev, hw.isEmpty() ? "yes"
                            : hw.containsKey(rev) ? "yes" : "no");
                }
                row.put("hardware", revisions);
                Map<String, Object> requires = new LinkedHashMap<>();
                pkg.requires().forEach((k, v) -> requires.put(k, v.toString()));
                row.put("requires", requires);
                rows.add(row);
            }
        }
        matrix.put("packages", rows);
        return matrix;
    }

    // ------------------------------------------------------------- goal closure

    /**
     * Expands an explicitly requested target into a fully consistent component
     * combination. Components the user pinned are kept verbatim; unmentioned
     * components are either left at their current version (if it satisfies all
     * target windows) or derived to the lowest mutually-compatible candidate.
     */
    private Map<String, String> closeGoal(DeviceFamily family, String revision,
                                          Map<String, String> start,
                                          Map<String, String> wanted,
                                          Map<String, String> derivedTargets,
                                          Map<String, Object> closureErrors) {
        Map<String, String> goal = copy(start);
        goal.putAll(wanted);

        // Fixed-point: each unresolved component is constrained by the windows
        // that currently-resolved target packages place on it.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String comp : family.componentOrder()) {
                String goalRef = goal.get(comp);
                FirmwarePackage current = goalRef == null ? null : catalog.byStorageKey(goalRef);
                // Collect every window pinned by resolved target packages.
                List<WindowDemand> demands = new ArrayList<>();
                for (String otherComp : family.componentOrder()) {
                    if (otherComp.equals(comp)) {
                        continue;
                    }
                    String otherRef = goal.get(otherComp);
                    FirmwarePackage other = otherRef == null ? null
                            : catalog.byStorageKey(otherRef);
                    if (other != null && other.requires().containsKey(comp)) {
                        demands.add(new WindowDemand(other, other.requires().get(comp)));
                    }
                }
                boolean userPinned = wanted.containsKey(comp);
                if (demands.isEmpty()) {
                    continue;
                }
                if (userPinned) {
                    FirmwarePackage pinned = catalog.byStorageKey(wanted.get(comp));
                    for (WindowDemand d : demands) {
                        if (pinned == null || !d.range().contains(pinned.version())) {
                            closureErrors.put("target." + comp,
                                    "pinned " + comp + " " + (pinned == null ? "?" : pinned.version())
                                            + " fails the window " + d.range() + " required by "
                                            + d.source().component() + " " + d.source().version());
                        }
                    }
                    continue;
                }
                boolean currentOk = current != null && current.hwOk(revision);
                if (currentOk) {
                    for (WindowDemand d : demands) {
                        if (!d.range().contains(current.version())) {
                            currentOk = false;
                            break;
                        }
                    }
                }
                if (currentOk && floorOk(family, revision, comp, current)) {
                    continue;
                }
                FirmwarePackage chosen = lowestCompatible(family, revision, comp, demands, goal);
                if (chosen == null) {
                    closureErrors.put("target." + comp,
                            "no verified " + comp + " version satisfies "
                                    + describeDemands(demands) + " on hardware " + revision);
                    continue;
                }
                goal.put(comp, chosen.storageKey());
                derivedTargets.put(comp, chosen.storageKey());
                changed = true;
            }
        }
        return goal;
    }

    private record WindowDemand(FirmwarePackage source, VersionRange range) {
    }

    private boolean floorOk(DeviceFamily family, String revision, String comp,
                            FirmwarePackage pkg) {
        if (!"bootloader".equals(comp)) {
            return true;
        }
        return !Version.of(pkg.version())
                .isLessThan(Version.of(family.bootloaderFloorFor(revision)));
    }

    private FirmwarePackage lowestCompatible(DeviceFamily family, String revision,
                                             String comp, List<WindowDemand> demands,
                                             Map<String, String> goal) {
        FirmwarePackage best = null;
        for (FirmwarePackage candidate : catalog.candidates(family.family(), comp)) {
            if (!candidate.hwOk(revision)) {
                continue;
            }
            if (!floorOk(family, revision, comp, candidate)) {
                continue;
            }
            boolean ok = true;
            for (WindowDemand d : demands) {
                if (!d.range().contains(candidate.version())) {
                    ok = false;
                    break;
                }
            }
            // The candidate's own demands must be satisfiable by already-pinned
            // components or left for the next fixed-point iteration.
            if (!ok) {
                continue;
            }
            if (best == null
                    || Version.of(candidate.version()).isLessThan(Version.of(best.version()))) {
                best = candidate;
            }
        }
        return best;
    }

    private String describeDemands(List<WindowDemand> demands) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < demands.size(); i++) {
            if (i > 0) {
                sb.append(" and ");
            }
            WindowDemand d = demands.get(i);
            sb.append(d.range()).append(" (from ").append(d.source().component())
                    .append(' ').append(d.source().version()).append(')');
        }
        return sb.toString();
    }

    private List<Map<String, Object>> closureConflictFacts(DeviceFamily family, String revision,
                                                           Map<String, String> start,
                                                           Map<String, String> wanted) {
        FactBook book = new FactBook();
        Map<String, String> cur = copy(start);
        cur.putAll(wanted);
        List<Map<String, Object>> out = new ArrayList<>();
        for (String comp : family.componentOrder()) {
            String ref = cur.get(comp);
            FirmwarePackage pkg = ref == null ? null : catalog.byStorageKey(ref);
            if (pkg == null) {
                continue;
            }
            for (Map.Entry<String, VersionRange> e : pkg.requires().entrySet()) {
                String otherRef = cur.get(e.getKey());
                FirmwarePackage other = otherRef == null ? null
                        : catalog.byStorageKey(otherRef);
                if (other == null || !e.getValue().contains(other.version())) {
                    Fact f = book.fact("closure:" + pkg.storageKey() + ":" + e.getKey()
                                    + ":" + (other == null ? "absent" : other.version()),
                            "package_requires",
                            pkg.component() + " " + pkg.version() + " needs " + e.getKey()
                                    + " " + e.getValue() + " but "
                                    + (other == null ? "none installed" : other.version()),
                            requiresEvidence(pkg, e.getKey(), e.getValue(), other));
                    out.add(Map.of("gate", f.gate(), "description", f.description(),
                            "evidence", f.evidence()));
                }
            }
        }
        return out;
    }
}
