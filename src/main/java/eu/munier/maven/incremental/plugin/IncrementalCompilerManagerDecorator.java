package eu.munier.maven.incremental.plugin;

import eu.munier.maven.incremental.state.ReactorAbiCache;
import java.util.Objects;
import org.codehaus.plexus.compiler.Compiler;
import org.codehaus.plexus.compiler.manager.CompilerManager;
import org.codehaus.plexus.compiler.manager.NoSuchCompilerException;

/**
 * Decorator around {@link CompilerManager} that wraps resolved compilers (such as
 * {@code javac} in {@code maven-compiler-plugin} or {@code jdt} in {@code tycho-compiler-plugin})
 * with class-level incremental compilation capabilities.
 */
public final class IncrementalCompilerManagerDecorator implements CompilerManager {

    private final CompilerManager delegate;
    private final ReactorAbiCache reactorAbiCache;

    public IncrementalCompilerManagerDecorator(CompilerManager delegate, ReactorAbiCache reactorAbiCache) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.reactorAbiCache = reactorAbiCache != null ? reactorAbiCache : new ReactorAbiCache();
    }

    @Override
    public Compiler getCompiler(String compilerId) throws NoSuchCompilerException {
        if (IncrementalJavacCompiler.COMPILER_ID.equals(compilerId) || "javac".equals(compilerId)) {
            return new IncrementalJavacCompiler(reactorAbiCache);
        }
        Compiler original = delegate.getCompiler(compilerId);
        if (original instanceof IncrementalJavacCompiler
                || original instanceof IncrementalDelegatingCompiler) {
            return original;
        }
        return new IncrementalDelegatingCompiler(original, reactorAbiCache);
    }

    public CompilerManager getDelegate() {
        return delegate;
    }
}
