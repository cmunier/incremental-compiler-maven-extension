package eu.munier.maven.incremental.plugin;

import eu.munier.maven.incremental.bytecode.ClassFileSummary;
import eu.munier.maven.incremental.engine.IncrementalCompilationEngine;
import eu.munier.maven.incremental.engine.PlexusDelegatingBackendCompiler;
import eu.munier.maven.incremental.state.ClasspathEntryNormalizer;
import eu.munier.maven.incremental.state.IncrementalBuildState;
import eu.munier.maven.incremental.state.ReactorAbiCache;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.codehaus.plexus.compiler.AbstractCompiler;
import org.codehaus.plexus.compiler.Compiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerException;
import org.codehaus.plexus.compiler.CompilerOutputStyle;
import org.codehaus.plexus.compiler.CompilerResult;

/**
 * Decorator around any Plexus {@link Compiler} (most notably Eclipse Tycho's {@code JDTCompiler})
 * that adds class-level incremental compilation while keeping OSGi access rules and JDT settings intact.
 */
public class IncrementalDelegatingCompiler extends AbstractCompiler {

    private final Compiler delegate;
    private final ReactorAbiCache reactorAbiCache;
    private IncrementalCompilationEngine.CompilationExecutionReport lastReport;

    public IncrementalDelegatingCompiler(Compiler delegate, ReactorAbiCache reactorAbiCache) {
        super(
                CompilerOutputStyle.ONE_OUTPUT_FILE_PER_INPUT_FILE,
                ".java",
                IncrementalJavacCompiler.INCREMENTAL_MARKER_SUFFIX,
                null);
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.reactorAbiCache = reactorAbiCache != null ? reactorAbiCache : new ReactorAbiCache();
    }

    @Override
    public String getCompilerId() {
        if (delegate instanceof AbstractCompiler ac) {
            return ac.getCompilerId();
        }
        return "jdt";
    }

    @Override
    public boolean canUpdateTarget(CompilerConfiguration configuration) {
        return true;
    }

    @Override
    public String getInputFileEnding(CompilerConfiguration configuration) throws CompilerException {
        String ending = delegate.getInputFileEnding(configuration);
        return ending != null ? ending : ".java";
    }

    @Override
    public String getOutputFileEnding(CompilerConfiguration configuration) {
        return IncrementalJavacCompiler.INCREMENTAL_MARKER_SUFFIX;
    }

    @Override
    public String getOutputFile(CompilerConfiguration configuration) throws CompilerException {
        return delegate.getOutputFile(configuration);
    }

    @Override
    public CompilerResult performCompile(CompilerConfiguration config) throws CompilerException {
        Set<String> knownConstantOwners = collectKnownConstantOwners(config);
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(reactorAbiCache);
        PlexusDelegatingBackendCompiler backend =
                new PlexusDelegatingBackendCompiler(delegate, knownConstantOwners);
        this.lastReport = engine.compile(config, backend);
        return this.lastReport.compilerResult();
    }

    @Override
    public String[] createCommandLine(CompilerConfiguration config) throws CompilerException {
        return delegate.createCommandLine(config);
    }

    public Compiler getDelegate() {
        return delegate;
    }

    public IncrementalCompilationEngine.CompilationExecutionReport getLastReport() {
        return lastReport;
    }

    private Set<String> collectKnownConstantOwners(CompilerConfiguration config) {
        Set<String> owners = new LinkedHashSet<>();
        if (config.getOutputLocation() != null && !config.getOutputLocation().isBlank()) {
            Path outDir = Path.of(config.getOutputLocation()).toAbsolutePath().normalize();
            Path stateFile = IncrementalCompilationEngine.resolveDefaultStateFile(outDir);
            IncrementalBuildState prevState = IncrementalBuildState.load(stateFile);
            if (prevState != null) {
                for (Map.Entry<String, ClassFileSummary> entry : prevState.getLocalClassSummaries().entrySet()) {
                    if (!entry.getValue().getCompileTimeConstants().isEmpty()) {
                        owners.add(entry.getKey());
                    }
                }
            }
        }
        if (config.getClasspathEntries() != null) {
            for (String rawCp : config.getClasspathEntries()) {
                Path cpPath = ClasspathEntryNormalizer.extractFilesystemPath(rawCp);
                if (cpPath != null) {
                    Map<String, ClassFileSummary> upstream = reactorAbiCache.getModuleSummaries(cpPath);
                    for (Map.Entry<String, ClassFileSummary> entry : upstream.entrySet()) {
                        if (!entry.getValue().getCompileTimeConstants().isEmpty()) {
                            owners.add(entry.getKey());
                        }
                    }
                }
            }
        }
        return owners;
    }
}
