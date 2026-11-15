package eu.munier.maven.incremental.plugin;

import eu.munier.maven.incremental.engine.IncrementalCompilationEngine;
import eu.munier.maven.incremental.engine.JavacBackendCompiler;
import eu.munier.maven.incremental.state.ReactorAbiCache;
import java.nio.file.Path;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.codehaus.plexus.compiler.AbstractCompiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerException;
import org.codehaus.plexus.compiler.CompilerOutputStyle;
import org.codehaus.plexus.compiler.CompilerResult;

/**
 * Plexus {@link org.codehaus.plexus.compiler.Compiler} component ({@code compilerId = "incremental-javac"})
 * providing class-level incremental compilation for {@code maven-compiler-plugin}.
 */
@Named("incremental-javac")
@Singleton
public class IncrementalJavacCompiler extends AbstractCompiler {

    public static final String COMPILER_ID = "incremental-javac";
    public static final String INCREMENTAL_MARKER_SUFFIX = ".inc-marker";

    private ReactorAbiCache reactorAbiCache;
    private IncrementalCompilationEngine.CompilationExecutionReport lastReport;

    public IncrementalJavacCompiler() {
        this(new ReactorAbiCache());
    }

    @Inject
    public IncrementalJavacCompiler(ReactorAbiCache reactorAbiCache) {
        super(CompilerOutputStyle.ONE_OUTPUT_FILE_PER_INPUT_FILE, ".java", INCREMENTAL_MARKER_SUFFIX, null);
        this.reactorAbiCache = reactorAbiCache != null ? reactorAbiCache : new ReactorAbiCache();
    }

    @Override
    public String getCompilerId() {
        return COMPILER_ID;
    }

    @Override
    public boolean canUpdateTarget(CompilerConfiguration configuration) {
        return true;
    }

    @Override
    public CompilerResult performCompile(CompilerConfiguration config) throws CompilerException {
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(getReactorAbiCache());
        this.lastReport = engine.compile(config, new JavacBackendCompiler());
        return this.lastReport.compilerResult();
    }

    @Override
    public String[] createCommandLine(CompilerConfiguration config) throws CompilerException {
        try {
            Path outDir = config.getOutputLocation() != null
                    ? Path.of(config.getOutputLocation())
                    : Path.of("target/classes");
            List<String> opts = JavacBackendCompiler.buildJavacOptions(config, outDir);
            return opts.toArray(String[]::new);
        } catch (Exception e) {
            throw new CompilerException("Failed to build javac command line", e);
        }
    }

    public ReactorAbiCache getReactorAbiCache() {
        if (reactorAbiCache == null) {
            reactorAbiCache = new ReactorAbiCache();
        }
        return reactorAbiCache;
    }

    public IncrementalCompilationEngine.CompilationExecutionReport getLastReport() {
        return lastReport;
    }
}
