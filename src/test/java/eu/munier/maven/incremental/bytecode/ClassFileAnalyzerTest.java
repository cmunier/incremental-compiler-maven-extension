package eu.munier.maven.incremental.bytecode;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassFileAnalyzerTest {

    @TempDir
    Path tempDir;

    private final ClassFileAnalyzer analyzer = new ClassFileAnalyzer();

    @Test
    void privateMethodAndBodyChangesPreserveAbiHashWhilePublicSignatureChangeAltersIt() throws Exception {
        Path v1Dir = compileSources("v1", """
                package com.example;
                public class Calculator {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """);

        Path v2Dir = compileSources("v2", """
                package com.example;
                public class Calculator {
                    private int secretMultiplier = 42;
                    public int add(int a, int b) {
                        return helper(a) + b;
                    }
                    private int helper(int x) {
                        return x + (secretMultiplier - 42);
                    }
                }
                """);

        Path v3Dir = compileSources("v3", """
                package com.example;
                public class Calculator {
                    public long add(int a, int b) {
                        return (long) a + b;
                    }
                }
                """);

        ClassFileSummary s1 = analyzer.analyze(v1Dir.resolve("com/example/Calculator.class"));
        ClassFileSummary s2 = analyzer.analyze(v2Dir.resolve("com/example/Calculator.class"));
        ClassFileSummary s3 = analyzer.analyze(v3Dir.resolve("com/example/Calculator.class"));

        assertThat(s1.getBinaryClassName()).isEqualTo("com.example.Calculator");
        assertThat(s1.hasSameAbi(s2)).isTrue();
        assertThat(s1.hasSameHierarchy(s2)).isTrue();
        assertThat(s1.hasSameAbi(s3)).isFalse();
    }

    @Test
    void extractsMethodBodyReferencesFromConstantPoolEvenWithSkipCode() throws Exception {
        Path outDir = compileSources("cpRefs",
                """
                package com.example;
                public class TargetDep {
                    public static String fetch() { return "ok"; }
                }
                """,
                """
                package com.example;
                import java.util.List;
                public class GenericParam {}
                """,
                """
                package com.example;
                import java.util.List;
                public class Caller {
                    private List<GenericParam> items;
                    public String execute() {
                        // TargetDep is only referenced inside the method body!
                        return TargetDep.fetch();
                    }
                }
                """);

        ClassFileSummary callerSummary = analyzer.analyze(outDir.resolve("com/example/Caller.class"));

        assertThat(callerSummary.getReferencedClassNames())
                .contains("com.example.TargetDep", "com.example.GenericParam", "java.util.List");
    }

    @Test
    void tracksCompileTimeConstantChangesAndHierarchyChanges() throws Exception {
        Path v1Dir = compileSources("constV1", """
                package com.example;
                public class Config implements Runnable {
                    public static final int TIMEOUT_MS = 1000;
                    public static final String ENDPOINT = "https://v1.example.com";
                    @Override public void run() {}
                }
                """);

        Path v2Dir = compileSources("constV2", """
                package com.example;
                public class Config implements Runnable {
                    public static final int TIMEOUT_MS = 2500;
                    public static final String ENDPOINT = "https://v1.example.com";
                    @Override public void run() {}
                }
                """);

        Path v3Dir = compileSources("constV3", """
                package com.example;
                import java.io.Serializable;
                public class Config implements Runnable, Serializable {
                    public static final int TIMEOUT_MS = 2500;
                    public static final String ENDPOINT = "https://v1.example.com";
                    @Override public void run() {}
                }
                """);

        ClassFileSummary s1 = analyzer.analyze(v1Dir.resolve("com/example/Config.class"));
        ClassFileSummary s2 = analyzer.analyze(v2Dir.resolve("com/example/Config.class"));
        ClassFileSummary s3 = analyzer.analyze(v3Dir.resolve("com/example/Config.class"));

        assertThat(s1.hasSameConstants(s2)).isFalse();
        assertThat(s1.hasSameAbi(s2)).isFalse();
        assertThat(s1.hasSameHierarchy(s2)).isTrue();

        assertThat(s2.hasSameConstants(s3)).isTrue();
        assertThat(s2.hasSameHierarchy(s3)).isFalse();
        assertThat(s3.getInterfaceNames()).containsExactly("java.io.Serializable", "java.lang.Runnable");
    }

    @Test
    void tracksRecordComponentsAnnotationsAndInnerClassesInAbi() throws Exception {
        Path v1Dir = compileSources("recV1", """
                package com.example;
                public record UserRecord(@Deprecated String id, int age) {
                    public static class NestedInfo {}
                }
                """);

        Path v2Dir = compileSources("recV2", """
                package com.example;
                public record UserRecord(String id, int age) {
                    public static class NestedInfo {}
                }
                """);

        ClassFileSummary s1 = analyzer.analyze(v1Dir.resolve("com/example/UserRecord.class"));
        ClassFileSummary s2 = analyzer.analyze(v2Dir.resolve("com/example/UserRecord.class"));

        assertThat(s1.getSuperClassName()).isEqualTo("java.lang.Record");
        assertThat(s1.hasSameAbi(s2)).isFalse();
        assertThat(s1.getReferencedClassNames()).contains("com.example.UserRecord$NestedInfo", "java.lang.Deprecated");
    }

    private Path compileSources(String label, String... sources) throws IOException {
        Path srcDir = tempDir.resolve(label + "-src");
        Path outDir = tempDir.resolve(label + "-classes");
        Files.createDirectories(srcDir);
        Files.createDirectories(outDir);

        List<Path> javaFiles = new java.util.ArrayList<>();
        for (int i = 0; i < sources.length; i++) {
            String content = sources[i];
            String className = extractClassName(content, "Class" + i);
            Path pkgDir = srcDir.resolve("com/example");
            Files.createDirectories(pkgDir);
            Path file = pkgDir.resolve(className + ".java");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            javaFiles.add(file);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var units = fm.getJavaFileObjectsFromPaths(javaFiles);
            List<String> opts = List.of("-d", outDir.toString());
            Boolean ok = compiler.getTask(null, fm, null, opts, null, units).call();
            assertThat(ok).isTrue();
        }
        return outDir;
    }

    private static String extractClassName(String source, String fallback) {
        var m = java.util.regex.Pattern.compile("(?:class|record)\\s+([A-Za-z0-9_]+)").matcher(source);
        return m.find() ? m.group(1) : fallback;
    }
}
