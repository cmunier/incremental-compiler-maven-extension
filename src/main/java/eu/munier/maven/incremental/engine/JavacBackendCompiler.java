package eu.munier.maven.incremental.engine;

import com.sun.source.util.JavacTask;
import eu.munier.maven.incremental.javac.AstDependencyCollector;
import eu.munier.maven.incremental.javac.JavaSourceHeaderScanner;
import eu.munier.maven.incremental.javac.TrackingJavaFileManager;
import eu.munier.maven.incremental.state.ClasspathEntryNormalizer;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * In-process JSR-199 {@link JavaCompiler} ({@code JavacTask}) backend that uses
 * {@link TrackingJavaFileManager} and {@link AstDependencyCollector} to record exact
 * class outputs, JSR-269 APT outputs (both isolating and aggregating), and compile-time AST dependencies.
 */
public final class JavacBackendCompiler implements BackendCompiler {

    @Override
    public RoundCompilationResult compileSubset(
            CompileRequest config,
            Set<Path> sourcesToCompile,
            Path outputDirectory) throws IncrementalCompilationException {
        if (sourcesToCompile.isEmpty()) {
            return new RoundCompilationResult(
                    true,
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    Collections.emptySet(),
                    false,
                    Collections.emptySet());
        }

        JavaCompiler systemCompiler = ToolProvider.getSystemJavaCompiler();
        if (systemCompiler == null) {
            throw new IncrementalCompilationException(
                    "No javax.tools.JavaCompiler available in current JVM runtime (JDK required).");
        }

        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException e) {
            throw new IncrementalCompilationException("Failed to create output directory: " + outputDirectory, e);
        }

        Charset encoding = resolveEncoding(config);
        DiagnosticCollector<JavaFileObject> diagnosticCollector = new DiagnosticCollector<>();

        try (StandardJavaFileManager stdFileManager =
                     systemCompiler.getStandardFileManager(diagnosticCollector, Locale.getDefault(), encoding);
             TrackingJavaFileManager trackingFileManager = new TrackingJavaFileManager(stdFileManager)) {

            List<String> options = buildJavacOptions(config, outputDirectory);
            List<File> sourceFiles = sourcesToCompile.stream()
                    .map(Path::toFile)
                    .toList();
            Iterable<? extends JavaFileObject> compilationUnits =
                    stdFileManager.getJavaFileObjectsFromFiles(sourceFiles);

            StringWriter compilerOutput = new StringWriter();
            JavacTask task = (JavacTask) systemCompiler.getTask(
                    compilerOutput,
                    trackingFileManager,
                    diagnosticCollector,
                    options,
                    null,
                    compilationUnits);

            AstDependencyCollector astCollector = new AstDependencyCollector(task);
            task.addTaskListener(astCollector);

            Boolean taskSuccess = task.call();
            boolean success = Boolean.TRUE.equals(taskSuccess);

            List<CompileDiagnostic> messages = toCompileDiagnostics(diagnosticCollector.getDiagnostics());
            if (!success && messages.isEmpty() && !compilerOutput.toString().isBlank()) {
                messages.add(new CompileDiagnostic(compilerOutput.toString().trim(), CompileDiagnostic.Kind.ERROR));
            }

            Map<Path, Set<Path>> sourceToClassFiles = new LinkedHashMap<>();
            Map<Path, Set<Path>> sourceToAptSources = new LinkedHashMap<>();
            Map<Path, Set<String>> sourceToAstRefs = new LinkedHashMap<>();
            Map<Path, String> sourceToPkg = new LinkedHashMap<>();
            Map<Path, Set<String>> sourceToWildcards = new LinkedHashMap<>();

            for (Path src : sourcesToCompile) {
                Path normSrc = src.toAbsolutePath().normalize();
                sourceToClassFiles.put(normSrc, new LinkedHashSet<>(trackingFileManager.getClassFilesForSource(normSrc)));
                Set<Path> aptGenerated = trackingFileManager.getAptGeneratedSourcesForSource(normSrc);
                sourceToAptSources.put(normSrc, new LinkedHashSet<>(aptGenerated));

                Set<String> mergedAstRefs = new LinkedHashSet<>(astCollector.getReferencedClasses(normSrc));
                for (Path aptSrc : aptGenerated) {
                    mergedAstRefs.addAll(astCollector.getReferencedClasses(aptSrc));
                }

                JavaSourceHeaderScanner.ScanResult headerScan = JavaSourceHeaderScanner.scan(normSrc);
                mergedAstRefs.addAll(headerScan.explicitImportedClasses());
                sourceToAstRefs.put(normSrc, mergedAstRefs);

                String pkg = astCollector.getPackageName(normSrc);
                if (pkg == null || pkg.isEmpty()) {
                    pkg = headerScan.packageName();
                }
                sourceToPkg.put(normSrc, pkg);

                Set<String> wildcards = new LinkedHashSet<>(astCollector.getWildcardImports(normSrc));
                wildcards.addAll(headerScan.wildcardImportedPackages());
                sourceToWildcards.put(normSrc, wildcards);
            }

            return new RoundCompilationResult(
                    success,
                    messages,
                    sourceToClassFiles,
                    sourceToAptSources,
                    sourceToAstRefs,
                    sourceToPkg,
                    sourceToWildcards,
                    trackingFileManager.getAllGeneratedClassFiles(),
                    trackingFileManager.isAggregatingAptDetected(),
                    trackingFileManager.getAptGeneratedResourceFiles());
        } catch (IOException e) {
            throw new IncrementalCompilationException("I/O error during incremental javac compilation", e);
        }
    }

    public static List<String> buildJavacOptions(CompileRequest config, Path outputDirectory)
            throws IOException {
        List<String> options = new ArrayList<>();

        options.add("-d");
        options.add(outputDirectory.toAbsolutePath().normalize().toString());

        // Empty sourcepath prevents javac from implicitly parsing untouched .java files on disk;
        // instead, javac resolves unchanged classes from outputDirectory on the classpath.
        options.add("-sourcepath");
        options.add("");

        List<String> cpElements = new ArrayList<>();
        cpElements.add(outputDirectory.toAbsolutePath().normalize().toString());
        if (config.getClasspathEntries() != null) {
            for (String entry : config.getClasspathEntries()) {
                Path cleanPath = ClasspathEntryNormalizer.extractFilesystemPath(entry);
                if (cleanPath != null) {
                    String pathStr = cleanPath.toString();
                    if (!cpElements.contains(pathStr)) {
                        cpElements.add(pathStr);
                    }
                }
            }
        }
        options.add("-classpath");
        options.add(String.join(File.pathSeparator, cpElements));

        if (config.getModulepathEntries() != null && !config.getModulepathEntries().isEmpty()) {
            List<String> mpElements = new ArrayList<>();
            for (String entry : config.getModulepathEntries()) {
                Path cleanPath = ClasspathEntryNormalizer.extractFilesystemPath(entry);
                if (cleanPath != null) {
                    mpElements.add(cleanPath.toString());
                }
            }
            if (!mpElements.isEmpty()) {
                options.add("--module-path");
                options.add(String.join(File.pathSeparator, mpElements));
            }
        }

        if (config.getReleaseVersion() != null && !config.getReleaseVersion().isBlank()) {
            options.add("--release");
            options.add(config.getReleaseVersion().trim());
        } else {
            if (config.getSourceVersion() != null && !config.getSourceVersion().isBlank()) {
                options.add("-source");
                options.add(config.getSourceVersion().trim());
            }
            if (config.getTargetVersion() != null && !config.getTargetVersion().isBlank()) {
                options.add("-target");
                options.add(config.getTargetVersion().trim());
            }
        }

        if (config.getSourceEncoding() != null && !config.getSourceEncoding().isBlank()) {
            options.add("-encoding");
            options.add(config.getSourceEncoding().trim());
        }

        if (config.isParameters()) {
            options.add("-parameters");
        }

        if (config.isDebug()) {
            if (config.getDebugLevel() != null && !config.getDebugLevel().isBlank()) {
                options.add("-g:" + config.getDebugLevel().trim());
            } else {
                options.add("-g");
            }
        } else {
            options.add("-g:none");
        }

        if (config.isShowDeprecation()) {
            options.add("-deprecation");
        }
        if (!config.isShowWarnings()) {
            options.add("-nowarn");
        }

        String proc = config.getProc();
        if (proc != null && !proc.isBlank()) {
            options.add("-proc:" + proc.trim());
        }

        boolean procDisabled = "none".equalsIgnoreCase(proc);
        if (!procDisabled) {
            File genSrcDir = config.getGeneratedSourcesDirectory();
            if (genSrcDir != null) {
                Path genPath = genSrcDir.toPath().toAbsolutePath().normalize();
                Files.createDirectories(genPath);
                options.add("-s");
                options.add(genPath.toString());
            }
            if (config.getProcessorPathEntries() != null && !config.getProcessorPathEntries().isEmpty()) {
                options.add("-processorpath");
                options.add(String.join(File.pathSeparator, config.getProcessorPathEntries()));
            }
            if (config.getAnnotationProcessors() != null && config.getAnnotationProcessors().length > 0) {
                List<String> procs = new ArrayList<>();
                for (String p : config.getAnnotationProcessors()) {
                    if (p != null && !p.isBlank()) {
                        procs.add(p.trim());
                    }
                }
                if (!procs.isEmpty()) {
                    options.add("-processor");
                    options.add(String.join(",", procs));
                }
            }
        }

        if (config.getCustomCompilerArgumentsEntries() != null) {
            var entries = new ArrayList<>(config.getCustomCompilerArgumentsEntries());
            for (Map.Entry<String, String> entry : entries) {
                String key = entry.getKey();
                String val = entry.getValue();
                if (key == null || key.isBlank()) {
                    continue;
                }
                if ("-sourcepath".equals(key) || "--source-path".equals(key)
                        || "-d".equals(key) || "-classpath".equals(key) || "-cp".equals(key)) {
                    continue;
                }
                options.add(key);
                if (val != null && !val.isBlank()) {
                    options.add(val);
                }
            }
        }

        return options;
    }

    private static Charset resolveEncoding(CompileRequest config) {
        if (config.getSourceEncoding() != null && !config.getSourceEncoding().isBlank()) {
            try {
                return Charset.forName(config.getSourceEncoding().trim());
            } catch (RuntimeException ignored) {
                // Fallback to UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static List<CompileDiagnostic> toCompileDiagnostics(
            List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        List<CompileDiagnostic> result = new ArrayList<>(diagnostics.size());
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            String file = null;
            if (d.getSource() != null) {
                try {
                    file = Path.of(d.getSource().toUri()).toString();
                } catch (RuntimeException ignored) {
                    file = d.getSource().getName();
                }
            }
            int line = (int) Math.max(0, d.getLineNumber());
            int col = (int) Math.max(0, d.getColumnNumber());
            CompileDiagnostic.Kind kind = switch (d.getKind()) {
                case ERROR -> CompileDiagnostic.Kind.ERROR;
                case WARNING -> CompileDiagnostic.Kind.WARNING;
                case MANDATORY_WARNING -> CompileDiagnostic.Kind.MANDATORY_WARNING;
                case NOTE -> CompileDiagnostic.Kind.NOTE;
                default -> CompileDiagnostic.Kind.OTHER;
            };
            result.add(new CompileDiagnostic(
                    file,
                    kind,
                    line,
                    col,
                    line,
                    col,
                    d.getMessage(Locale.getDefault())));
        }
        return result;
    }
}
