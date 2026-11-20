package eu.munier.maven.incremental.engine;

/**
 * Compiler-agnostic diagnostic message emitted during incremental compilation.
 */
public record CompileDiagnostic(
        String file,
        Kind kind,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn,
        String message) {

    public enum Kind {
        ERROR,
        WARNING,
        MANDATORY_WARNING,
        NOTE,
        OTHER
    }

    public CompileDiagnostic(String message, Kind kind) {
        this(null, kind, 0, 0, 0, 0, message);
    }
}
