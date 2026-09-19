package fw.core;

import fw.model.DeviceFamily;
import fw.model.FirmwarePackage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** In-memory read model over all imported families and verified packages. */
public final class Catalog {

    private final Map<String, DeviceFamily> families = new LinkedHashMap<>();
    private final Map<String, FirmwarePackage> packagesByKey = new LinkedHashMap<>();
    private final Map<String, List<FirmwarePackage>> packagesByComponent = new LinkedHashMap<>();

    public synchronized void addFamily(DeviceFamily family) {
        families.put(family.family(), family);
    }

    public synchronized DeviceFamily family(String id) {
        return families.get(id);
    }

    public synchronized Collection<DeviceFamily> families() {
        return new ArrayList<>(families.values());
    }

    /**
     * Registers a verified package.
     *
     * @return one of {@code added}, {@code duplicate} (identical record) or
     *         {@code name_tamper} (same id+version, different binary).
     */
    public synchronized PutResult addPackage(FirmwarePackage pkg) {
        String key = pkg.storageKey();
        if (packagesByKey.containsKey(key)) {
            return new PutResult("duplicate", packagesByKey.get(key));
        }
        for (FirmwarePackage existing : packagesByComponent.values().stream()
                .flatMap(List::stream).toList()) {
            if (existing.packageId().equals(pkg.packageId())
                    && existing.version().equals(pkg.version())
                    && !existing.sha256().equals(pkg.sha256())) {
                return new PutResult("name_tamper", existing);
            }
        }
        packagesByKey.put(key, pkg);
        packagesByComponent.computeIfAbsent(componentKey(pkg.family(), pkg.component()),
                        k -> new ArrayList<>())
                .add(pkg);
        return new PutResult("added", pkg);
    }

    public synchronized FirmwarePackage byStorageKey(String storageKey) {
        return packagesByKey.get(storageKey);
    }

    /** Resolves a package reference (storage key, or id#version) in a family. */
    public synchronized FirmwarePackage resolve(String family, String component, String ref) {
        if (ref == null) {
            return null;
        }
        FirmwarePackage direct = packagesByKey.get(ref);
        if (direct != null) {
            return direct;
        }
        List<FirmwarePackage> pool = packagesByComponent
                .get(componentKey(family, component));
        if (pool == null) {
            return null;
        }
        List<FirmwarePackage> matches = new ArrayList<>();
        for (FirmwarePackage p : pool) {
            String shortRef = p.packageId() + "#" + p.version();
            if (shortRef.equals(ref)) {
                matches.add(p);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    public synchronized List<FirmwarePackage> candidates(String family, String component) {
        return new ArrayList<>(
                packagesByComponent.getOrDefault(componentKey(family, component), List.of()));
    }

    public synchronized Optional<FirmwarePackage> latest(String family, String component) {
        return candidates(family, component).stream()
                .max((a, b) -> fw.model.Version.of(a.version())
                        .compareTo(fw.model.Version.of(b.version())));
    }

    public synchronized Collection<FirmwarePackage> allPackages() {
        return new ArrayList<>(packagesByKey.values());
    }

    private static String componentKey(String family, String component) {
        return family.toLowerCase(Locale.ROOT) + "/" + component.toLowerCase(Locale.ROOT);
    }

    public record PutResult(String status, FirmwarePackage pkg) {
    }
}
