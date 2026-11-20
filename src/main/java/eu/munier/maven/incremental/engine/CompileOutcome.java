package eu.munier.maven.incremental.engine;

import java.util.Collections;
import java.util.List;

/**
 * Compiler-agnostic compilation result containing success status and emitted diagnostics.
 */
public record CompileOutcome(
        boolean success,
        List<CompileDiagnostic> messages) {

    public CompileOutcome {
        messages = messages != null ? List.copyOf(messages) : Collections.emptyList();
    }

    public boolean isSuccess() {
        return success;
    }

    public static CompileOutcome ok() {
        return new CompileOutcome(true, Collections.emptyList());
    }
}
