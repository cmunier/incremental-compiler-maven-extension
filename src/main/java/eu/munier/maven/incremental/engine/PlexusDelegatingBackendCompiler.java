package eu.munier.maven.incremental.engine;

import eu.munier.maven.incremental.javac.JavaSourceHeaderScanner;
import eu.munier.maven.incremental.state.ClasspathEntryNormalizer;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.codehaus.plexus.compiler.Compiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerException;
import org.codehaus.plexus.compiler.CompilerResult;

/**
 * {@link BackendCompiler} that delegates subset compilation to an existing Plexus {@link Compiler}
 * implementation (such as Eclipse Tycho's {@code JDTCompiler}), preserving OSGi access rules
 * and JDT-specific compiler arguments while adding class-level incremental compilation.
 */
public final class PlexusDelegatingBackendCompiler implements BackendCompiler {

    private final Compiler delegate;
    private final Set<String> knownConstantOwnerClasses;

    public PlexusDelegatingBackendCompiler(Compiler delegate, Set<String> knownConstantOwnerClasses) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.knownConstantOwnerClasses = knownConstantOwnerClasses != null
                ? knownConstantOwnerClasses
                : Collections.emptySet();
    }

    @Override
    public RoundCompilationResult compileSubset(
            CompilerConfiguration baseConfig,
            Set<Path> sourcesToCompile,
            Path outputDirectory) throws CompilerException {
        if (sourcesToCompile.isEmpty()) {
            return new RoundCompilationResult(
                    true,
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptySet());
        }

        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException e) {
            throw new CompilerException("Failed to create output directory: " + outputDirectory, e);
        }

        Map<Path, Long> beforeClasses = snapshotFiles(outputDirectory, ".class");
        Path genSrcDir = baseConfig.getGeneratedSourcesDirectory() != null
                ? baseConfig.getGeneratedSourcesDirectory().toPath().toAbsolutePath().normalize()
                : null;
        Map<Path, Long> beforeAptSources = (genSrcDir != null && Files.isDirectory(genSrcDir))
                ? snapshotFiles(genSrcDir, ".java")
                : Collections.emptyMap();

        Set<File> originalSources = baseConfig.getSourceFiles();
        List<String> originalClasspath = baseConfig.getClasspathEntries() != null
                ? new ArrayList<>(baseConfig.getClasspathEntries())
                : new ArrayList<>();

        try {
            Set<File> subsetFiles = new LinkedHashSet<>();
            for (Path src : sourcesToCompile) {
                subsetFiles.add(src.toAbsolutePath().normalize().toFile());
            }
            baseConfig.setSourceFiles(subsetFiles);

            // Ensure outputDirectory is on classpath so JDT/ECJ resolves unchanged classes of the same bundle
            String selfCpEntry = formatSelfClasspathEntry(outputDirectory, originalClasspath);
            if (!containsOutputDirectory(originalClasspath, outputDirectory)) {
                List<String> augmentedCp = new ArrayList<>(originalClasspath.size() + 1);
                augmentedCp.add(selfCpEntry);
                augmentedCp.addAll(originalClasspath);
                baseConfig.setClasspathEntries(augmentedCp);
            }

            CompilerResult compilerResult = delegate.performCompile(baseConfig);
            boolean success = compilerResult.isSuccess();

            Set<Path> touchedClasses = findModifiedOrExpectedClasses(
                    outputDirectory, beforeClasses, sourcesToCompile);
            Set<Path> touchedAptSources = (genSrcDir != null && Files.isDirectory(genSrcDir))
                    ? findModifiedFiles(genSrcDir, beforeAptSources, ".java")
                    : Collections.emptySet();

            Map<Path, Set<Path>> sourceToClassFiles = new LinkedHashMap<>();
            Map<Path, Set<Path>> sourceToAptSources = new LinkedHashMap<>();
            Map<Path, Set<String>> sourceToAstRefs = new LinkedHashMap<>();
            Map<Path, String> sourceToPkg = new LinkedHashMap<>();
            Map<Path, Set<String>> sourceToWildcards = new LinkedHashMap<>();

            for (Path src : sourcesToCompile) {
                Path normSrc = src.toAbsolutePath().normalize();
                JavaSourceHeaderScanner.ScanResult scan = JavaSourceHeaderScanner.scan(normSrc);
                String pkg = scan.packageName();
                sourceToPkg.put(normSrc, pkg);
                sourceToWildcards.put(normSrc, scan.wildcardImportedPackages());

                Set<String> refs = new LinkedHashSet<>(scan.explicitImportedClasses());
                // Match capitalized tokens against known constant-bearing classes
                for (String token : scan.capitalizedTokens()) {
                    for (String constOwner : knownConstantOwnerClasses) {
                        if (constOwner.endsWith("." + token) || constOwner.equals(token)) {
                            refs.add(constOwner);
                        }
                    }
                }
                sourceToAstRefs.put(normSrc, refs);

                String simpleSourceName = stripExtension(normSrc.getFileName().toString());
                String sourceFileName = normSrc.getFileName().toString();
                String relPkgDir = pkg.isEmpty() ? "" : pkg.replace('.', File.separatorChar);
                Path expectedPkgDir = relPkgDir.isEmpty()
                        ? outputDirectory.toAbsolutePath().normalize()
                        : outputDirectory.toAbsolutePath().normalize().resolve(relPkgDir);

                Set<Path> matchedClasses = new LinkedHashSet<>();
                for (Path clsFile : touchedClasses) {
                    if (clsFile.getParent() != null && clsFile.getParent().equals(expectedPkgDir)) {
                        String clsFileName = clsFile.getFileName().toString();
                        if (clsFileName.equals(simpleSourceName + ".class")
                                || clsFileName.startsWith(simpleSourceName + "$")
                                || sourcesToCompile.size() == 1
                                || matchesBytecodeSourceFileAttribute(clsFile, sourceFileName)) {
                            matchedClasses.add(clsFile);
                        }
                    }
                }
                sourceToClassFiles.put(normSrc, matchedClasses);
                sourceToAptSources.put(normSrc, sourcesToCompile.size() == 1
                        ? new LinkedHashSet<>(touchedAptSources)
                        : Collections.emptySet());
            }

            return new RoundCompilationResult(
                    success,
                    compilerResult.getCompilerMessages() != null
                            ? compilerResult.getCompilerMessages()
                            : Collections.emptyList(),
                    sourceToClassFiles,
                    sourceToAptSources,
                    sourceToAstRefs,
                    sourceToPkg,
                    sourceToWildcards,
                    touchedClasses);
        } finally {
            baseConfig.setSourceFiles(originalSources);
            baseConfig.setClasspathEntries(originalClasspath);
        }
    }

    private static String formatSelfClasspathEntry(Path outputDirectory, List<String> existingEntries) {
        String base = outputDirectory.toAbsolutePath().normalize().toString();
        if (ClasspathEntryNormalizer.hasOsgiAccessRules(existingEntries)) {
            return base + "[+**/*]";
        }
        return base;
    }

    private static boolean containsOutputDirectory(List<String> entries, Path outputDirectory) {
        Path normOut = outputDirectory.toAbsolutePath().normalize();
        for (String entry : entries) {
            Path extracted = ClasspathEntryNormalizer.extractFilesystemPath(entry);
            if (normOut.equals(extracted)) {
                return true;
            }
        }
        return false;
    }

    private static Map<Path, Long> snapshotFiles(Path rootDir, String suffix) {
        if (!Files.isDirectory(rootDir)) {
            return Collections.emptyMap();
        }
        Map<Path, Long> snapshot = new LinkedHashMap<>();
        try {
            Files.walkFileTree(rootDir.toAbsolutePath().normalize(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(suffix)) {
                        snapshot.put(file, (attrs.lastModifiedTime().toMillis() << 20) ^ attrs.size());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Ignore snapshot error
        }
        return snapshot;
    }

    private static Set<Path> findModifiedFiles(Path rootDir, Map<Path, Long> beforeSnapshot, String suffix) {
        Map<Path, Long> after = snapshotFiles(rootDir, suffix);
        Set<Path> modified = new LinkedHashSet<>();
        for (Map.Entry<Path, Long> entry : after.entrySet()) {
            Long prev = beforeSnapshot.get(entry.getKey());
            if (prev == null || !prev.equals(entry.getValue())) {
                modified.add(entry.getKey());
            }
        }
        return modified;
    }

    private static Set<Path> findModifiedOrExpectedClasses(
            Path outputDirectory,
            Map<Path, Long> beforeSnapshot,
            Set<Path> compiledSources) {
        Set<Path> modified = findModifiedFiles(outputDirectory, beforeSnapshot, ".class");
        Path normOut = outputDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normOut)) {
            return modified;
        }
        // Also include any existing .class file matching a compiled source's primary class name
        // (in case filesystem timestamp granularity matched the pre-snapshot millisecond)
        for (Path src : compiledSources) {
            JavaSourceHeaderScanner.ScanResult scan = JavaSourceHeaderScanner.scan(src);
            String pkg = scan.packageName();
            String simpleName = stripExtension(src.getFileName().toString());
            Path pkgDir = pkg.isEmpty() ? normOut : normOut.resolve(pkg.replace('.', File.separatorChar));
            if (Files.isDirectory(pkgDir)) {
                try (var stream = Files.list(pkgDir)) {
                    stream.filter(p -> {
                        String fn = p.getFileName().toString();
                        return fn.equals(simpleName + ".class") || fn.startsWith(simpleName + "$") && fn.endsWith(".class");
                    }).forEach(p -> modified.add(p.toAbsolutePath().normalize()));
                } catch (IOException ignored) {
                }
            }
        }
        return modified;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static boolean matchesBytecodeSourceFileAttribute(Path classFile, String expectedSourceFileName) {
        try {
            byte[] bytes = Files.readAllBytes(classFile);
            org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);
            final String[] foundSource = { null };
            cr.accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public void visitSource(String source, String debug) {
                    foundSource[0] = source;
                }
            }, org.objectweb.asm.ClassReader.SKIP_CODE | org.objectweb.asm.ClassReader.SKIP_FRAMES);
            return expectedSourceFileName.equals(foundSource[0]);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
