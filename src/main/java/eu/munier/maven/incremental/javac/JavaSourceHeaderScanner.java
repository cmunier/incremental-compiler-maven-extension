package eu.munier.maven.incremental.javac;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight scanner for {@code .java} source files that extracts package declarations,
 * explicit/wildcard/static imports, and identifier tokens. Used as a fast supplement for
 * non-JavacTask backends (such as Eclipse Tycho's {@code JDTCompiler}) to track inlined
 * {@code static final} constant references and wildcard imports.
 */
public final class JavaSourceHeaderScanner {

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;");
    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([a-zA-Z0-9_.*]+)\\s*;");
    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("\\b([A-Z][a-zA-Z0-9_$]*)\\b");

    public record ScanResult(
            String packageName,
            Set<String> explicitImportedClasses,
            Set<String> wildcardImportedPackages,
            Set<String> capitalizedTokens) {
    }

    public static ScanResult scan(Path javaFile) {
        try {
            String raw = Files.readString(javaFile, StandardCharsets.UTF_8);
            String stripped = stripCommentsAndStrings(raw);

            String packageName = "";
            Matcher pkgMatcher = PACKAGE_PATTERN.matcher(stripped);
            if (pkgMatcher.find()) {
                packageName = pkgMatcher.group(1).trim();
            }

            Set<String> explicitImports = new LinkedHashSet<>();
            Set<String> wildcardImports = new LinkedHashSet<>();
            Matcher impMatcher = IMPORT_PATTERN.matcher(stripped);
            while (impMatcher.find()) {
                boolean isStatic = impMatcher.group(1) != null;
                String target = impMatcher.group(2).trim();
                if (target.endsWith(".*")) {
                    String prefix = target.substring(0, target.length() - 2);
                    wildcardImports.add(prefix);
                    if (isStatic) {
                        explicitImports.add(prefix);
                    }
                } else if (isStatic) {
                    int lastDot = target.lastIndexOf('.');
                    if (lastDot > 0) {
                        explicitImports.add(target.substring(0, lastDot));
                    }
                } else {
                    explicitImports.add(target);
                }
            }

            Set<String> capitalizedTokens = new LinkedHashSet<>();
            Matcher idMatcher = IDENTIFIER_PATTERN.matcher(stripped);
            while (idMatcher.find()) {
                capitalizedTokens.add(idMatcher.group(1));
            }

            return new ScanResult(
                    packageName,
                    Collections.unmodifiableSet(explicitImports),
                    Collections.unmodifiableSet(wildcardImports),
                    Collections.unmodifiableSet(capitalizedTokens));
        } catch (IOException e) {
            return new ScanResult("", Set.of(), Set.of(), Set.of());
        }
    }

    private static String stripCommentsAndStrings(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        int len = src.length();
        int i = 0;
        while (i < len) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < len && src.charAt(i + 1) == '/') {
                i += 2;
                while (i < len && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < len && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') {
                        sb.append('\n');
                    }
                    i++;
                }
                i += 2;
            } else if (c == '"' || c == '\'') {
                char quote = c;
                sb.append(' ');
                i++;
                while (i < len && src.charAt(i) != quote) {
                    if (src.charAt(i) == '\\' && i + 1 < len) {
                        i += 2;
                    } else {
                        if (src.charAt(i) == '\n') {
                            sb.append('\n');
                        }
                        i++;
                    }
                }
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
