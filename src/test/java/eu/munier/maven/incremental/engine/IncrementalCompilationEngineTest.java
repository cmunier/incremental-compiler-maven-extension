package eu.munier.maven.incremental.engine;

import static org.assertj.core.api.Assertions.assertThat;

import eu.munier.maven.incremental.bytecode.ClassFileAnalyzer;
import eu.munier.maven.incremental.bytecode.ClassFileSummary;
import eu.munier.maven.incremental.plugin.CrossRealmCompilerBridge;
import eu.munier.maven.incremental.plugin.IncrementalCompileExecutionListener;
import eu.munier.maven.incremental.state.ClasspathEntryNormalizer;
import eu.munier.maven.incremental.state.ReactorAbiCache;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.apache.maven.plugin.AbstractMojo;
import org.codehaus.plexus.compiler.Compiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;
import org.codehaus.plexus.compiler.CompilerResult;
import org.codehaus.plexus.compiler.manager.CompilerManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IncrementalCompilationEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void scenario1_noOpAndTouchedFileWithSameContentCompileZeroFiles() throws Exception {
        ReactorAbiCache cache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(cache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        Path moduleDir = tempDir.resolve("mod-noop");
        Path srcDir = moduleDir.resolve("src/main/java");
        Path outDir = moduleDir.resolve("target/classes");

        Path fileA = writeJava(srcDir, "com/example/Alpha.java", """
                package com.example;
                public class Alpha {
                    public String name() { return "alpha"; }
                }
                """);
        Path fileB = writeJava(srcDir, "com/example/Beta.java", """
                package com.example;
                public class Beta {
                    public String call(Alpha a) { return a.name(); }
                }
                """);

        CompilerConfiguration cfg = createConfig(srcDir, outDir, List.of(fileA, fileB));

        // 1. Initial full build
        var r1 = runCompile(engine, cfg, backend);
        assertThat(r1.compilerResult().isSuccess()).isTrue();
        assertThat(r1.fullRebuild()).isTrue();
        assertThat(r1.totalSourcesCount()).isEqualTo(2);
        assertThat(r1.compiledSourcesCount()).isEqualTo(2);
        assertThat(r1.skippedSourcesCount()).isZero();
        assertThat(r1.compiledPercentage()).isEqualTo(100.0);
        assertThat(r1.skippedPercentage()).isZero();
        assertThat(r1.compiledSources()).containsExactlyInAnyOrder(fileA, fileB);

        // 2. Immediate second build -> 0 files compiled
        var r2 = runCompile(engine, cfg, backend);
        assertThat(r2.compilerResult().isSuccess()).isTrue();
        assertThat(r2.fullRebuild()).isFalse();
        assertThat(r2.roundsExecuted()).isZero();
        assertThat(r2.totalSourcesCount()).isEqualTo(2);
        assertThat(r2.compiledSourcesCount()).isZero();
        assertThat(r2.skippedSourcesCount()).isEqualTo(2);
        assertThat(r2.compiledPercentage()).isZero();
        assertThat(r2.skippedPercentage()).isEqualTo(100.0);
        assertThat(r2.compiledSources()).isEmpty();

        // 3. Touch fileA (advance timestamp by +10s without changing content) -> 0 files compiled
        Files.setLastModifiedTime(fileA, FileTime.from(Instant.now().plusSeconds(10)));
        var r3 = runCompile(engine, cfg, backend);
        assertThat(r3.compilerResult().isSuccess()).isTrue();
        assertThat(r3.roundsExecuted()).isZero();
        assertThat(r3.compiledSources()).isEmpty();
    }

    @Test
    void scenario2_privateMethodBodyChangeOnlyRecompilesModifiedClass() throws Exception {
        ReactorAbiCache cache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(cache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        Path moduleDir = tempDir.resolve("mod-abi-shortcircuit");
        Path srcDir = moduleDir.resolve("src/main/java");
        Path outDir = moduleDir.resolve("target/classes");

        Path fileA = writeJava(srcDir, "com/example/ServiceA.java", """
                package com.example;
                public class ServiceA {
                    public int compute(int x) {
                        return x * 2;
                    }
                }
                """);
        Path fileB = writeJava(srcDir, "com/example/ServiceB.java", """
                package com.example;
                public class ServiceB {
                    private final ServiceA a = new ServiceA();
                    public int delegate(int v) {
                        return a.compute(v) + 1;
                    }
                }
                """);
        Path fileC = writeJava(srcDir, "com/example/ServiceC.java", """
                package com.example;
                public class ServiceC {
                    private final ServiceB b = new ServiceB();
                    public int run() {
                        return b.delegate(5);
                    }
                }
                """);

        CompilerConfiguration cfg = createConfig(srcDir, outDir, List.of(fileA, fileB, fileC));
        var r1 = runCompile(engine, cfg, backend);
        assertThat(r1.compilerResult().isSuccess()).isTrue();
        assertThat(r1.compiledSources()).hasSize(3);

        // Modify ONLY the internal implementation of ServiceA (add a private helper method & change body)
        writeJava(srcDir, "com/example/ServiceA.java", """
                package com.example;
                public class ServiceA {
                    public int compute(int x) {
                        return internalScale(x);
                    }
                    private int internalScale(int x) {
                        return x * 10;
                    }
                }
                """);

        var r2 = runCompile(engine, cfg, backend);
        assertThat(r2.compilerResult().isSuccess()).isTrue();
        assertThat(r2.fullRebuild()).isFalse();
        assertThat(r2.roundsExecuted()).isEqualTo(1);
        assertThat(r2.totalSourcesCount()).isEqualTo(3);
        assertThat(r2.compiledSourcesCount()).isEqualTo(1);
        assertThat(r2.skippedSourcesCount()).isEqualTo(2);
        // Only ServiceA was recompiled! ServiceB and ServiceC were skipped because ServiceA's ABI did not change.
        assertThat(r2.compiledSources()).containsExactly(fileA);
    }

    @Test
    void scenario3_publicSignatureChangeRecompilesDirectDependentsButStopsWhenDownstreamAbiUnchanged() throws Exception {
        ReactorAbiCache cache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(cache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        Path moduleDir = tempDir.resolve("mod-abi-cascade");
        Path srcDir = moduleDir.resolve("src/main/java");
        Path outDir = moduleDir.resolve("target/classes");

        Path fileA = writeJava(srcDir, "com/example/RepoA.java", """
                package com.example;
                public class RepoA {
                    public int fetchCount() { return 10; }
                }
                """);
        Path fileB = writeJava(srcDir, "com/example/FacadeB.java", """
                package com.example;
                public class FacadeB {
                    private final RepoA repo = new RepoA();
                    public String summary() {
                        return "Count=" + repo.fetchCount();
                    }
                }
                """);
        Path fileC = writeJava(srcDir, "com/example/ControllerC.java", """
                package com.example;
                public class ControllerC {
                    private final FacadeB facade = new FacadeB();
                    public String handle() {
                        return facade.summary();
                    }
                }
                """);
        Path fileUnrelated = writeJava(srcDir, "com/example/Unrelated.java", """
                package com.example;
                public class Unrelated {
                    public void ping() {}
                }
                """);

        CompilerConfiguration cfg = createConfig(srcDir, outDir, List.of(fileA, fileB, fileC, fileUnrelated));
        assertThat(runCompile(engine, cfg, backend).compilerResult().isSuccess()).isTrue();

        // Change RepoA.fetchCount() return type from int to long (ABI change!)
        // FacadeB calls repo.fetchCount() in string concatenation, so FacadeB still compiles and its own ABI (String summary()) stays unchanged!
        writeJava(srcDir, "com/example/RepoA.java", """
                package com.example;
                public class RepoA {
                    public long fetchCount() { return 100L; }
                }
                """);

        var r2 = runCompile(engine, cfg, backend);
        assertThat(r2.compilerResult().isSuccess()).isTrue();
        assertThat(r2.roundsExecuted()).isEqualTo(2);
        // Round 1 compiled RepoA, detected ABI change; Round 2 compiled FacadeB, saw FacadeB's ABI was unchanged,
        // so ControllerC and Unrelated were NOT recompiled!
        assertThat(r2.compiledSources()).containsExactlyInAnyOrder(fileA, fileB);
    }

    @Test
    void scenario4_inlinedCompileTimeConstantsTriggerDependentRecompilationViaAstTracking() throws Exception {
        ReactorAbiCache cache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(cache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        Path moduleDir = tempDir.resolve("mod-constants");
        Path srcDir = moduleDir.resolve("src/main/java");
        Path outDir = moduleDir.resolve("target/classes");

        Path constantsFile = writeJava(srcDir, "com/example/AppConstants.java", """
                package com.example;
                public class AppConstants {
                    public static final String API_VERSION = "v1";
                    public static final int MAX_ITEMS = 25;
                }
                """);
        Path consumerFile = writeJava(srcDir, "com/example/ConstantConsumer.java", """
                package com.example;
                public class ConstantConsumer {
                    public String describe() {
                        return AppConstants.API_VERSION + "#" + AppConstants.MAX_ITEMS;
                    }
                }
                """);

        CompilerConfiguration cfg = createConfig(srcDir, outDir, List.of(constantsFile, consumerFile));
        assertThat(runCompile(engine, cfg, backend).compilerResult().isSuccess()).isTrue();
        assertThat(invokeDescribe(outDir, "com.example.ConstantConsumer")).isEqualTo("v1#25");

        // Update the compile-time constants in AppConstants.java
        writeJava(srcDir, "com/example/AppConstants.java", """
                package com.example;
                public class AppConstants {
                    public static final String API_VERSION = "v2";
                    public static final int MAX_ITEMS = 99;
                }
                """);

        var r2 = runCompile(engine, cfg, backend);
        assertThat(r2.compilerResult().isSuccess()).isTrue();
        // ConstantConsumer MUST be recompiled even though javac inlines constants and omits AppConstants from ConstantConsumer's bytecode Constant Pool!
        assertThat(r2.compiledSources()).containsExactlyInAnyOrder(constantsFile, consumerFile);
        assertThat(invokeDescribe(outDir, "com.example.ConstantConsumer")).isEqualTo("v2#99");
    }

    @Test
    void scenario5_removedInnerClassAndDeletedSourceFileCleanUpStaleClassFiles() throws Exception {
        ReactorAbiCache cache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(cache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        Path moduleDir = tempDir.resolve("mod-cleanup");
        Path srcDir = moduleDir.resolve("src/main/java");
        Path outDir = moduleDir.resolve("target/classes");

        Path outerFile = writeJava(srcDir, "com/example/Outer.java", """
                package com.example;
                public class Outer {
                    public static class NestedHelper {
                        public static int val() { return 1; }
                    }
                }
                """);
        Path obsoleteFile = writeJava(srcDir, "com/example/Obsolete.java", """
                package com.example;
                public class Obsolete {}
                """);

        CompilerConfiguration cfg = createConfig(srcDir, outDir, List.of(outerFile, obsoleteFile));
        assertThat(runCompile(engine, cfg, backend).compilerResult().isSuccess()).isTrue();
        assertThat(outDir.resolve("com/example/Outer.class")).exists();
        assertThat(outDir.resolve("com/example/Outer$NestedHelper.class")).exists();
        assertThat(outDir.resolve("com/example/Obsolete.class")).exists();

        // Remove NestedHelper from Outer.java and delete Obsolete.java
        writeJava(srcDir, "com/example/Outer.java", """
                package com.example;
                public class Outer {
                }
                """);
        Files.delete(obsoleteFile);

        CompilerConfiguration cfg2 = createConfig(srcDir, outDir, List.of(outerFile));
        var r2 = runCompile(engine, cfg2, backend);
        assertThat(r2.compilerResult().isSuccess()).isTrue();
        assertThat(outDir.resolve("com/example/Outer.class")).exists();
        assertThat(outDir.resolve("com/example/Outer$NestedHelper.class")).doesNotExist();
        assertThat(outDir.resolve("com/example/Obsolete.class")).doesNotExist();
    }

    @Test
    void scenario6_breakingSignatureChangePersistsDirtyDependentAcrossFailedRunsUntilFixed() throws Exception {
        ReactorAbiCache cache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(cache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        Path moduleDir = tempDir.resolve("mod-broken-recovery");
        Path srcDir = moduleDir.resolve("src/main/java");
        Path outDir = moduleDir.resolve("target/classes");

        Path providerFile = writeJava(srcDir, "com/example/Provider.java", """
                package com.example;
                public class Provider {
                    public String greet(String name) { return "Hi " + name; }
                }
                """);
        Path callerFile = writeJava(srcDir, "com/example/Caller.java", """
                package com.example;
                public class Caller {
                    public String call() { return new Provider().greet("World"); }
                }
                """);

        CompilerConfiguration cfg = createConfig(srcDir, outDir, List.of(providerFile, callerFile));
        assertThat(runCompile(engine, cfg, backend).compilerResult().isSuccess()).isTrue();

        // Break Provider's signature so Provider compiles in Round 1, but Caller fails in Round 2
        writeJava(srcDir, "com/example/Provider.java", """
                package com.example;
                public class Provider {
                    public String greet(String firstName, String lastName) {
                        return "Hi " + firstName + " " + lastName;
                    }
                }
                """);

        var failedRun1 = runCompile(engine, cfg, backend);
        assertThat(failedRun1.compilerResult().isSuccess()).isFalse();
        assertThat(failedRun1.compiledSources()).containsExactlyInAnyOrder(providerFile, callerFile);

        // Run build AGAIN without modifying any file -> must NOT report "up-to-date"; Caller is still dirty & broken!
        var failedRun2 = runCompile(engine, cfg, backend);
        assertThat(failedRun2.compilerResult().isSuccess()).isFalse();
        assertThat(failedRun2.compiledSources()).containsExactly(callerFile);

        // Now fix Caller.java -> only Caller.java is compiled and build succeeds
        writeJava(srcDir, "com/example/Caller.java", """
                package com.example;
                public class Caller {
                    public String call() { return new Provider().greet("Ada", "Lovelace"); }
                }
                """);

        var fixedRun = runCompile(engine, cfg, backend);
        assertThat(fixedRun.compilerResult().isSuccess()).isTrue();
        assertThat(fixedRun.compiledSources()).containsExactly(callerFile);
    }

    @Test
    void scenario7_multiModuleReactorCrossModuleAbiPropagation() throws Exception {
        ReactorAbiCache sharedReactorCache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(sharedReactorCache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        // Upstream module: core
        Path coreDir = tempDir.resolve("reactor/module-core");
        Path coreSrc = coreDir.resolve("src/main/java");
        Path coreOut = coreDir.resolve("target/classes");
        Path coreApiFile = writeJava(coreSrc, "com/core/UserApi.java", """
                package com.core;
                public class UserApi {
                    public String findUser(int id) {
                        return "User-" + id;
                    }
                }
                """);

        // Downstream module: web (depends on coreOut)
        Path webDir = tempDir.resolve("reactor/module-web");
        Path webSrc = webDir.resolve("src/main/java");
        Path webOut = webDir.resolve("target/classes");
        Path webEndpointFile = writeJava(webSrc, "com/web/UserEndpoint.java", """
                package com.web;
                import com.core.UserApi;
                public class UserEndpoint {
                    private final UserApi api = new UserApi();
                    public String get(int id) { return api.findUser(id); }
                }
                """);
        Path webHealthFile = writeJava(webSrc, "com/web/HealthCheck.java", """
                package com.web;
                public class HealthCheck {
                    public String status() { return "OK"; }
                }
                """);

        CompilerConfiguration coreCfg = createConfig(coreSrc, coreOut, List.of(coreApiFile));
        CompilerConfiguration webCfg = createConfig(webSrc, webOut, List.of(webEndpointFile, webHealthFile));
        webCfg.setClasspathEntries(List.of(coreOut.toString()));

        // 1. Initial build of both modules
        assertThat(runCompile(engine, coreCfg, backend).compilerResult().isSuccess()).isTrue();
        assertThat(runCompile(engine, webCfg, backend).compilerResult().isSuccess()).isTrue();

        // 2. Internal implementation change in module-core (ABI unchanged)
        writeJava(coreSrc, "com/core/UserApi.java", """
                package com.core;
                public class UserApi {
                    public String findUser(int id) {
                        return formatId(id);
                    }
                    private String formatId(int id) {
                        return "User#" + id;
                    }
                }
                """);

        var coreInternalRebuild = runCompile(engine, coreCfg, backend);
        assertThat(coreInternalRebuild.compiledSources()).containsExactly(coreApiFile);

        // Downstream module-web sees that UserApi's ABI hash in module-core is unchanged -> compiles 0 files!
        var webAfterInternalChange = runCompile(engine, webCfg, backend);
        assertThat(webAfterInternalChange.compilerResult().isSuccess()).isTrue();
        assertThat(webAfterInternalChange.compiledSources()).isEmpty();

        // 3. Public ABI change in module-core (add a new public method to UserApi)
        writeJava(coreSrc, "com/core/UserApi.java", """
                package com.core;
                public class UserApi {
                    public String findUser(int id) {
                        return "User#" + id;
                    }
                    public String version() { return "2.0"; }
                }
                """);

        assertThat(runCompile(engine, coreCfg, backend).compilerResult().isSuccess()).isTrue();

        // Downstream module-web recompiles ONLY UserEndpoint.java (HealthCheck.java is untouched!)
        var webAfterAbiChange = runCompile(engine, webCfg, backend);
        assertThat(webAfterAbiChange.compilerResult().isSuccess()).isTrue();
        assertThat(webAfterAbiChange.compiledSources()).containsExactly(webEndpointFile);
    }

    @Test
    void scenario8_jsr269AnnotationProcessorIncrementalGeneration() throws Exception {
        ReactorAbiCache cache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(cache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        Path moduleDir = tempDir.resolve("mod-apt");
        Path srcDir = moduleDir.resolve("src/main/java");
        Path genDir = moduleDir.resolve("target/generated-sources/annotations");
        Path outDir = moduleDir.resolve("target/classes");

        Path annFile = writeJava(srcDir, "com/example/GenerateBuilder.java", """
                package com.example;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Retention(RetentionPolicy.SOURCE)
                @Target(ElementType.TYPE)
                public @interface GenerateBuilder {
                    String suffix() default "Generated";
                }
                """);
        Path modelFile = writeJava(srcDir, "com/example/Customer.java", """
                package com.example;
                @GenerateBuilder(suffix = "Builder")
                public class Customer {
                    public String id() { return "C1"; }
                }
                """);
        Path plainFile = writeJava(srcDir, "com/example/PlainService.java", """
                package com.example;
                public class PlainService {}
                """);

        CompilerConfiguration cfg = createConfig(srcDir, outDir, List.of(annFile, modelFile, plainFile));
        cfg.setGeneratedSourcesDirectory(genDir.toFile());
        cfg.setAnnotationProcessors(new String[] { TestBuilderAnnotationProcessor.class.getName() });

        // 1. Initial compile runs the JSR-269 processor and generates CustomerBuilder.java + CustomerBuilder.class
        var r1 = runCompile(engine, cfg, backend);
        assertThat(r1.compilerResult().isSuccess()).isTrue();
        assertThat(genDir.resolve("com/example/CustomerBuilder.java")).exists();
        assertThat(outDir.resolve("com/example/CustomerBuilder.class")).exists();

        // 2. Modify Customer.java (@GenerateBuilder suffix changed from "Builder" to "Factory")
        writeJava(srcDir, "com/example/Customer.java", """
                package com.example;
                @GenerateBuilder(suffix = "Factory")
                public class Customer {
                    public String id() { return "C2"; }
                }
                """);

        var r2 = runCompile(engine, cfg, backend);
        assertThat(r2.compilerResult().isSuccess()).isTrue();
        assertThat(r2.compiledSources()).containsExactly(modelFile);
        // Old APT output CustomerBuilder (.java & .class) was cleaned up and new CustomerFactory was generated!
        assertThat(genDir.resolve("com/example/CustomerBuilder.java")).doesNotExist();
        assertThat(outDir.resolve("com/example/CustomerBuilder.class")).doesNotExist();
        assertThat(genDir.resolve("com/example/CustomerFactory.java")).exists();
        assertThat(outDir.resolve("com/example/CustomerFactory.class")).exists();
    }

    @Test
    void scenario9_tychoCompilerIntegrationWithDelegatingJdtAndOsgiAccessRules() throws Exception {
        ReactorAbiCache cache = new ReactorAbiCache();

        // Verify ClasspathEntryNormalizer handles Tycho OSGi access rules properly
        Path bundleJar = tempDir.resolve("plugins/org.example.bundle_1.0.0.jar");
        Files.createDirectories(bundleJar.getParent());
        try (var jos = new java.util.jar.JarOutputStream(Files.newOutputStream(bundleJar))) {
            jos.putNextEntry(new java.util.jar.JarEntry("META-INF/MANIFEST.MF"));
            jos.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        String tychoCpEntry = bundleJar + "[+org/example/api/*;~org/example/internal/*;?**/*]";
        assertThat(ClasspathEntryNormalizer.hasOsgiAccessRules(List.of(tychoCpEntry))).isTrue();
        assertThat(ClasspathEntryNormalizer.extractFilesystemPath(tychoCpEntry))
                .isEqualTo(bundleJar.toAbsolutePath().normalize());

        // Verify IncrementalCompileExecutionListener preserves compilerId="jdt" on Tycho Mojos
        // while wrapping CompilerManager via CrossRealmCompilerBridge
        DummyTychoMojo tychoMojo = new DummyTychoMojo();
        IncrementalCompileExecutionListener listener = new IncrementalCompileExecutionListener(cache);
        listener.configureMojoForIncrementalCompile(tychoMojo, true);

        assertThat(tychoMojo.compilerId).isEqualTo("jdt");
        assertThat(tychoMojo.useIncrementalCompilation).isFalse();
        assertThat(CrossRealmCompilerBridge.isAlreadyWrapped(tychoMojo.compilerManager)).isTrue();

        // Also verify standard maven-compiler-plugin keeps compilerId="javac" and intercepts via proxy
        DummyTychoMojo mavenMojo = new DummyTychoMojo();
        mavenMojo.compilerId = "javac";
        listener.configureMojoForIncrementalCompile(mavenMojo, false);
        assertThat(mavenMojo.compilerId).isEqualTo("javac");
        assertThat(CrossRealmCompilerBridge.isAlreadyWrapped(mavenMojo.compilerManager)).isTrue();
        Compiler incJavac = mavenMojo.compilerManager.getCompiler("javac");
        assertThat(incJavac.canUpdateTarget(new CompilerConfiguration())).isTrue();
        assertThat(incJavac.getOutputFileEnding(new CompilerConfiguration()))
                .isEqualTo(CrossRealmCompilerBridge.INCREMENTAL_MARKER_SUFFIX);
        assertThat(incJavac.createCommandLine(new CompilerConfiguration())).contains("-sourcepath");

        Compiler resolvedCompiler = tychoMojo.compilerManager.getCompiler("jdt");
        assertThat(resolvedCompiler.canUpdateTarget(new CompilerConfiguration())).isTrue();
        assertThat(resolvedCompiler.getOutputFileEnding(new CompilerConfiguration()))
                .isEqualTo(CrossRealmCompilerBridge.INCREMENTAL_MARKER_SUFFIX);

        // Execute incremental build through the proxied Tycho compiler (with OSGi MANIFEST.MF)
        Path bundleDir = tempDir.resolve("tycho-bundle");
        Path manifestFile = bundleDir.resolve("META-INF/MANIFEST.MF");
        Files.createDirectories(manifestFile.getParent());
        Files.writeString(manifestFile, "Manifest-Version: 1.0\nBundle-SymbolicName: org.example.bundle\n");

        Path srcDir = bundleDir.resolve("src");
        Path outDir = bundleDir.resolve("target/classes");
        Path activatorFile = writeJava(srcDir, "org/example/Activator.java", """
                package org.example;
                public class Activator {
                    public String start() { return "started"; }
                }
                """);
        Path serviceFile = writeJava(srcDir, "org/example/BundleService.java", """
                package org.example;
                public class BundleService {
                    public String status(Activator a) { return a.start(); }
                }
                """);

        CompilerConfiguration tychoCfg = createConfig(srcDir, outDir, List.of(activatorFile, serviceFile));
        tychoCfg.setClasspathEntries(List.of(tychoCpEntry));

        assertThat(resolvedCompiler.performCompile(tychoCfg).isSuccess()).isTrue();
        assertThat(tychoMojo.lastCompiledSubset).containsExactlyInAnyOrder(activatorFile, serviceFile);

        // Private body change in Activator.java -> only Activator.java is compiled via delegate!
        writeJava(srcDir, "org/example/Activator.java", """
                package org.example;
                public class Activator {
                    public String start() { return internalMsg(); }
                    private String internalMsg() { return "started-v2"; }
                }
                """);
        assertThat(resolvedCompiler.performCompile(tychoCfg).isSuccess()).isTrue();
        assertThat(tychoMojo.lastCompiledSubset).containsExactly(activatorFile);

        // Modifying META-INF/MANIFEST.MF invalidates optionsFingerprint and triggers full bundle rebuild
        Files.writeString(manifestFile,
                "Manifest-Version: 1.0\nBundle-SymbolicName: org.example.bundle\nExport-Package: org.example\n");
        assertThat(resolvedCompiler.performCompile(tychoCfg).isSuccess()).isTrue();
        assertThat(tychoMojo.lastCompiledSubset).containsExactlyInAnyOrder(activatorFile, serviceFile);
    }

    @Test
    void scenario10_abstractMethodTransitiveHierarchyCrossModule() throws Exception {
        ReactorAbiCache sharedReactorCache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(sharedReactorCache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        // Module 1 (api): InterfaceA and AbstractB implements InterfaceA
        Path apiDir = tempDir.resolve("transitive-hierarchy/module-api");
        Path apiSrc = apiDir.resolve("src/main/java");
        Path apiOut = apiDir.resolve("target/classes");
        Path ifaceFile = writeJava(apiSrc, "com/api/InterfaceA.java", """
                package com.api;
                public interface InterfaceA {
                    String m1();
                }
                """);
        Path abstractFile = writeJava(apiSrc, "com/api/AbstractB.java", """
                package com.api;
                public abstract class AbstractB implements InterfaceA {
                }
                """);

        // Module 2 (impl): ConcreteC extends AbstractB (never mentions InterfaceA directly!)
        Path implDir = tempDir.resolve("transitive-hierarchy/module-impl");
        Path implSrc = implDir.resolve("src/main/java");
        Path implOut = implDir.resolve("target/classes");
        Path concreteFile = writeJava(implSrc, "com/impl/ConcreteC.java", """
                package com.impl;
                import com.api.AbstractB;
                public class ConcreteC extends AbstractB {
                    @Override
                    public String m1() { return "ok"; }
                }
                """);

        CompilerConfiguration apiCfg = createConfig(apiSrc, apiOut, List.of(ifaceFile, abstractFile));
        CompilerConfiguration implCfg = createConfig(implSrc, implOut, List.of(concreteFile));
        implCfg.setClasspathEntries(List.of(apiOut.toString()));

        assertThat(runCompile(engine, apiCfg, backend).compilerResult().isSuccess()).isTrue();
        assertThat(runCompile(engine, implCfg, backend).compilerResult().isSuccess()).isTrue();

        // Add a new unimplemented abstract method m2() to InterfaceA in module-api
        writeJava(apiSrc, "com/api/InterfaceA.java", """
                package com.api;
                public interface InterfaceA {
                    String m1();
                    String m2();
                }
                """);

        // module-api compiles cleanly because AbstractB is abstract
        assertThat(runCompile(engine, apiCfg, backend).compilerResult().isSuccess()).isTrue();

        // module-impl MUST detect that ConcreteC's transitive supertype InterfaceA changed its ABI
        // and recompile ConcreteC.java (which now fails to compile because m2() is not implemented!)
        var implRebuild = runCompile(engine, implCfg, backend);
        assertThat(implRebuild.compilerResult().isSuccess()).isFalse();
        assertThat(implRebuild.compiledSources()).containsExactly(concreteFile);
    }

    @Test
    void scenario11_typeShadowingAndWildcardImportCollision() throws Exception {
        ReactorAbiCache cache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(cache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        Path moduleDir = tempDir.resolve("type-shadowing");
        Path srcDir = moduleDir.resolve("src/main/java");
        Path outDir = moduleDir.resolve("target/classes");

        Path pkg2Helper = writeJava(srcDir, "com/pkg2/Helper.java", """
                package com.pkg2;
                public class Helper {
                    public static String origin() { return "from-pkg2"; }
                }
                """);
        Path pkg1Client = writeJava(srcDir, "com/pkg1/Client.java", """
                package com.pkg1;
                import com.pkg2.*;
                public class Client {
                    public String describe() {
                        return Helper.origin();
                    }
                }
                """);
        Path pkg1NoWildcard = writeJava(srcDir, "com/pkg1/NoWildcard.java", """
                package com.pkg1;
                public class NoWildcard {
                    public int id() { return 1; }
                }
                """);

        CompilerConfiguration cfg1 = createConfig(srcDir, outDir, List.of(pkg2Helper, pkg1Client, pkg1NoWildcard));
        assertThat(runCompile(engine, cfg1, backend).compilerResult().isSuccess()).isTrue();
        assertThat(invokeDescribe(outDir, "com.pkg1.Client")).isEqualTo("from-pkg2");

        // Now add a brand-new class com.pkg1.Helper in com.pkg1!
        // Per JLS §6.5.2, same-package com.pkg1.Helper shadows wildcard-imported com.pkg2.Helper in Client.java.
        Path pkg1Helper = writeJava(srcDir, "com/pkg1/Helper.java", """
                package com.pkg1;
                public class Helper {
                    public static String origin() { return "shadowed-by-pkg1"; }
                }
                """);

        CompilerConfiguration cfg2 = createConfig(
                srcDir, outDir, List.of(pkg2Helper, pkg1Client, pkg1NoWildcard, pkg1Helper));
        var r2 = runCompile(engine, cfg2, backend);
        assertThat(r2.compilerResult().isSuccess()).isTrue();
        // Round 1 compiled the new pkg1Helper; Round 2 automatically recompiled pkg1Client (which uses wildcard imports in pkg1),
        // while leaving pkg1NoWildcard and pkg2Helper untouched!
        assertThat(r2.compiledSources()).containsExactlyInAnyOrder(pkg1Helper, pkg1Client);
        assertThat(invokeDescribe(outDir, "com.pkg1.Client")).isEqualTo("shadowed-by-pkg1");
    }

    @Test
    void scenario12_secondaryTopLevelTypesInSingleSourceFile() throws Exception {
        ReactorAbiCache cache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(cache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        Path moduleDir = tempDir.resolve("secondary-types");
        Path srcDir = moduleDir.resolve("src/main/java");
        Path outDir = moduleDir.resolve("target/classes");

        Path containerFile = writeJava(srcDir, "com/example/Container.java", """
                package com.example;
                public class Container {}
                class SecondarySupport {
                    static String tag() { return "sec-v1"; }
                }
                """);
        Path consumerFile = writeJava(srcDir, "com/example/Consumer.java", """
                package com.example;
                public class Consumer {
                    public String use() { return SecondarySupport.tag(); }
                }
                """);

        CompilerConfiguration cfg = createConfig(srcDir, outDir, List.of(containerFile, consumerFile));
        assertThat(runCompile(engine, cfg, backend).compilerResult().isSuccess()).isTrue();
        assertThat(outDir.resolve("com/example/SecondarySupport.class")).exists();

        // Remove SecondarySupport from Container.java -> SecondarySupport.class is deleted and Consumer fails to compile!
        writeJava(srcDir, "com/example/Container.java", """
                package com.example;
                public class Container {}
                """);

        var r2 = runCompile(engine, cfg, backend);
        assertThat(r2.compilerResult().isSuccess()).isFalse();
        assertThat(outDir.resolve("com/example/SecondarySupport.class")).doesNotExist();
        assertThat(r2.compiledSources()).containsExactlyInAnyOrder(containerFile, consumerFile);
    }

    @Test
    void scenario13_packageInfoWithoutClassOutputDoesNotTriggerInfiniteRecompiles() throws Exception {
        ReactorAbiCache cache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(cache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        Path moduleDir = tempDir.resolve("pkginfo-noop");
        Path srcDir = moduleDir.resolve("src/main/java");
        Path outDir = moduleDir.resolve("target/classes");

        // A package-info.java without runtime/class retention annotations does not emit package-info.class
        Path pkgInfoFile = writeJava(srcDir, "com/example/package-info.java", """
                /**
                 * Package documentation only.
                 */
                package com.example;
                """);
        Path serviceFile = writeJava(srcDir, "com/example/PkgService.java", """
                package com.example;
                public class PkgService {}
                """);

        CompilerConfiguration cfg = createConfig(srcDir, outDir, List.of(pkgInfoFile, serviceFile));
        var r1 = runCompile(engine, cfg, backend);
        assertThat(r1.compilerResult().isSuccess()).isTrue();
        assertThat(r1.compiledSources()).containsExactlyInAnyOrder(pkgInfoFile, serviceFile);

        // Second run must be a true No-Op (0 files compiled) even though package-info.class does not exist on disk!
        var r2 = runCompile(engine, cfg, backend);
        assertThat(r2.compilerResult().isSuccess()).isTrue();
        assertThat(r2.compiledSources()).isEmpty();
    }

    @Test
    void scenario14_sealedHierarchyAndExhaustiveSwitchRecompilation() throws Exception {
        ReactorAbiCache cache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(cache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        Path moduleDir = tempDir.resolve("sealed-hierarchy");
        Path srcDir = moduleDir.resolve("src/main/java");
        Path outDir = moduleDir.resolve("target/classes");

        Path shapeFile = writeJava(srcDir, "com/example/Shape.java", """
                package com.example;
                public sealed interface Shape permits Circle, Square {}
                """);
        Path circleFile = writeJava(srcDir, "com/example/Circle.java", """
                package com.example;
                public final class Circle implements Shape {}
                """);
        Path squareFile = writeJava(srcDir, "com/example/Square.java", """
                package com.example;
                public final class Square implements Shape {}
                """);
        Path evalFile = writeJava(srcDir, "com/example/ShapeEvaluator.java", """
                package com.example;
                public class ShapeEvaluator {
                    public int sides(Shape s) {
                        if (s instanceof Circle) return 0;
                        if (s instanceof Square) return 4;
                        return -1;
                    }
                }
                """);

        CompilerConfiguration cfg1 = createConfig(
                srcDir, outDir, List.of(shapeFile, circleFile, squareFile, evalFile));
        assertThat(runCompile(engine, cfg1, backend).compilerResult().isSuccess()).isTrue();

        // Add a new permitted subclass Triangle to sealed interface Shape
        Path triangleFile = writeJava(srcDir, "com/example/Triangle.java", """
                package com.example;
                public final class Triangle implements Shape {}
                """);
        writeJava(srcDir, "com/example/Shape.java", """
                package com.example;
                public sealed interface Shape permits Circle, Square, Triangle {}
                """);

        CompilerConfiguration cfg2 = createConfig(
                srcDir, outDir, List.of(shapeFile, circleFile, squareFile, triangleFile, evalFile));
        var r2 = runCompile(engine, cfg2, backend);
        assertThat(r2.compilerResult().isSuccess()).isTrue();
        // Shape's PermittedSubclasses changed its hierarchyHash & abiHash, so subtypes and ShapeEvaluator are recompiled!
        assertThat(r2.compiledSources()).contains(shapeFile, triangleFile, evalFile);
    }

    @Test
    void scenario15_aggregatingAnnotationProcessorRemovesDeletedClassFromGeneratedResourceList() throws Exception {
        ReactorAbiCache cache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(cache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        Path moduleDir = tempDir.resolve("quarkus-aggregating-apt");
        Path srcDir = moduleDir.resolve("src/main/java");
        Path outDir = moduleDir.resolve("target/classes");

        Path annFile = writeJava(srcDir, "com/example/BuildStep.java", """
                package com.example;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                @Retention(RetentionPolicy.RUNTIME)
                public @interface BuildStep {}
                """);
        Path stepA = writeJava(srcDir, "com/example/StepA.java", """
                package com.example;
                @BuildStep
                public class StepA {}
                """);
        Path stepB = writeJava(srcDir, "com/example/StepB.java", """
                package com.example;
                @BuildStep
                public class StepB {}
                """);

        CompilerConfiguration cfg1 = createConfig(srcDir, outDir, List.of(annFile, stepA, stepB));
        cfg1.setAnnotationProcessors(new String[] { TestQuarkusBuildStepProcessor.class.getName() });

        // 1. Initial build writes META-INF/quarkus-build-steps.list with both StepA and StepB
        var r1 = runCompile(engine, cfg1, backend);
        assertThat(r1.compilerResult().isSuccess()).isTrue();
        Path stepsList = outDir.resolve("META-INF/quarkus-build-steps.list");
        assertThat(stepsList).exists();
        assertThat(Files.readAllLines(stepsList))
                .containsExactly("com.example.StepA", "com.example.StepB");

        // 2. Immediate No-Op build -> 0 files compiled even with aggregating APT!
        var r2 = runCompile(engine, cfg1, backend);
        assertThat(r2.compilerResult().isSuccess()).isTrue();
        assertThat(r2.fullRebuild()).isFalse();
        assertThat(r2.compiledSources()).isEmpty();

        // 3. Delete StepB.java -> engine detects aggregating APT + deletion, runs full processor pass,
        // and removes stale com.example.StepB from META-INF/quarkus-build-steps.list!
        Files.delete(stepB);
        CompilerConfiguration cfg3 = createConfig(srcDir, outDir, List.of(annFile, stepA));
        cfg3.setAnnotationProcessors(new String[] { TestQuarkusBuildStepProcessor.class.getName() });

        var r3 = runCompile(engine, cfg3, backend);
        assertThat(r3.compilerResult().isSuccess()).isTrue();
        assertThat(r3.fullRebuild()).isTrue();
        assertThat(outDir.resolve("com/example/StepB.class")).doesNotExist();
        assertThat(Files.readAllLines(stepsList)).containsExactly("com.example.StepA");
    }

    @Test
    void scenario16_externallyOverwrittenClassFileDetectedEvenWhenSourceRevertedToOriginalContent() throws Exception {
        ReactorAbiCache cache = new ReactorAbiCache();
        IncrementalCompilationEngine engine = new IncrementalCompilationEngine(cache);
        JavacBackendCompiler backend = new JavacBackendCompiler();

        Path moduleDir = tempDir.resolve("ecj-stub-tamper");
        Path srcDir = moduleDir.resolve("src/main/java");
        Path outDir = moduleDir.resolve("target/classes");

        String validSource = """
                package com.example;
                public class Greeter {
                    public String describe() { return "valid-maven-class"; }
                }
                """;
        Path greeterFile = writeJava(srcDir, "com/example/Greeter.java", validSource);
        CompilerConfiguration cfg = createConfig(srcDir, outDir, List.of(greeterFile));

        // 1. Initial Maven build produces valid Greeter.class
        assertThat(runCompile(engine, cfg, backend).compilerResult().isSuccess()).isTrue();
        assertThat(invokeDescribe(outDir, "com.example.Greeter")).isEqualTo("valid-maven-class");

        // 2. Simulate Eclipse/m2e ECJ overwriting Greeter.class with an error-stub class,
        // while Greeter.java is reverted externally back to its exact original v1 content (newer mtime)!
        Path greeterClass = outDir.resolve("com/example/Greeter.class");
        Files.write(greeterClass, new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 61 });
        Files.setLastModifiedTime(greeterClass, FileTime.from(Instant.now().plusSeconds(5)));
        Files.writeString(greeterFile, validSource, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(greeterFile, FileTime.from(Instant.now().plusSeconds(10)));

        // 3. Next Maven incremental build MUST detect the tampered Greeter.class and recompile Greeter.java!
        var r2 = runCompile(engine, cfg, backend);
        assertThat(r2.compilerResult().isSuccess()).isTrue();
        assertThat(r2.compiledSources()).containsExactly(greeterFile);
        assertThat(invokeDescribe(outDir, "com.example.Greeter")).isEqualTo("valid-maven-class");
    }

    @Test
    void scenario17_extensionClassesHaveZeroStaticPlexusCompilerImportsForCoreExtensionRealm() throws Exception {
        ClassFileAnalyzer analyzer = new ClassFileAnalyzer();
        Path classesDir = Path.of("target/classes");

        List<Path> coreExtensionClasses = List.of(
                classesDir.resolve("eu/munier/maven/incremental/plugin/IncrementalCompileExecutionListener.class"),
                classesDir.resolve("eu/munier/maven/incremental/plugin/CrossRealmCompilerBridge.class"),
                classesDir.resolve("eu/munier/maven/incremental/engine/IncrementalCompilationEngine.class"),
                classesDir.resolve("eu/munier/maven/incremental/engine/JavacBackendCompiler.class"),
                classesDir.resolve("eu/munier/maven/incremental/engine/PlexusDelegatingBackendCompiler.class"));

        for (Path clsFile : coreExtensionClasses) {
            assertThat(clsFile).exists();
            ClassFileSummary summary = analyzer.analyze(clsFile);
            for (String ref : summary.getReferencedClassNames()) {
                assertThat(ref)
                        .as("Core extension class %s must not statically reference plexus-compiler class %s",
                                clsFile.getFileName(), ref)
                        .doesNotStartWith("org.codehaus.plexus.compiler.");
            }
        }
    }

    @SupportedAnnotationTypes("com.example.BuildStep")
    @SupportedSourceVersion(SourceVersion.RELEASE_17)
    public static class TestQuarkusBuildStepProcessor extends AbstractProcessor {
        private final Set<String> discoveredSteps = new TreeSet<>();

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            for (TypeElement ann : annotations) {
                for (Element el : roundEnv.getElementsAnnotatedWith(ann)) {
                    if (el instanceof TypeElement te) {
                        discoveredSteps.add(te.getQualifiedName().toString());
                    }
                }
            }
            if (roundEnv.processingOver() && !discoveredSteps.isEmpty()) {
                try {
                    FileObject resource = processingEnv.getFiler().createResource(
                            StandardLocation.CLASS_OUTPUT, "", "META-INF/quarkus-build-steps.list");
                    try (Writer w = resource.openWriter()) {
                        for (String step : discoveredSteps) {
                            w.write(step);
                            w.write('\n');
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return true;
        }
    }

    @SupportedAnnotationTypes("com.example.GenerateBuilder")
    @SupportedSourceVersion(SourceVersion.RELEASE_17)
    public static class TestBuilderAnnotationProcessor extends AbstractProcessor {
        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            for (TypeElement ann : annotations) {
                for (Element element : roundEnv.getElementsAnnotatedWith(ann)) {
                    if (element instanceof TypeElement typeElement) {
                        String suffix = "Generated";
                        for (var mirror : element.getAnnotationMirrors()) {
                            for (var entry : mirror.getElementValues().entrySet()) {
                                if ("suffix".equals(entry.getKey().getSimpleName().toString())) {
                                    suffix = entry.getValue().getValue().toString();
                                }
                            }
                        }
                        String pkg = processingEnv.getElementUtils().getPackageOf(typeElement).getQualifiedName().toString();
                        String genClassName = typeElement.getSimpleName() + suffix;
                        String fqn = pkg.isEmpty() ? genClassName : pkg + "." + genClassName;
                        try {
                            JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn, typeElement);
                            try (Writer w = file.openWriter()) {
                                if (!pkg.isEmpty()) {
                                    w.write("package " + pkg + ";\n");
                                }
                                w.write("public class " + genClassName + " {\n");
                                w.write("    public " + typeElement.getSimpleName() + " build() {\n");
                                w.write("        return new " + typeElement.getSimpleName() + "();\n");
                                w.write("    }\n");
                                w.write("}\n");
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
            return true;
        }
    }

    private static class DummyTychoMojo extends AbstractMojo {
        String compilerId = "jdt";
        boolean useIncrementalCompilation = true;
        Set<Path> lastCompiledSubset = new LinkedHashSet<>();
        CompilerManager compilerManager = id -> new org.codehaus.plexus.compiler.AbstractCompiler(
                org.codehaus.plexus.compiler.CompilerOutputStyle.ONE_OUTPUT_FILE_PER_INPUT_FILE,
                ".java",
                ".class",
                null) {
            @Override
            public String getCompilerId() {
                return id;
            }

            @Override
            public CompilerResult performCompile(CompilerConfiguration config)
                    throws org.codehaus.plexus.compiler.CompilerException {
                Set<Path> sources = new LinkedHashSet<>();
                for (File f : config.getSourceFiles()) {
                    sources.add(f.toPath().toAbsolutePath().normalize());
                }
                lastCompiledSubset = new LinkedHashSet<>(sources);
                try {
                    var res = new JavacBackendCompiler().compileSubset(
                            toRequest(config), sources, Path.of(config.getOutputLocation()));
                    return new CompilerResult(res.success(), List.of());
                } catch (IncrementalCompilationException e) {
                    throw new org.codehaus.plexus.compiler.CompilerException(e.getMessage(), e);
                }
            }

            @Override
            public String[] createCommandLine(CompilerConfiguration config) {
                return new String[0];
            }
        };

        @Override
        public void execute() {
        }
    }

    private static IncrementalCompilationEngine.CompilationExecutionReport runCompile(
            IncrementalCompilationEngine engine,
            CompilerConfiguration cfg,
            BackendCompiler backend) throws IncrementalCompilationException {
        return engine.compile(toRequest(cfg), backend);
    }

    private static CompileRequest toRequest(CompilerConfiguration cfg) {
        return CompileRequest.builder()
                .outputLocation(cfg.getOutputLocation())
                .sourceFiles(cfg.getSourceFiles())
                .sourceLocations(cfg.getSourceLocations())
                .classpathEntries(cfg.getClasspathEntries())
                .modulepathEntries(cfg.getModulepathEntries())
                .releaseVersion(cfg.getReleaseVersion())
                .sourceVersion(cfg.getSourceVersion())
                .targetVersion(cfg.getTargetVersion())
                .sourceEncoding(cfg.getSourceEncoding())
                .parameters(cfg.isParameters())
                .debug(cfg.isDebug())
                .debugLevel(cfg.getDebugLevel())
                .showDeprecation(cfg.isShowDeprecation())
                .showWarnings(cfg.isShowWarnings())
                .proc(cfg.getProc())
                .generatedSourcesDirectory(cfg.getGeneratedSourcesDirectory())
                .processorPathEntries(cfg.getProcessorPathEntries())
                .annotationProcessors(cfg.getAnnotationProcessors())
                .customCompilerArgumentsEntries(cfg.getCustomCompilerArgumentsEntries() != null
                        ? new ArrayList<>(cfg.getCustomCompilerArgumentsEntries())
                        : List.of())
                .build();
    }

    private static Path writeJava(Path srcRoot, String relativePath, String content) throws IOException {
        Path file = srcRoot.resolve(relativePath).toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static CompilerConfiguration createConfig(Path srcDir, Path outDir, List<Path> sourceFiles) {
        CompilerConfiguration cfg = new CompilerConfiguration();
        cfg.setOutputLocation(outDir.toAbsolutePath().normalize().toString());
        cfg.addSourceLocation(srcDir.toAbsolutePath().normalize().toString());
        Set<File> files = new LinkedHashSet<>();
        for (Path p : sourceFiles) {
            files.add(p.toAbsolutePath().normalize().toFile());
        }
        cfg.setSourceFiles(files);
        cfg.setReleaseVersion("17");
        cfg.setDebug(true);
        return cfg;
    }

    private static String invokeDescribe(Path classesDir, String className) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() })) {
            Class<?> cls = Class.forName(className, true, loader);
            Object instance = cls.getDeclaredConstructor().newInstance();
            Method m = cls.getMethod("describe");
            return (String) m.invoke(instance);
        }
    }
}
