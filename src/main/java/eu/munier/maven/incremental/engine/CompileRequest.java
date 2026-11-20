package eu.munier.maven.incremental.engine;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiler-agnostic configuration snapshot for a single incremental compilation invocation.
 * Decouples the core engine from {@code plexus-compiler-api} so the extension can run cleanly
 * from {@code .mvn/extensions.xml} and across isolated Maven/Tycho plugin ClassRealms.
 */
public final class CompileRequest {

    private final String outputLocation;
    private final Set<File> sourceFiles;
    private final List<String> sourceLocations;
    private final List<String> classpathEntries;
    private final List<String> modulepathEntries;
    private final String releaseVersion;
    private final String sourceVersion;
    private final String targetVersion;
    private final String sourceEncoding;
    private final boolean parameters;
    private final boolean debug;
    private final String debugLevel;
    private final boolean showDeprecation;
    private final boolean showWarnings;
    private final String proc;
    private final File generatedSourcesDirectory;
    private final List<String> processorPathEntries;
    private final String[] annotationProcessors;
    private final List<Map.Entry<String, String>> customCompilerArgumentsEntries;

    private CompileRequest(Builder builder) {
        this.outputLocation = builder.outputLocation;
        this.sourceFiles = builder.sourceFiles != null
                ? Collections.unmodifiableSet(new LinkedHashSet<>(builder.sourceFiles))
                : Collections.emptySet();
        this.sourceLocations = builder.sourceLocations != null
                ? List.copyOf(builder.sourceLocations)
                : Collections.emptyList();
        this.classpathEntries = builder.classpathEntries != null
                ? List.copyOf(builder.classpathEntries)
                : Collections.emptyList();
        this.modulepathEntries = builder.modulepathEntries != null
                ? List.copyOf(builder.modulepathEntries)
                : Collections.emptyList();
        this.releaseVersion = builder.releaseVersion;
        this.sourceVersion = builder.sourceVersion;
        this.targetVersion = builder.targetVersion;
        this.sourceEncoding = builder.sourceEncoding;
        this.parameters = builder.parameters;
        this.debug = builder.debug;
        this.debugLevel = builder.debugLevel;
        this.showDeprecation = builder.showDeprecation;
        this.showWarnings = builder.showWarnings;
        this.proc = builder.proc;
        this.generatedSourcesDirectory = builder.generatedSourcesDirectory;
        this.processorPathEntries = builder.processorPathEntries != null
                ? List.copyOf(builder.processorPathEntries)
                : Collections.emptyList();
        this.annotationProcessors = builder.annotationProcessors != null
                ? builder.annotationProcessors.clone()
                : null;
        this.customCompilerArgumentsEntries = builder.customCompilerArgumentsEntries != null
                ? List.copyOf(builder.customCompilerArgumentsEntries)
                : Collections.emptyList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getOutputLocation() {
        return outputLocation;
    }

    public Set<File> getSourceFiles() {
        return sourceFiles;
    }

    public List<String> getSourceLocations() {
        return sourceLocations;
    }

    public List<String> getClasspathEntries() {
        return classpathEntries;
    }

    public List<String> getModulepathEntries() {
        return modulepathEntries;
    }

    public String getReleaseVersion() {
        return releaseVersion;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public String getTargetVersion() {
        return targetVersion;
    }

    public String getSourceEncoding() {
        return sourceEncoding;
    }

    public boolean isParameters() {
        return parameters;
    }

    public boolean isDebug() {
        return debug;
    }

    public String getDebugLevel() {
        return debugLevel;
    }

    public boolean isShowDeprecation() {
        return showDeprecation;
    }

    public boolean isShowWarnings() {
        return showWarnings;
    }

    public String getProc() {
        return proc;
    }

    public File getGeneratedSourcesDirectory() {
        return generatedSourcesDirectory;
    }

    public List<String> getProcessorPathEntries() {
        return processorPathEntries;
    }

    public String[] getAnnotationProcessors() {
        return annotationProcessors != null ? annotationProcessors.clone() : null;
    }

    public List<Map.Entry<String, String>> getCustomCompilerArgumentsEntries() {
        return customCompilerArgumentsEntries;
    }

    public static final class Builder {
        private String outputLocation;
        private Set<File> sourceFiles;
        private List<String> sourceLocations;
        private List<String> classpathEntries;
        private List<String> modulepathEntries;
        private String releaseVersion;
        private String sourceVersion;
        private String targetVersion;
        private String sourceEncoding;
        private boolean parameters;
        private boolean debug = true;
        private String debugLevel;
        private boolean showDeprecation;
        private boolean showWarnings = true;
        private String proc;
        private File generatedSourcesDirectory;
        private List<String> processorPathEntries;
        private String[] annotationProcessors;
        private List<Map.Entry<String, String>> customCompilerArgumentsEntries;

        public Builder outputLocation(String outputLocation) {
            this.outputLocation = outputLocation;
            return this;
        }

        public Builder sourceFiles(Set<File> sourceFiles) {
            this.sourceFiles = sourceFiles;
            return this;
        }

        public Builder sourceLocations(List<String> sourceLocations) {
            this.sourceLocations = sourceLocations;
            return this;
        }

        public Builder classpathEntries(List<String> classpathEntries) {
            this.classpathEntries = classpathEntries;
            return this;
        }

        public Builder modulepathEntries(List<String> modulepathEntries) {
            this.modulepathEntries = modulepathEntries;
            return this;
        }

        public Builder releaseVersion(String releaseVersion) {
            this.releaseVersion = releaseVersion;
            return this;
        }

        public Builder sourceVersion(String sourceVersion) {
            this.sourceVersion = sourceVersion;
            return this;
        }

        public Builder targetVersion(String targetVersion) {
            this.targetVersion = targetVersion;
            return this;
        }

        public Builder sourceEncoding(String sourceEncoding) {
            this.sourceEncoding = sourceEncoding;
            return this;
        }

        public Builder parameters(boolean parameters) {
            this.parameters = parameters;
            return this;
        }

        public Builder debug(boolean debug) {
            this.debug = debug;
            return this;
        }

        public Builder debugLevel(String debugLevel) {
            this.debugLevel = debugLevel;
            return this;
        }

        public Builder showDeprecation(boolean showDeprecation) {
            this.showDeprecation = showDeprecation;
            return this;
        }

        public Builder showWarnings(boolean showWarnings) {
            this.showWarnings = showWarnings;
            return this;
        }

        public Builder proc(String proc) {
            this.proc = proc;
            return this;
        }

        public Builder generatedSourcesDirectory(File generatedSourcesDirectory) {
            this.generatedSourcesDirectory = generatedSourcesDirectory;
            return this;
        }

        public Builder processorPathEntries(List<String> processorPathEntries) {
            this.processorPathEntries = processorPathEntries;
            return this;
        }

        public Builder annotationProcessors(String[] annotationProcessors) {
            this.annotationProcessors = annotationProcessors;
            return this;
        }

        public Builder customCompilerArgumentsEntries(List<Map.Entry<String, String>> entries) {
            this.customCompilerArgumentsEntries = entries;
            return this;
        }

        public CompileRequest build() {
            return new CompileRequest(this);
        }
    }
}
