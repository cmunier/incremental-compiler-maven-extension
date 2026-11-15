package eu.munier.maven.incremental.state;

import eu.munier.maven.incremental.bytecode.ClassFileAnalyzer;
import eu.munier.maven.incremental.bytecode.ClassFileSummary;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.inject.Named;
import javax.inject.Singleton;

/**
 * Reactor-wide cache of class ABI and constant fingerprints across multi-module builds.
 */
@Named
@Singleton
public class ReactorAbiCache {

    private final ClassFileAnalyzer analyzer = new ClassFileAnalyzer();
    private final Map<Path, Map<String, ClassFileSummary>> moduleOutputCache = new ConcurrentHashMap<>();
    private final Map<Path, CachedFileSummary> diskClassCache = new ConcurrentHashMap<>();

    private record CachedFileSummary(long lastModifiedMillis, long size, ClassFileSummary summary) {
    }

    public void updateModuleSummaries(Path outputDirectory, Map<String, ClassFileSummary> summaries) {
        if (outputDirectory == null || summaries == null) {
            return;
        }
        Path normalized = outputDirectory.toAbsolutePath().normalize();
        moduleOutputCache.put(normalized, new ConcurrentHashMap<>(summaries));
    }

    public Map<String, ClassFileSummary> getModuleSummaries(Path outputDirectory) {
        if (outputDirectory == null) {
            return Collections.emptyMap();
        }
        return moduleOutputCache.getOrDefault(
                outputDirectory.toAbsolutePath().normalize(), Collections.emptyMap());
    }

    /**
     * Resolves combined (ABI + ConstantValue) hashes for the given referenced external classes
     * across the provided normalized classpath entries (directories and JARs).
     */
    public Map<String, byte[]> resolveExternalClassHashes(
            List<Path> classpathEntries,
            Set<String> referencedExternalClasses,
            Map<String, Long> previousJarFingerprints,
            Map<String, byte[]> previousExternalHashes,
            Map<String, Long> updatedJarFingerprintsOut) {
        Map<String, byte[]> resolved = new LinkedHashMap<>();
        if (referencedExternalClasses == null || referencedExternalClasses.isEmpty()) {
            recordJarFingerprintsOnly(classpathEntries, updatedJarFingerprintsOut);
            return resolved;
        }

        boolean anyJarChanged = false;
        for (Path entry : classpathEntries) {
            if (Files.isRegularFile(entry)) {
                long fp = computeFileFingerprint(entry);
                String key = entry.toString();
                updatedJarFingerprintsOut.put(key, fp);
                Long prevFp = previousJarFingerprints != null ? previousJarFingerprints.get(key) : null;
                if (prevFp == null || prevFp != fp) {
                    anyJarChanged = true;
                }
            }
        }

        // First resolve classes from directory classpath entries (upstream reactor modules)
        Set<String> remaining = new java.util.LinkedHashSet<>(referencedExternalClasses);
        for (Path entry : classpathEntries) {
            if (Files.isDirectory(entry)) {
                Path normDir = entry.toAbsolutePath().normalize();
                Map<String, ClassFileSummary> inMemory = moduleOutputCache.get(normDir);
                var iterator = remaining.iterator();
                while (iterator.hasNext()) {
                    String binaryName = iterator.next();
                    Path classFile = normDir.resolve(binaryName.replace('.', '/') + ".class");
                    if (Files.isRegularFile(classFile)) {
                        ClassFileSummary summary = getOrAnalyzeClassFile(classFile, inMemory, binaryName);
                        if (summary != null) {
                            resolved.put(binaryName, combineAbiAndConstantsHashWithSupertypes(summary, normDir, inMemory));
                            iterator.remove();
                        }
                    }
                }
            }
        }

        if (remaining.isEmpty()) {
            return resolved;
        }

        // If no JAR changed and all remaining classes already have cached hashes from the previous build, reuse them
        if (!anyJarChanged && previousExternalHashes != null) {
            boolean allPresentInPrev = true;
            for (String binaryName : remaining) {
                byte[] prevHash = previousExternalHashes.get(binaryName);
                if (prevHash != null) {
                    resolved.put(binaryName, prevHash);
                } else {
                    allPresentInPrev = false;
                }
            }
            if (allPresentInPrev) {
                return resolved;
            }
        }

        // Inspect JAR files for remaining classes
        for (Path entry : classpathEntries) {
            if (remaining.isEmpty()) {
                break;
            }
            if (Files.isRegularFile(entry) && entry.toString().endsWith(".jar")) {
                try (JarFile jarFile = new JarFile(entry.toFile())) {
                    var iterator = remaining.iterator();
                    while (iterator.hasNext()) {
                        String binaryName = iterator.next();
                        String entryName = binaryName.replace('.', '/') + ".class";
                        JarEntry jarEntry = jarFile.getJarEntry(entryName);
                        if (jarEntry != null) {
                            try (InputStream in = jarFile.getInputStream(jarEntry)) {
                                byte[] bytes = in.readAllBytes();
                                ClassFileSummary summary = analyzer.analyze(bytes);
                                resolved.put(binaryName, combineAbiAndConstantsHash(summary));
                                iterator.remove();
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // Ignore unreadable JAR
                }
            }
        }

        return resolved;
    }

    private void recordJarFingerprintsOnly(
            List<Path> classpathEntries,
            Map<String, Long> updatedJarFingerprintsOut) {
        for (Path entry : classpathEntries) {
            if (Files.isRegularFile(entry)) {
                updatedJarFingerprintsOut.put(entry.toString(), computeFileFingerprint(entry));
            }
        }
    }

    private ClassFileSummary getOrAnalyzeClassFile(
            Path classFile,
            Map<String, ClassFileSummary> inMemoryModuleMap,
            String binaryName) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(classFile, BasicFileAttributes.class);
            long lastMod = attrs.lastModifiedTime().toMillis();
            long size = attrs.size();
            CachedFileSummary cached = diskClassCache.get(classFile);
            if (cached != null && cached.lastModifiedMillis() == lastMod && cached.size() == size) {
                return cached.summary();
            }
            ClassFileSummary summary = analyzer.analyze(classFile);
            diskClassCache.put(classFile, new CachedFileSummary(lastMod, size, summary));
            if (inMemoryModuleMap != null) {
                inMemoryModuleMap.put(binaryName, summary);
            }
            return summary;
        } catch (IOException e) {
            return inMemoryModuleMap != null ? inMemoryModuleMap.get(binaryName) : null;
        }
    }

    public static byte[] combineAbiAndConstantsHash(ClassFileSummary summary) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(summary.getAbiHash());
            md.update(summary.getConstantsHash());
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private byte[] combineAbiAndConstantsHashWithSupertypes(
            ClassFileSummary rootSummary,
            Path moduleOutputDir,
            Map<String, ClassFileSummary> inMemoryModuleMap) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            Set<String> visited = new java.util.LinkedHashSet<>();
            java.util.Deque<ClassFileSummary> queue = new java.util.ArrayDeque<>();
            queue.add(rootSummary);
            visited.add(rootSummary.getBinaryClassName());

            while (!queue.isEmpty()) {
                ClassFileSummary current = queue.poll();
                md.update(current.getAbiHash());
                md.update(current.getConstantsHash());

                if (current.getSuperClassName() != null
                        && !current.getSuperClassName().startsWith("java.")
                        && visited.add(current.getSuperClassName())) {
                    ClassFileSummary superSum = resolveFromDir(
                            moduleOutputDir, inMemoryModuleMap, current.getSuperClassName());
                    if (superSum != null) {
                        queue.add(superSum);
                    }
                }
                for (String iface : current.getInterfaceNames()) {
                    if (!iface.startsWith("java.") && visited.add(iface)) {
                        ClassFileSummary ifaceSum = resolveFromDir(moduleOutputDir, inMemoryModuleMap, iface);
                        if (ifaceSum != null) {
                            queue.add(ifaceSum);
                        }
                    }
                }
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private ClassFileSummary resolveFromDir(
            Path normDir,
            Map<String, ClassFileSummary> inMemoryModuleMap,
            String binaryName) {
        if (inMemoryModuleMap != null) {
            ClassFileSummary s = inMemoryModuleMap.get(binaryName);
            if (s != null) {
                return s;
            }
        }
        Path classFile = normDir.resolve(binaryName.replace('.', '/') + ".class");
        if (Files.isRegularFile(classFile)) {
            return getOrAnalyzeClassFile(classFile, inMemoryModuleMap, binaryName);
        }
        return null;
    }

    private static long computeFileFingerprint(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return (attrs.lastModifiedTime().toMillis() << 20) ^ attrs.size();
        } catch (IOException e) {
            return -1L;
        }
    }
}
