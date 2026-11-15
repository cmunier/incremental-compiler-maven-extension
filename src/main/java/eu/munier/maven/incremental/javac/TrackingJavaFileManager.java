package eu.munier.maven.incremental.javac;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

/**
 * {@link ForwardingJavaFileManager} that records exact provenance mappings between:
 * <ul>
 *   <li>Input {@code .java} source files and generated {@code .class} files (including inner/anonymous classes)</li>
 *   <li>Originating {@code .java} source files and JSR-269 Annotation Processor generated {@code .java} files</li>
 *   <li>Generated {@code .java} files and their compiled {@code .class} outputs</li>
 * </ul>
 */
public final class TrackingJavaFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    private final Map<Path, Set<Path>> sourceToClassFiles = new LinkedHashMap<>();
    private final Map<Path, Set<Path>> sourceToAptGeneratedSources = new LinkedHashMap<>();
    private final Map<Path, Path> aptSourceToOriginatingSource = new LinkedHashMap<>();
    private final Set<Path> allGeneratedClassFiles = new LinkedHashSet<>();
    private final Set<Path> allAptGeneratedSourceFiles = new LinkedHashSet<>();

    public TrackingJavaFileManager(StandardJavaFileManager fileManager) {
        super(fileManager);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(
            Location location,
            String className,
            JavaFileObject.Kind kind,
            FileObject sibling) throws IOException {
        JavaFileObject out = super.getJavaFileForOutput(location, className, kind, sibling);
        recordOutput(location, kind, out, sibling);
        return out;
    }

    @Override
    public FileObject getFileForOutput(
            Location location,
            String packageName,
            String relativeName,
            FileObject sibling) throws IOException {
        FileObject out = super.getFileForOutput(location, packageName, relativeName, sibling);
        if (relativeName != null && relativeName.endsWith(".java")) {
            recordOutput(location, JavaFileObject.Kind.SOURCE, out, sibling);
        } else if (relativeName != null && relativeName.endsWith(".class")) {
            recordOutput(location, JavaFileObject.Kind.CLASS, out, sibling);
        }
        return out;
    }

    private void recordOutput(
            Location location,
            JavaFileObject.Kind kind,
            FileObject outputFile,
            FileObject sibling) {
        Path outputPath = toPath(outputFile);
        if (outputPath == null) {
            return;
        }
        Path siblingPath = toPath(sibling);
        Path rootSource = resolveRootSource(siblingPath);

        if (kind == JavaFileObject.Kind.CLASS || StandardLocation.CLASS_OUTPUT.equals(location)) {
            allGeneratedClassFiles.add(outputPath);
            if (rootSource != null) {
                sourceToClassFiles
                        .computeIfAbsent(rootSource, k -> new LinkedHashSet<>())
                        .add(outputPath);
            }
            if (siblingPath != null && !siblingPath.equals(rootSource)) {
                sourceToClassFiles
                        .computeIfAbsent(siblingPath, k -> new LinkedHashSet<>())
                        .add(outputPath);
            }
        } else if (kind == JavaFileObject.Kind.SOURCE || StandardLocation.SOURCE_OUTPUT.equals(location)) {
            allAptGeneratedSourceFiles.add(outputPath);
            if (rootSource != null) {
                sourceToAptGeneratedSources
                        .computeIfAbsent(rootSource, k -> new LinkedHashSet<>())
                        .add(outputPath);
                aptSourceToOriginatingSource.put(outputPath, rootSource);
            }
        }
    }

    private Path resolveRootSource(Path siblingPath) {
        if (siblingPath == null) {
            return null;
        }
        Path current = siblingPath;
        int guard = 0;
        while (aptSourceToOriginatingSource.containsKey(current) && guard++ < 10) {
            current = aptSourceToOriginatingSource.get(current);
        }
        return current;
    }

    public Set<Path> getClassFilesForSource(Path sourceFile) {
        return sourceToClassFiles.getOrDefault(
                sourceFile.toAbsolutePath().normalize(), Collections.emptySet());
    }

    public Set<Path> getAptGeneratedSourcesForSource(Path sourceFile) {
        return sourceToAptGeneratedSources.getOrDefault(
                sourceFile.toAbsolutePath().normalize(), Collections.emptySet());
    }

    public Map<Path, Path> getAptSourceToOriginatingSource() {
        return Collections.unmodifiableMap(aptSourceToOriginatingSource);
    }

    public Set<Path> getAllGeneratedClassFiles() {
        return Collections.unmodifiableSet(allGeneratedClassFiles);
    }

    public Set<Path> getAllAptGeneratedSourceFiles() {
        return Collections.unmodifiableSet(allAptGeneratedSourceFiles);
    }

    private static Path toPath(FileObject fileObject) {
        if (fileObject == null) {
            return null;
        }
        try {
            URI uri = fileObject.toUri();
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return Path.of(uri).toAbsolutePath().normalize();
            }
        } catch (RuntimeException ignored) {
            // Non-file URI
        }
        return null;
    }
}
