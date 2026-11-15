package eu.munier.maven.incremental.state;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * Normalizes classpath entries from both standard {@code maven-compiler-plugin} and
 * Eclipse {@code tycho-compiler-plugin} (which appends OSGi access rules in brackets).
 */
public final class ClasspathEntryNormalizer {

    private ClasspathEntryNormalizer() {
    }

    /**
     * Extracts the clean filesystem {@link Path} from a classpath entry, stripping any
     * trailing Eclipse JDT / Tycho OSGi access rule specification {@code [...]}.
     */
    public static Path extractFilesystemPath(String rawEntry) {
        if (rawEntry == null || rawEntry.isBlank()) {
            return null;
        }
        String trimmed = stripAccessRules(rawEntry.trim());
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Path.of(trimmed).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    public static String stripAccessRules(String rawEntry) {
        if (rawEntry == null) {
            return "";
        }
        if (rawEntry.endsWith("]")) {
            int bracketStart = rawEntry.indexOf('[');
            if (bracketStart > 0) {
                String candidatePath = rawEntry.substring(0, bracketStart);
                String ruleSection = rawEntry.substring(bracketStart + 1, rawEntry.length() - 1);
                if (looksLikeJdtAccessRule(ruleSection) || existsSafe(candidatePath)) {
                    return candidatePath;
                }
            }
        }
        return rawEntry;
    }

    private static boolean looksLikeJdtAccessRule(String ruleSection) {
        return ruleSection.isEmpty()
                || ruleSection.startsWith("+")
                || ruleSection.startsWith("-")
                || ruleSection.startsWith("~")
                || ruleSection.startsWith("?")
                || ruleSection.contains(";");
    }

    private static boolean existsSafe(String pathStr) {
        try {
            return Files.exists(Path.of(pathStr));
        } catch (InvalidPathException e) {
            return false;
        }
    }

    public static boolean hasOsgiAccessRules(List<String> classpathEntries) {
        if (classpathEntries == null) {
            return false;
        }
        for (String entry : classpathEntries) {
            if (entry != null && entry.endsWith("]") && entry.indexOf('[') > 0) {
                int idx = entry.indexOf('[');
                if (looksLikeJdtAccessRule(entry.substring(idx + 1, entry.length() - 1))) {
                    return true;
                }
            }
        }
        return false;
    }
}
