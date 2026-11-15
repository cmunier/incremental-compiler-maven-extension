package eu.munier.maven.incremental.engine;

import eu.munier.maven.incremental.bytecode.ClassFileAnalyzer;
import eu.munier.maven.incremental.bytecode.ClassFileSummary;
import eu.munier.maven.incremental.state.ClasspathEntryNormalizer;
import eu.munier.maven.incremental.state.DependencyGraph;
import eu.munier.maven.incremental.state.IncrementalBuildState;
import eu.munier.maven.incremental.state.ReactorAbiCache;
import eu.munier.maven.incremental.state.SourceFileRecord;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerException;
import org.codehaus.plexus.compiler.CompilerMessage;
import org.codehaus.plexus.compiler.CompilerResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Core class-level incremental compilation engine with two-tier ABI/Constant fingerprinting,
 * iterative dependent recompilation, JSR-269 APT output tracking, and multi-module reactor ABI caching.
 */
public final class IncrementalCompilationEngine {

    private static final Logger LOG = LoggerFactory.getLogger(IncrementalCompilationEngine.class);
    private static final int MAX_INCREMENTAL_ROUNDS = 16;

    private final ReactorAbiCache reactorAbiCache;
    private final ClassFileAnalyzer classFileAnalyzer = new ClassFileAnalyzer();

    public record CompilationExecutionReport(
            CompilerResult compilerResult,
            boolean fullRebuild,
            int roundsExecuted,
            int totalSourcesCount,
            Set<Path> compiledSources,
            Set<Path> deletedClassFiles) {

        public int compiledSourcesCount() {
            return compiledSources != null ? compiledSources.size() : 0;
        }

        public int skippedSourcesCount() {
            return Math.max(0, totalSourcesCount - compiledSourcesCount());
        }

        public double compiledPercentage() {
            return totalSourcesCount == 0 ? 0.0 : (compiledSourcesCount() * 100.0) / totalSourcesCount;
        }

        public double skippedPercentage() {
            return totalSourcesCount == 0 ? 100.0 : (skippedSourcesCount() * 100.0) / totalSourcesCount;
        }
    }

    public IncrementalCompilationEngine(ReactorAbiCache reactorAbiCache) {
        this.reactorAbiCache = reactorAbiCache != null ? reactorAbiCache : new ReactorAbiCache();
    }

    public CompilationExecutionReport compile(
            CompilerConfiguration config,
            BackendCompiler backendCompiler) throws CompilerException {
        return compile(config, backendCompiler, null);
    }

    public CompilationExecutionReport compile(
            CompilerConfiguration config,
            BackendCompiler backendCompiler,
            Path customStateFile) throws CompilerException {
        String outputLocation = config.getOutputLocation();
        if (outputLocation == null || outputLocation.isBlank()) {
            throw new CompilerException("CompilerConfiguration outputLocation must not be null or empty.");
        }
        Path outputDirectory = Path.of(outputLocation).toAbsolutePath().normalize();
        Path stateFile = customStateFile != null
                ? customStateFile.toAbsolutePath().normalize()
                : resolveDefaultStateFile(outputDirectory);

        Set<Path> currentSources = collectSourceFiles(config);
        String optionsFingerprint = computeOptionsFingerprint(config, outputDirectory);
        List<Path> normalizedClasspath = collectNormalizedClasspath(config, outputDirectory);

        IncrementalBuildState state = IncrementalBuildState.load(stateFile);
        String fullRebuildReason = determineFullRebuildReason(
                state, optionsFingerprint, currentSources, outputDirectory);

        if (fullRebuildReason != null) {
            return executeFullRebuild(
                    config,
                    backendCompiler,
                    outputDirectory,
                    stateFile,
                    currentSources,
                    optionsFingerprint,
                    normalizedClasspath,
                    fullRebuildReason);
        }

        return executeIncrementalBuild(
                config,
                backendCompiler,
                outputDirectory,
                stateFile,
                state,
                currentSources,
                normalizedClasspath);
    }

    private static String determineFullRebuildReason(
            IncrementalBuildState state,
            String optionsFingerprint,
            Set<Path> currentSources,
            Path outputDirectory) {
        if (state == null) {
            return "initial build (no previous incremental state)";
        }
        if (!Objects.equals(state.getOptionsFingerprint(), optionsFingerprint)) {
            return "compiler options or module descriptor changed";
        }
        if (!currentSources.isEmpty() && !Files.isDirectory(outputDirectory)) {
            return "output directory missing";
        }
        return null;
    }

    private CompilationExecutionReport executeFullRebuild(
            CompilerConfiguration config,
            BackendCompiler backendCompiler,
            Path outputDirectory,
            Path stateFile,
            Set<Path> currentSources,
            String optionsFingerprint,
            List<Path> normalizedClasspath,
            String fullRebuildReason) throws CompilerException {
        Set<Path> deletedClasses = cleanDirectoryFiles(outputDirectory, ".class");
        if (config.getGeneratedSourcesDirectory() != null) {
            cleanDirectoryFiles(config.getGeneratedSourcesDirectory().toPath(), ".java");
        }

        int totalCount = currentSources.size();
        IncrementalBuildState newState = new IncrementalBuildState(optionsFingerprint);
        if (currentSources.isEmpty()) {
            saveStateQuietly(newState, stateFile);
            reactorAbiCache.updateModuleSummaries(outputDirectory, Collections.emptyMap());
            LOG.info("[IncrementalCompiler] Full build ({}): 0 source files to compile in {}",
                    fullRebuildReason, outputDirectory);
            return new CompilationExecutionReport(
                    new CompilerResult(true, Collections.emptyList()),
                    true,
                    0,
                    0,
                    Collections.emptySet(),
                    deletedClasses);
        }

        LOG.info("[IncrementalCompiler] Full build ({}): compiling {} of {} source file(s) (100.0%), kept 0 unchanged (0.0%) in {}",
                fullRebuildReason, totalCount, totalCount, outputDirectory);

        BackendCompiler.RoundCompilationResult roundResult =
                backendCompiler.compileSubset(config, currentSources, outputDirectory);

        if (!roundResult.success()) {
            for (Path src : currentSources) {
                newState.getPendingDirtySources().add(src.toString());
            }
            saveStateQuietly(newState, stateFile);
            return new CompilationExecutionReport(
                    new CompilerResult(false, roundResult.messages()),
                    true,
                    1,
                    totalCount,
                    currentSources,
                    deletedClasses);
        }

        updateStateFromRound(newState, currentSources, roundResult, null, null);
        refreshExternalClasspathState(newState, normalizedClasspath);
        newState.getPendingDirtySources().clear();
        saveStateQuietly(newState, stateFile);
        reactorAbiCache.updateModuleSummaries(outputDirectory, newState.getLocalClassSummaries());

        return new CompilationExecutionReport(
                new CompilerResult(true, roundResult.messages()),
                true,
                1,
                totalCount,
                currentSources,
                deletedClasses);
    }

    private CompilationExecutionReport executeIncrementalBuild(
            CompilerConfiguration config,
            BackendCompiler backendCompiler,
            Path outputDirectory,
            Path stateFile,
            IncrementalBuildState state,
            Set<Path> currentSources,
            List<Path> normalizedClasspath) throws CompilerException {

        DependencyGraph dependencyGraph = DependencyGraph.fromState(state);
        Set<Path> initialDirtySources = new LinkedHashSet<>();
        Set<Path> deletedClassFiles = new LinkedHashSet<>();
        Set<String> deletedClassNames = new LinkedHashSet<>();
        Set<String> namespaceChangedPackages = new LinkedHashSet<>();
        Set<String> modifiedPackageInfoPackages = new LinkedHashSet<>();
        boolean stateMetadataModified = false;

        Map<String, Path> currentSourceMap = new LinkedHashMap<>(currentSources.size());
        for (Path src : currentSources) {
            currentSourceMap.put(src.toString(), src);
        }

        // 1. Detect deleted .java files
        var recordIterator = state.getSourceRecords().entrySet().iterator();
        while (recordIterator.hasNext()) {
            Map.Entry<String, SourceFileRecord> entry = recordIterator.next();
            String srcKey = entry.getKey();
            SourceFileRecord record = entry.getValue();
            if (!currentSourceMap.containsKey(srcKey)) {
                stateMetadataModified = true;
                deletedClassNames.addAll(record.getDefinedClassNames());
                if (record.getPackageName() != null && !record.getPackageName().isEmpty()) {
                    namespaceChangedPackages.add(record.getPackageName());
                    if (srcKey.endsWith("package-info.java")) {
                        modifiedPackageInfoPackages.add(record.getPackageName());
                    }
                }
                for (String definedCls : record.getDefinedClassNames()) {
                    state.getLocalClassSummaries().remove(definedCls);
                }
                deletedClassFiles.addAll(deleteRecordedFiles(record.getGeneratedClassFilePaths()));
                deleteRecordedFiles(record.getAptGeneratedSourcePaths());
                recordIterator.remove();
            }
        }

        // 2. Detect added or modified .java files (and missing .class outputs)
        for (Map.Entry<String, Path> entry : currentSourceMap.entrySet()) {
            String srcKey = entry.getKey();
            Path srcPath = entry.getValue();
            SourceFileRecord prevRecord = state.getSourceRecords().get(srcKey);
            if (prevRecord == null) {
                initialDirtySources.add(srcPath);
                continue;
            }

            if (!allRecordedFilesExist(prevRecord.getGeneratedClassFilePaths())) {
                initialDirtySources.add(srcPath);
                continue;
            }

            try {
                BasicFileAttributes attrs = Files.readAttributes(srcPath, BasicFileAttributes.class);
                long lastMod = attrs.lastModifiedTime().toMillis();
                long size = attrs.size();
                if (lastMod != prevRecord.getLastModifiedMillis() || size != prevRecord.getFileSize()) {
                    byte[] currentSha256 = sha256File(srcPath);
                    if (prevRecord.hasSameContentHash(currentSha256)) {
                        // Content identical (e.g. touch / git branch switch) -> update timestamp only
                        state.getSourceRecords().put(srcKey, prevRecord.withUpdatedTimestamp(lastMod));
                        stateMetadataModified = true;
                    } else {
                        initialDirtySources.add(srcPath);
                        if (srcPath.getFileName().toString().equals("package-info.java")
                                && prevRecord.getPackageName() != null
                                && !prevRecord.getPackageName().isEmpty()) {
                            modifiedPackageInfoPackages.add(prevRecord.getPackageName());
                        }
                    }
                }
            } catch (IOException e) {
                initialDirtySources.add(srcPath);
            }
        }

        // 3. Propagate dependents of deleted local classes, namespace changes, and package-info modifications
        if (!deletedClassNames.isEmpty()) {
            Set<Path> affectedByDeletion = dependencyGraph.findAffectedSources(deletedClassNames, deletedClassNames);
            for (Path candidate : affectedByDeletion) {
                Path norm = candidate.toAbsolutePath().normalize();
                if (currentSourceMap.containsKey(norm.toString())) {
                    initialDirtySources.add(norm);
                }
            }
        }
        if (!namespaceChangedPackages.isEmpty()) {
            for (Path candidate : dependencyGraph.findSourcesAffectedByNamespaceChanges(namespaceChangedPackages)) {
                Path norm = candidate.toAbsolutePath().normalize();
                if (currentSourceMap.containsKey(norm.toString())) {
                    initialDirtySources.add(norm);
                }
            }
        }
        if (!modifiedPackageInfoPackages.isEmpty()) {
            for (Path candidate : dependencyGraph.findSourcesInPackages(modifiedPackageInfoPackages)) {
                Path norm = candidate.toAbsolutePath().normalize();
                if (currentSourceMap.containsKey(norm.toString())) {
                    initialDirtySources.add(norm);
                }
            }
        }

        // 4. Detect ABI / Constant changes in upstream reactor modules & external JARs
        Set<String> externalReferenced = state.getAllExternalReferencedClasses();
        if (!externalReferenced.isEmpty()) {
            Map<String, Long> newJarFingerprints = new LinkedHashMap<>();
            Map<String, byte[]> currentExternalHashes = reactorAbiCache.resolveExternalClassHashes(
                    normalizedClasspath,
                    externalReferenced,
                    state.getClasspathJarFingerprints(),
                    state.getExternalClassCombinedHashes(),
                    newJarFingerprints);

            Set<String> changedExternalClasses = new LinkedHashSet<>();
            for (String extClass : externalReferenced) {
                byte[] oldHash = state.getExternalClassCombinedHashes().get(extClass);
                byte[] newHash = currentExternalHashes.get(extClass);
                if (!Arrays.equals(oldHash, newHash)) {
                    changedExternalClasses.add(extClass);
                }
            }

            if (!changedExternalClasses.isEmpty()) {
                Set<Path> affectedByExternal =
                        dependencyGraph.findDirectDependentsOfExternalClasses(changedExternalClasses);
                for (Path candidate : affectedByExternal) {
                    Path norm = candidate.toAbsolutePath().normalize();
                    if (currentSourceMap.containsKey(norm.toString())) {
                        initialDirtySources.add(norm);
                    }
                }
            }
        }

        // 5. Include pending dirty sources from any previous failed build
        for (String pending : state.getPendingDirtySources()) {
            Path norm = Path.of(pending).toAbsolutePath().normalize();
            if (currentSourceMap.containsKey(norm.toString())) {
                initialDirtySources.add(norm);
            }
        }

        int totalCount = currentSources.size();

        // 6. Fast exit if nothing needs recompilation
        if (initialDirtySources.isEmpty()) {
            if (stateMetadataModified) {
                refreshExternalClasspathState(state, normalizedClasspath);
                saveStateQuietly(state, stateFile);
                reactorAbiCache.updateModuleSummaries(outputDirectory, state.getLocalClassSummaries());
            } else if (reactorAbiCache.getModuleSummaries(outputDirectory).isEmpty()) {
                reactorAbiCache.updateModuleSummaries(outputDirectory, state.getLocalClassSummaries());
            }
            LOG.info("[IncrementalCompiler] Up-to-date: compiled 0 of {} source file(s) (0.0%), kept {} unchanged (100.0%) in {}",
                    totalCount, totalCount, outputDirectory);
            return new CompilationExecutionReport(
                    new CompilerResult(true, Collections.emptyList()),
                    false,
                    0,
                    totalCount,
                    Collections.emptySet(),
                    deletedClassFiles);
        }

        // 7. Iterative compilation rounds (Round 1: direct dirty set; Round 2+: ABI-affected dependents)
        Set<Path> roundSources = new LinkedHashSet<>(initialDirtySources);
        Set<Path> allCompiledSources = new LinkedHashSet<>();
        List<CompilerMessage> allMessages = new ArrayList<>();
        int round = 0;

        while (!roundSources.isEmpty() && round < MAX_INCREMENTAL_ROUNDS) {
            round++;
            String roundReason = (round == 1) ? "direct/upstream changes" : "ABI-affected dependents";
            LOG.info("[IncrementalCompiler] Round {}: compiling {} of {} source file(s) ({}) in {}",
                    round, roundSources.size(), totalCount, roundReason, outputDirectory);

            // Persist current dirty set before compiling so build failures leave a recoverable state
            state.getPendingDirtySources().clear();
            for (Path dirty : roundSources) {
                state.getPendingDirtySources().add(dirty.toString());
            }
            saveStateQuietly(state, stateFile);

            // Clean old .class and APT .java outputs of the files about to be recompiled
            Map<Path, Set<String>> previousDefinedClassesPerSource = new LinkedHashMap<>();
            for (Path src : roundSources) {
                SourceFileRecord oldRecord = state.getSourceRecords().get(src.toString());
                if (oldRecord != null) {
                    previousDefinedClassesPerSource.put(
                            src, new LinkedHashSet<>(oldRecord.getDefinedClassNames()));
                    deletedClassFiles.addAll(deleteRecordedFiles(oldRecord.getGeneratedClassFilePaths()));
                    deleteRecordedFiles(oldRecord.getAptGeneratedSourcePaths());
                }
            }

            BackendCompiler.RoundCompilationResult roundResult =
                    backendCompiler.compileSubset(config, roundSources, outputDirectory);
            allMessages.addAll(roundResult.messages());
            allCompiledSources.addAll(roundSources);

            if (!roundResult.success()) {
                saveStateQuietly(state, stateFile);
                LOG.warn("[IncrementalCompiler] Compilation failed in round {} after compiling {} of {} source file(s) in {}",
                        round, allCompiledSources.size(), totalCount, outputDirectory);
                return new CompilationExecutionReport(
                        new CompilerResult(false, allMessages),
                        false,
                        round,
                        totalCount,
                        allCompiledSources,
                        deletedClassFiles);
            }

            Set<String> abiChangedClasses = new LinkedHashSet<>();
            Set<String> hierarchyChangedClasses = new LinkedHashSet<>();
            Set<String> roundNamespacePackages = new LinkedHashSet<>();

            updateStateFromRound(
                    state,
                    roundSources,
                    roundResult,
                    previousDefinedClassesPerSource,
                    new AbiDiffSink(abiChangedClasses, hierarchyChangedClasses, roundNamespacePackages));

            dependencyGraph.rebuild(state);

            if (abiChangedClasses.isEmpty()
                    && hierarchyChangedClasses.isEmpty()
                    && roundNamespacePackages.isEmpty()) {
                roundSources = Collections.emptySet();
            } else {
                Set<Path> newlyAffected = new LinkedHashSet<>(
                        dependencyGraph.findAffectedSources(abiChangedClasses, hierarchyChangedClasses));
                newlyAffected.addAll(
                        dependencyGraph.findSourcesAffectedByNamespaceChanges(roundNamespacePackages));
                Set<Path> nextRound = new LinkedHashSet<>();
                for (Path candidate : newlyAffected) {
                    Path norm = candidate.toAbsolutePath().normalize();
                    if (currentSourceMap.containsKey(norm.toString()) && !allCompiledSources.contains(norm)) {
                        nextRound.add(norm);
                    }
                }
                roundSources = nextRound;
            }
        }

        state.getPendingDirtySources().clear();
        refreshExternalClasspathState(state, normalizedClasspath);
        saveStateQuietly(state, stateFile);
        reactorAbiCache.updateModuleSummaries(outputDirectory, state.getLocalClassSummaries());

        int compiledCount = allCompiledSources.size();
        int skippedCount = Math.max(0, totalCount - compiledCount);
        LOG.info("[IncrementalCompiler] Summary: compiled {} of {} source file(s) ({}%) in {} round(s), kept {} unchanged ({}%), deleted {} stale class file(s) in {}",
                compiledCount,
                totalCount,
                formatPercent(compiledCount, totalCount),
                round,
                skippedCount,
                formatPercent(skippedCount, totalCount),
                deletedClassFiles.size(),
                outputDirectory);

        return new CompilationExecutionReport(
                new CompilerResult(true, allMessages),
                false,
                round,
                totalCount,
                allCompiledSources,
                deletedClassFiles);
    }

    private static String formatPercent(int part, int total) {
        if (total <= 0) {
            return "0.0";
        }
        return String.format(Locale.ROOT, "%.1f", (part * 100.0) / total);
    }

    private record AbiDiffSink(
            Set<String> abiChangedClasses,
            Set<String> hierarchyChangedClasses,
            Set<String> namespaceChangedPackages) {
    }

    private void updateStateFromRound(
            IncrementalBuildState state,
            Set<Path> compiledSources,
            BackendCompiler.RoundCompilationResult roundResult,
            Map<Path, Set<String>> previousDefinedClassesPerSource,
            AbiDiffSink diffSink) throws CompilerException {
        for (Path src : compiledSources) {
            Path normSrc = src.toAbsolutePath().normalize();
            String srcKey = normSrc.toString();

            Set<Path> generatedClassPaths = roundResult.sourceToClassFiles()
                    .getOrDefault(normSrc, Collections.emptySet());
            Set<Path> aptGeneratedSourcePaths = roundResult.sourceToAptGeneratedSources()
                    .getOrDefault(normSrc, Collections.emptySet());
            Set<String> astRefs = roundResult.sourceToAstReferencedClasses()
                    .getOrDefault(normSrc, Collections.emptySet());
            String pkgName = roundResult.sourceToPackageName().getOrDefault(normSrc, "");
            Set<String> wildcards = roundResult.sourceToWildcardImports()
                    .getOrDefault(normSrc, Collections.emptySet());

            Set<String> newDefinedClassNames = new LinkedHashSet<>();
            Set<String> classFilePathStrings = new LinkedHashSet<>();

            for (Path classFile : generatedClassPaths) {
                if (!Files.isRegularFile(classFile)) {
                    continue;
                }
                Path normClassFile = classFile.toAbsolutePath().normalize();
                classFilePathStrings.add(normClassFile.toString());
                try {
                    ClassFileSummary newSummary = classFileAnalyzer.analyze(normClassFile);
                    String binaryName = newSummary.getBinaryClassName();
                    newDefinedClassNames.add(binaryName);

                    ClassFileSummary oldSummary = state.getLocalClassSummaries().put(binaryName, newSummary);
                    if (diffSink != null) {
                        if (oldSummary == null) {
                            diffSink.abiChangedClasses().add(binaryName);
                            diffSink.hierarchyChangedClasses().add(binaryName);
                            if (pkgName != null && !pkgName.isEmpty() && binaryName.indexOf('$') < 0) {
                                diffSink.namespaceChangedPackages().add(pkgName);
                            }
                        } else {
                            if (!newSummary.hasSameAbi(oldSummary) || !newSummary.hasSameConstants(oldSummary)) {
                                diffSink.abiChangedClasses().add(binaryName);
                            }
                            if (!newSummary.hasSameHierarchy(oldSummary)) {
                                diffSink.hierarchyChangedClasses().add(binaryName);
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new CompilerException("Failed to analyze compiled class file: " + normClassFile, e);
                }
            }

            if (previousDefinedClassesPerSource != null) {
                Set<String> prevDefined = previousDefinedClassesPerSource.getOrDefault(
                        normSrc, Collections.emptySet());
                for (String oldCls : prevDefined) {
                    if (!newDefinedClassNames.contains(oldCls)) {
                        state.getLocalClassSummaries().remove(oldCls);
                        if (diffSink != null) {
                            diffSink.abiChangedClasses().add(oldCls);
                            diffSink.hierarchyChangedClasses().add(oldCls);
                        }
                    }
                }
            }

            Set<String> aptPathStrings = new LinkedHashSet<>();
            for (Path aptPath : aptGeneratedSourcePaths) {
                aptPathStrings.add(aptPath.toAbsolutePath().normalize().toString());
            }

            try {
                BasicFileAttributes attrs = Files.readAttributes(normSrc, BasicFileAttributes.class);
                byte[] sha256 = sha256File(normSrc);
                SourceFileRecord newRecord = new SourceFileRecord(
                        srcKey,
                        attrs.lastModifiedTime().toMillis(),
                        attrs.size(),
                        sha256,
                        pkgName,
                        wildcards,
                        newDefinedClassNames,
                        classFilePathStrings,
                        aptPathStrings,
                        astRefs);
                state.getSourceRecords().put(srcKey, newRecord);
            } catch (IOException e) {
                throw new CompilerException("Failed to read source file attributes: " + normSrc, e);
            }
        }
    }

    private void refreshExternalClasspathState(
            IncrementalBuildState state,
            List<Path> normalizedClasspath) {
        Set<String> externalReferenced = state.getAllExternalReferencedClasses();
        Map<String, Long> updatedJarFingerprints = new LinkedHashMap<>();
        Map<String, byte[]> resolvedHashes = reactorAbiCache.resolveExternalClassHashes(
                normalizedClasspath,
                externalReferenced,
                state.getClasspathJarFingerprints(),
                state.getExternalClassCombinedHashes(),
                updatedJarFingerprints);
        state.getExternalClassCombinedHashes().clear();
        state.getExternalClassCombinedHashes().putAll(resolvedHashes);
        state.getClasspathJarFingerprints().clear();
        state.getClasspathJarFingerprints().putAll(updatedJarFingerprints);
    }

    private static Set<Path> collectSourceFiles(CompilerConfiguration config) {
        Set<Path> sources = new LinkedHashSet<>();
        if (config.getSourceFiles() != null && !config.getSourceFiles().isEmpty()) {
            for (File f : config.getSourceFiles()) {
                if (f != null && f.isFile()) {
                    sources.add(f.toPath().toAbsolutePath().normalize());
                }
            }
            return sources;
        }
        if (config.getSourceLocations() != null) {
            for (String loc : config.getSourceLocations()) {
                if (loc == null || loc.isBlank()) {
                    continue;
                }
                Path root = Path.of(loc).toAbsolutePath().normalize();
                if (Files.isDirectory(root)) {
                    try {
                        Files.walkFileTree(root, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                if (file.getFileName().toString().endsWith(".java")) {
                                    sources.add(file.toAbsolutePath().normalize());
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return sources;
    }

    private static List<Path> collectNormalizedClasspath(CompilerConfiguration config, Path outputDirectory) {
        List<Path> paths = new ArrayList<>();
        if (config.getClasspathEntries() != null) {
            for (String raw : config.getClasspathEntries()) {
                Path p = ClasspathEntryNormalizer.extractFilesystemPath(raw);
                if (p != null && !p.equals(outputDirectory)) {
                    paths.add(p);
                }
            }
        }
        if (config.getModulepathEntries() != null) {
            for (String raw : config.getModulepathEntries()) {
                Path p = ClasspathEntryNormalizer.extractFilesystemPath(raw);
                if (p != null && !p.equals(outputDirectory)) {
                    paths.add(p);
                }
            }
        }
        return paths;
    }

    public static String computeOptionsFingerprint(CompilerConfiguration config, Path outputDirectory) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            updateStr(md, "release=" + Objects.toString(config.getReleaseVersion(), ""));
            updateStr(md, "source=" + Objects.toString(config.getSourceVersion(), ""));
            updateStr(md, "target=" + Objects.toString(config.getTargetVersion(), ""));
            updateStr(md, "encoding=" + Objects.toString(config.getSourceEncoding(), ""));
            updateStr(md, "parameters=" + config.isParameters());
            updateStr(md, "debug=" + config.isDebug() + ":" + Objects.toString(config.getDebugLevel(), ""));
            updateStr(md, "proc=" + Objects.toString(config.getProc(), ""));
            if (config.getAnnotationProcessors() != null) {
                updateStr(md, "processors=" + Arrays.toString(config.getAnnotationProcessors()));
            }
            if (config.getProcessorPathEntries() != null) {
                updateStr(md, "processorPath=" + config.getProcessorPathEntries());
            }
            if (config.getCustomCompilerArgumentsEntries() != null) {
                for (Map.Entry<String, String> arg : config.getCustomCompilerArgumentsEntries()) {
                    updateStr(md, "arg=" + arg.getKey() + ":" + Objects.toString(arg.getValue(), ""));
                }
            }

            // Include OSGi / Tycho project descriptor files if present in module root
            Path moduleRoot = inferModuleRoot(outputDirectory);
            if (moduleRoot != null) {
                hashOptionalFile(md, moduleRoot.resolve("META-INF/MANIFEST.MF"));
                hashOptionalFile(md, moduleRoot.resolve("build.properties"));
                hashOptionalFile(md, moduleRoot.resolve(".settings/org.eclipse.jdt.core.prefs"));
            }

            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Path inferModuleRoot(Path outputDirectory) {
        Path parent = outputDirectory.getParent();
        if (parent != null && "target".equals(parent.getFileName().toString())) {
            return parent.getParent();
        }
        return parent;
    }

    private static void hashOptionalFile(MessageDigest md, Path file) {
        if (Files.isRegularFile(file)) {
            try {
                updateStr(md, "file:" + file.getFileName());
                md.update(Files.readAllBytes(file));
            } catch (IOException ignored) {
            }
        }
    }

    private static void updateStr(MessageDigest md, String s) {
        md.update(s.getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\n');
    }

    public static Path resolveDefaultStateFile(Path outputDirectory) {
        Path normOut = outputDirectory.toAbsolutePath().normalize();
        String dirName = normOut.getFileName() != null ? normOut.getFileName().toString() : "default";
        Path parent = normOut.getParent();
        if (parent != null) {
            return parent.resolve("incremental-compiler").resolve(dirName).resolve("state.bin");
        }
        return normOut.resolve(".incremental-state.bin");
    }

    private static boolean allRecordedFilesExist(Set<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return true;
        }
        for (String pathStr : filePaths) {
            if (!Files.isRegularFile(Path.of(pathStr))) {
                return false;
            }
        }
        return true;
    }

    private static Set<Path> deleteRecordedFiles(Set<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return Collections.emptySet();
        }
        Set<Path> deleted = new LinkedHashSet<>();
        for (String pathStr : filePaths) {
            Path p = Path.of(pathStr).toAbsolutePath().normalize();
            try {
                if (Files.deleteIfExists(p)) {
                    deleted.add(p);
                }
            } catch (IOException ignored) {
            }
        }
        return deleted;
    }

    private static Set<Path> cleanDirectoryFiles(Path dir, String suffix) {
        if (dir == null || !Files.isDirectory(dir)) {
            return Collections.emptySet();
        }
        Set<Path> deleted = new LinkedHashSet<>();
        try {
            Files.walkFileTree(dir.toAbsolutePath().normalize(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(suffix)) {
                        try {
                            if (Files.deleteIfExists(file)) {
                                deleted.add(file);
                            }
                        } catch (IOException ignored) {
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
        return deleted;
    }

    private static byte[] sha256File(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(file));
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void saveStateQuietly(IncrementalBuildState state, Path stateFile) {
        try {
            state.save(stateFile);
        } catch (IOException e) {
            LOG.warn("[IncrementalCompiler] Failed to save state to {}: {}", stateFile, e.getMessage());
        }
    }
}
