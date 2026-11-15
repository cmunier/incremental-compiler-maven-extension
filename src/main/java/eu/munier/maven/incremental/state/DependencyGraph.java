package eu.munier.maven.incremental.state;

import eu.munier.maven.incremental.bytecode.ClassFileSummary;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory class-level dependency and inheritance graph constructed from {@link IncrementalBuildState}.
 */
public final class DependencyGraph {

    private final Map<String, String> classToSourcePath = new LinkedHashMap<>();
    private final Map<String, Set<String>> referencedClassToSourcePaths = new LinkedHashMap<>();
    private final Map<String, Set<String>> superTypeToSubtypes = new LinkedHashMap<>();
    private final Map<String, Set<String>> packageToSourcePaths = new LinkedHashMap<>();
    private final Map<String, Set<String>> packageToWildcardUsingSources = new LinkedHashMap<>();
    private final Map<String, Set<String>> wildcardPkgToSourcePaths = new LinkedHashMap<>();

    public static DependencyGraph fromState(IncrementalBuildState state) {
        DependencyGraph graph = new DependencyGraph();
        graph.rebuild(state);
        return graph;
    }

    public void rebuild(IncrementalBuildState state) {
        classToSourcePath.clear();
        referencedClassToSourcePaths.clear();
        superTypeToSubtypes.clear();
        packageToSourcePaths.clear();
        packageToWildcardUsingSources.clear();
        wildcardPkgToSourcePaths.clear();

        for (SourceFileRecord record : state.getSourceRecords().values()) {
            String srcPath = record.getSourcePath();
            String pkg = record.getPackageName();
            if (pkg != null && !pkg.isEmpty()) {
                packageToSourcePaths
                        .computeIfAbsent(pkg, k -> new LinkedHashSet<>())
                        .add(srcPath);
                if (!record.getWildcardImports().isEmpty()) {
                    packageToWildcardUsingSources
                            .computeIfAbsent(pkg, k -> new LinkedHashSet<>())
                            .add(srcPath);
                }
            }
            for (String wildcard : record.getWildcardImports()) {
                wildcardPkgToSourcePaths
                        .computeIfAbsent(wildcard, k -> new LinkedHashSet<>())
                        .add(srcPath);
            }
            for (String definedClass : record.getDefinedClassNames()) {
                classToSourcePath.put(definedClass, srcPath);
            }
            for (String astRef : record.getAstReferencedClasses()) {
                addDependencyEdge(astRef, srcPath);
            }
        }

        for (Map.Entry<String, ClassFileSummary> entry : state.getLocalClassSummaries().entrySet()) {
            String className = entry.getKey();
            ClassFileSummary summary = entry.getValue();
            String ownerSource = resolveSourcePathForClass(className);

            if (summary.getSuperClassName() != null) {
                superTypeToSubtypes
                        .computeIfAbsent(summary.getSuperClassName(), k -> new LinkedHashSet<>())
                        .add(className);
                if (ownerSource != null) {
                    addDependencyEdge(summary.getSuperClassName(), ownerSource);
                }
            }
            for (String iface : summary.getInterfaceNames()) {
                superTypeToSubtypes
                        .computeIfAbsent(iface, k -> new LinkedHashSet<>())
                        .add(className);
                if (ownerSource != null) {
                    addDependencyEdge(iface, ownerSource);
                }
            }
            if (ownerSource != null) {
                for (String ref : summary.getReferencedClassNames()) {
                    addDependencyEdge(ref, ownerSource);
                }
            }
        }
    }

    private void addDependencyEdge(String referencedBinaryClass, String dependentSourcePath) {
        if (referencedBinaryClass == null || referencedBinaryClass.isEmpty()) {
            return;
        }
        referencedClassToSourcePaths
                .computeIfAbsent(referencedBinaryClass, k -> new LinkedHashSet<>())
                .add(dependentSourcePath);

        // Also link outer class if this is a nested/inner class (Foo$Bar -> Foo)
        int dollarIdx = referencedBinaryClass.indexOf('$');
        if (dollarIdx > 0) {
            String topLevel = referencedBinaryClass.substring(0, dollarIdx);
            referencedClassToSourcePaths
                    .computeIfAbsent(topLevel, k -> new LinkedHashSet<>())
                    .add(dependentSourcePath);
        }
    }

    private String resolveSourcePathForClass(String binaryClassName) {
        String direct = classToSourcePath.get(binaryClassName);
        if (direct != null) {
            return direct;
        }
        int dollarIdx = binaryClassName.indexOf('$');
        if (dollarIdx > 0) {
            return classToSourcePath.get(binaryClassName.substring(0, dollarIdx));
        }
        return null;
    }

    /**
     * Computes all {@code .java} source file paths that must be recompiled when the given
     * classes have had an ABI or constant change, or when hierarchy changes occurred.
     */
    public Set<Path> findAffectedSources(
            Set<String> abiOrConstantChangedClasses,
            Set<String> hierarchyChangedClasses) {
        Set<String> effectiveChangedClasses = new LinkedHashSet<>(abiOrConstantChangedClasses);

        // Any ABI change in a supertype may affect its transitive subtypes
        for (String changedClass : abiOrConstantChangedClasses) {
            effectiveChangedClasses.addAll(getTransitiveSubtypes(changedClass));
        }
        for (String hierClass : hierarchyChangedClasses) {
            effectiveChangedClasses.add(hierClass);
            effectiveChangedClasses.addAll(getTransitiveSubtypes(hierClass));
        }

        Set<Path> affectedSources = new LinkedHashSet<>();
        for (String cls : effectiveChangedClasses) {
            String subtypeSource = resolveSourcePathForClass(cls);
            if (subtypeSource != null && !abiOrConstantChangedClasses.contains(cls)) {
                affectedSources.add(Path.of(subtypeSource));
            }
            Set<String> dependents = referencedClassToSourcePaths.getOrDefault(cls, Collections.emptySet());
            for (String depSource : dependents) {
                affectedSources.add(Path.of(depSource));
            }
        }
        return affectedSources;
    }

    public Set<Path> findDirectDependentsOfExternalClasses(Set<String> changedExternalClasses) {
        return findAffectedSources(changedExternalClasses, changedExternalClasses);
    }

    /**
     * Returns all {@code .java} source files that could be impacted by a new or deleted
     * top-level type in the given packages (due to same-package shadowing of wildcard imports
     * or wildcard import ambiguity across packages per JLS §6.5.2 and §7.5.2).
     */
    public Set<Path> findSourcesAffectedByNamespaceChanges(Set<String> packagesWithAddedOrRemovedTypes) {
        if (packagesWithAddedOrRemovedTypes == null || packagesWithAddedOrRemovedTypes.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Path> affected = new LinkedHashSet<>();
        for (String pkg : packagesWithAddedOrRemovedTypes) {
            if (pkg == null) {
                continue;
            }
            for (String src : packageToWildcardUsingSources.getOrDefault(pkg, Collections.emptySet())) {
                affected.add(Path.of(src));
            }
            for (String src : wildcardPkgToSourcePaths.getOrDefault(pkg, Collections.emptySet())) {
                affected.add(Path.of(src));
            }
        }
        return affected;
    }

    /**
     * Returns all {@code .java} source files belonging to the given packages
     * (used when {@code package-info.java} is modified or removed).
     */
    public Set<Path> findSourcesInPackages(Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Path> affected = new LinkedHashSet<>();
        for (String pkg : packages) {
            for (String src : packageToSourcePaths.getOrDefault(pkg, Collections.emptySet())) {
                affected.add(Path.of(src));
            }
        }
        return affected;
    }

    public Set<String> getTransitiveSubtypes(String rootType) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootType);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            Set<String> direct = superTypeToSubtypes.getOrDefault(current, Collections.emptySet());
            for (String sub : direct) {
                if (visited.add(sub)) {
                    queue.add(sub);
                }
            }
        }
        return visited;
    }
}
