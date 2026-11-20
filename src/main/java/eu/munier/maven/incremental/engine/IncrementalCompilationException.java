package eu.munier.maven.incremental.engine;

/**
 * Checked exception raised when incremental compilation fails due to configuration,
 * I/O, or backend compiler invocation errors.
 */
public class IncrementalCompilationException extends Exception {

    public IncrementalCompilationException(String message) {
        super(message);
    }

    public IncrementalCompilationException(String message, Throwable cause) {
        super(message, cause);
    }
}
