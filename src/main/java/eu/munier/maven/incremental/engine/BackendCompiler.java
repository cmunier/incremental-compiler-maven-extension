package eu.munier.maven.incremental.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SPI for invoking a compiler backend (such as JSR-199 {@code javac} or delegated Eclipse Tycho {@code jdt})
 * on a specific subset of dirty source files during an incremental compilation round.
 */
public interface BackendCompiler {

    record RoundCompilationResult(
            boolean success,
            List<CompileDiagnostic> messages,
            Map<Path, Set<Path>> sourceToClassFiles,
            Map<Path, Set<Path>> sourceToAptGeneratedSources,
            Map<Path, Set<String>> sourceToAstReferencedClasses,
            Map<Path, String> sourceToPackageName,
            Map<Path, Set<String>> sourceToWildcardImports,
            Set<Path> allGeneratedClassFiles,
            boolean aggregatingAptDetected,
            Set<Path> aptGeneratedResourceFiles) {
    }

    RoundCompilationResult compileSubset(
            CompileRequest baseConfig,
            Set<Path> sourcesToCompile,
            Path outputDirectory) throws IncrementalCompilationException;
}
