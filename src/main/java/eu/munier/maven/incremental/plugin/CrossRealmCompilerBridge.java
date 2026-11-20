package eu.munier.maven.incremental.plugin;

import eu.munier.maven.incremental.bytecode.ClassFileSummary;
import eu.munier.maven.incremental.engine.CompileDiagnostic;
import eu.munier.maven.incremental.engine.CompileOutcome;
import eu.munier.maven.incremental.engine.CompileRequest;
import eu.munier.maven.incremental.engine.IncrementalCompilationEngine;
import eu.munier.maven.incremental.engine.IncrementalCompilationException;
import eu.munier.maven.incremental.engine.JavacBackendCompiler;
import eu.munier.maven.incremental.engine.PlexusDelegatingBackendCompiler;
import eu.munier.maven.incremental.state.ClasspathEntryNormalizer;
import eu.munier.maven.incremental.state.IncrementalBuildState;
import eu.munier.maven.incremental.state.ReactorAbiCache;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ClassRealm-agnostic dynamic proxy bridge between Maven's Core Extension realm (or a plugin realm)
 * and the target plugin's {@code plexus-compiler-api} / {@code plexus-compiler-manager} interfaces.
 *
 * <p>This class intentionally has zero static compile-time references to {@code org.codehaus.plexus.compiler.*},
 * allowing the extension to work seamlessly from {@code .mvn/extensions.xml} as well as across
 * isolated {@code maven-compiler-plugin} and {@code tycho-compiler-plugin} ClassRealms.</p>
 */
public final class CrossRealmCompilerBridge {

    private static final Logger LOG = LoggerFactory.getLogger(CrossRealmCompilerBridge.class);

    public static final String INCREMENTAL_MARKER_SUFFIX = ".inc-marker";
    public static final String INCREMENTAL_JAVAC_COMPILER_ID = "incremental-javac";

    private static final String COMPILER_MANAGER_INTERFACE = "org.codehaus.plexus.compiler.manager.CompilerManager";
    private static final String COMPILER_INTERFACE = "org.codehaus.plexus.compiler.Compiler";
    private static final String COMPILER_OUTPUT_STYLE_CLASS = "org.codehaus.plexus.compiler.CompilerOutputStyle";
    private static final String COMPILER_RESULT_CLASS = "org.codehaus.plexus.compiler.CompilerResult";
    private static final String COMPILER_MESSAGE_CLASS = "org.codehaus.plexus.compiler.CompilerMessage";
    private static final String COMPILER_MESSAGE_KIND_CLASS = "org.codehaus.plexus.compiler.CompilerMessage$Kind";
    private static final String COMPILER_EXCEPTION_CLASS = "org.codehaus.plexus.compiler.CompilerException";

    private CrossRealmCompilerBridge() {
    }

    public static boolean isAlreadyWrapped(Object candidate) {
        return candidate != null
                && Proxy.isProxyClass(candidate.getClass())
                && Proxy.getInvocationHandler(candidate) instanceof CompilerManagerProxyHandler;
    }

    /**
     * Wraps a plugin's {@code CompilerManager} instance in a JDK dynamic proxy loaded against
     * the plugin's own {@link ClassLoader}.
     */
    public static Object wrapCompilerManager(
            Object existingManager,
            ClassLoader pluginClassLoader,
            ReactorAbiCache reactorAbiCache) {
        Objects.requireNonNull(existingManager, "existingManager");
        if (isAlreadyWrapped(existingManager)) {
            return existingManager;
        }
        ClassLoader loader = pluginClassLoader != null
                ? pluginClassLoader
                : existingManager.getClass().getClassLoader();
        try {
            Class<?> managerIface = loader.loadClass(COMPILER_MANAGER_INTERFACE);
            Class<?> compilerIface = loader.loadClass(COMPILER_INTERFACE);
            if (!managerIface.isInstance(existingManager)) {
                LOG.warn("[IncrementalCompiler] Object {} does not implement {} in ClassLoader {}",
                        existingManager.getClass().getName(), COMPILER_MANAGER_INTERFACE, loader);
                return existingManager;
            }
            return Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] { managerIface },
                    new CompilerManagerProxyHandler(existingManager, loader, compilerIface, reactorAbiCache));
        } catch (ClassNotFoundException e) {
            LOG.warn("[IncrementalCompiler] Could not load {} from plugin ClassLoader {}: {}",
                    COMPILER_MANAGER_INTERFACE, loader, e.getMessage());
            return existingManager;
        }
    }

    private static final class CompilerManagerProxyHandler implements InvocationHandler {

        private final Object delegateManager;
        private final ClassLoader pluginClassLoader;
        private final Class<?> compilerInterface;
        private final ReactorAbiCache reactorAbiCache;

        CompilerManagerProxyHandler(
                Object delegateManager,
                ClassLoader pluginClassLoader,
                Class<?> compilerInterface,
                ReactorAbiCache reactorAbiCache) {
            this.delegateManager = delegateManager;
            this.pluginClassLoader = pluginClassLoader;
            this.compilerInterface = compilerInterface;
            this.reactorAbiCache = reactorAbiCache;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("getCompiler".equals(name) && args != null && args.length == 1 && args[0] instanceof String compilerId) {
                if ("javac".equals(compilerId) || INCREMENTAL_JAVAC_COMPILER_ID.equals(compilerId)) {
                    return Proxy.newProxyInstance(
                            pluginClassLoader,
                            new Class<?>[] { compilerInterface },
                            new IncrementalCompilerProxyHandler(
                                    compilerId, null, pluginClassLoader, reactorAbiCache));
                }
                Object originalCompiler = invokeTarget(delegateManager, method, args);
                if (originalCompiler == null) {
                    return null;
                }
                if (Proxy.isProxyClass(originalCompiler.getClass())
                        && Proxy.getInvocationHandler(originalCompiler) instanceof IncrementalCompilerProxyHandler) {
                    return originalCompiler;
                }
                return Proxy.newProxyInstance(
                        pluginClassLoader,
                        new Class<?>[] { compilerInterface },
                        new IncrementalCompilerProxyHandler(
                                compilerId, originalCompiler, pluginClassLoader, reactorAbiCache));
            }
            return invokeObjectOrDelegate(proxy, delegateManager, method, args);
        }
    }

    private static final class IncrementalCompilerProxyHandler implements InvocationHandler {

        private final String compilerId;
        private final Object delegateCompiler; // null for native JSR-199 JavacBackendCompiler
        private final ClassLoader pluginClassLoader;
        private final ReactorAbiCache reactorAbiCache;

        IncrementalCompilerProxyHandler(
                String compilerId,
                Object delegateCompiler,
                ClassLoader pluginClassLoader,
                ReactorAbiCache reactorAbiCache) {
            this.compilerId = compilerId;
            this.delegateCompiler = delegateCompiler;
            this.pluginClassLoader = pluginClassLoader;
            this.reactorAbiCache = reactorAbiCache;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            int paramCount = args != null ? args.length : 0;

            return switch (name) {
                case "getCompilerId" -> delegateCompiler != null
                        ? invokeTarget(delegateCompiler, method, args)
                        : compilerId;
                case "canUpdateTarget" -> true;
                case "getCompilerOutputStyle" -> resolveOneOutputPerInputStyle(pluginClassLoader);
                case "getInputFileEnding" -> {
                    if (delegateCompiler != null) {
                        Object ending = invokeTarget(delegateCompiler, method, args);
                        yield ending != null ? ending : ".java";
                    }
                    yield ".java";
                }
                case "getOutputFileEnding" -> INCREMENTAL_MARKER_SUFFIX;
                case "getOutputFile" -> delegateCompiler != null
                        ? invokeTarget(delegateCompiler, method, args)
                        : null;
                case "createCommandLine" -> {
                    if (delegateCompiler != null) {
                        yield invokeTarget(delegateCompiler, method, args);
                    }
                    yield buildJavacCommandLine(paramCount == 1 ? args[0] : null);
                }
                case "performCompile" -> performIncrementalCompile(paramCount == 1 ? args[0] : null);
                default -> {
                    if (delegateCompiler != null) {
                        yield invokeObjectOrDelegate(proxy, delegateCompiler, method, args);
                    }
                    yield handleObjectMethod(proxy, method, args);
                }
            };
        }

        private String[] buildJavacCommandLine(Object plexusConfig) throws Throwable {
            try {
                CompileRequest req = toCompileRequest(plexusConfig);
                Path outDir = req.getOutputLocation() != null && !req.getOutputLocation().isBlank()
                        ? Path.of(req.getOutputLocation())
                        : Path.of("target/classes");
                List<String> opts = JavacBackendCompiler.buildJavacOptions(req, outDir);
                return opts.toArray(String[]::new);
            } catch (Exception e) {
                throw newPlexusCompilerException(pluginClassLoader, "Failed to build javac command line", e);
            }
        }

        private Object performIncrementalCompile(Object plexusConfig) throws Throwable {
            CompileRequest request = toCompileRequest(plexusConfig);
            IncrementalCompilationEngine engine = new IncrementalCompilationEngine(reactorAbiCache);
            try {
                IncrementalCompilationEngine.CompilationExecutionReport report;
                if (delegateCompiler == null) {
                    report = engine.compile(request, new JavacBackendCompiler());
                } else {
                    Set<String> knownConstantOwners = collectKnownConstantOwners(request, reactorAbiCache);
                    PlexusDelegatingBackendCompiler.SubsetCompilerDelegate subsetDelegate =
                            (subsetFiles, effectiveClasspath) -> invokeDelegateCompiler(
                                    delegateCompiler, plexusConfig, subsetFiles, effectiveClasspath);
                    report = engine.compile(
                            request,
                            new PlexusDelegatingBackendCompiler(subsetDelegate, knownConstantOwners));
                }
                return toPlexusCompilerResult(pluginClassLoader, report.compilerResult());
            } catch (IncrementalCompilationException e) {
                throw newPlexusCompilerException(pluginClassLoader, e.getMessage(), e);
            }
        }
    }

    private static CompileOutcome invokeDelegateCompiler(
            Object delegateCompiler,
            Object plexusConfig,
            Set<File> subsetSourceFiles,
            List<String> effectiveClasspath) throws IncrementalCompilationException {
        Object originalSources = invokeNoArg(plexusConfig, "getSourceFiles");
        Object originalClasspathRaw = invokeNoArg(plexusConfig, "getClasspathEntries");
        List<String> originalClasspathCopy = originalClasspathRaw instanceof List<?> list
                ? new ArrayList<>(toStringList(list))
                : new ArrayList<>();
        try {
            invokeOneArg(plexusConfig, "setSourceFiles", Set.class, subsetSourceFiles);
            invokeOneArg(plexusConfig, "setClasspathEntries", List.class, effectiveClasspath);

            Method performCompile = findMethod(delegateCompiler.getClass(), "performCompile", 1);
            if (performCompile == null) {
                throw new IncrementalCompilationException(
                        "Delegate compiler has no performCompile(CompilerConfiguration) method: "
                                + delegateCompiler.getClass().getName());
            }
            Object plexusResult = performCompile.invoke(delegateCompiler, plexusConfig);
            return fromPlexusCompilerResult(plexusResult);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getTargetException();
            throw new IncrementalCompilationException(
                    "Delegate compiler failed: " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException e) {
            throw new IncrementalCompilationException(
                    "Failed to invoke delegate compiler via reflection", e);
        } finally {
            if (originalSources instanceof Set<?>) {
                invokeOneArgQuietly(plexusConfig, "setSourceFiles", Set.class, originalSources);
            }
            invokeOneArgQuietly(plexusConfig, "setClasspathEntries", List.class, originalClasspathCopy);
        }
    }

    static CompileRequest toCompileRequest(Object plexusConfig) {
        if (plexusConfig == null) {
            return CompileRequest.builder().build();
        }
        CompileRequest.Builder b = CompileRequest.builder()
                .outputLocation(asString(invokeNoArg(plexusConfig, "getOutputLocation")))
                .sourceFiles(asFileSet(invokeNoArg(plexusConfig, "getSourceFiles")))
                .sourceLocations(toStringList(invokeNoArg(plexusConfig, "getSourceLocations")))
                .classpathEntries(toStringList(invokeNoArg(plexusConfig, "getClasspathEntries")))
                .modulepathEntries(toStringList(invokeNoArg(plexusConfig, "getModulepathEntries")))
                .releaseVersion(asString(invokeNoArg(plexusConfig, "getReleaseVersion")))
                .sourceVersion(asString(invokeNoArg(plexusConfig, "getSourceVersion")))
                .targetVersion(asString(invokeNoArg(plexusConfig, "getTargetVersion")))
                .sourceEncoding(asString(invokeNoArg(plexusConfig, "getSourceEncoding")))
                .parameters(asBoolean(invokeNoArg(plexusConfig, "isParameters"), false))
                .debug(asBoolean(invokeNoArg(plexusConfig, "isDebug"), true))
                .debugLevel(asString(invokeNoArg(plexusConfig, "getDebugLevel")))
                .showDeprecation(asBoolean(invokeNoArg(plexusConfig, "isShowDeprecation"), false))
                .showWarnings(asBoolean(invokeNoArg(plexusConfig, "isShowWarnings"), true))
                .proc(asString(invokeNoArg(plexusConfig, "getProc")))
                .generatedSourcesDirectory(asFile(invokeNoArg(plexusConfig, "getGeneratedSourcesDirectory")))
                .processorPathEntries(toStringList(invokeNoArg(plexusConfig, "getProcessorPathEntries")))
                .annotationProcessors(asStringArray(invokeNoArg(plexusConfig, "getAnnotationProcessors")))
                .customCompilerArgumentsEntries(
                        asEntryList(invokeNoArg(plexusConfig, "getCustomCompilerArgumentsEntries")));
        return b.build();
    }

    static Object toPlexusCompilerResult(ClassLoader loader, CompileOutcome outcome) throws Exception {
        Class<?> resultClass = loader.loadClass(COMPILER_RESULT_CLASS);
        Class<?> messageClass = loader.loadClass(COMPILER_MESSAGE_CLASS);
        Class<?> kindClass = loader.loadClass(COMPILER_MESSAGE_KIND_CLASS);

        List<Object> plexusMessages = new ArrayList<>(outcome.messages().size());
        Constructor<?> fullMsgCtor = messageClass.getConstructor(
                String.class, kindClass, int.class, int.class, int.class, int.class, String.class);

        for (CompileDiagnostic d : outcome.messages()) {
            Object kindEnum = resolveEnumConstant(kindClass, d.kind().name(), "OTHER");
            plexusMessages.add(fullMsgCtor.newInstance(
                    d.file(),
                    kindEnum,
                    d.startLine(),
                    d.startColumn(),
                    d.endLine(),
                    d.endColumn(),
                    d.message()));
        }

        Constructor<?> resultCtor = resultClass.getConstructor(boolean.class, List.class);
        return resultCtor.newInstance(outcome.success(), plexusMessages);
    }

    static CompileOutcome fromPlexusCompilerResult(Object plexusResult) {
        if (plexusResult == null) {
            return new CompileOutcome(false, Collections.emptyList());
        }
        boolean success = asBoolean(invokeNoArg(plexusResult, "isSuccess"), false);
        Object rawMsgs = invokeNoArg(plexusResult, "getCompilerMessages");
        List<CompileDiagnostic> diagnostics = new ArrayList<>();
        if (rawMsgs instanceof Collection<?> col) {
            for (Object msgObj : col) {
                if (msgObj == null) {
                    continue;
                }
                String file = asString(invokeNoArg(msgObj, "getFile"));
                Object kindObj = invokeNoArg(msgObj, "getKind");
                CompileDiagnostic.Kind kind = parseKind(kindObj != null ? kindObj.toString() : null);
                int startLine = asInt(invokeNoArg(msgObj, "getStartLine"), 0);
                int startCol = asInt(invokeNoArg(msgObj, "getStartColumn"), 0);
                int endLine = asInt(invokeNoArg(msgObj, "getEndLine"), startLine);
                int endCol = asInt(invokeNoArg(msgObj, "getEndColumn"), startCol);
                String message = asString(invokeNoArg(msgObj, "getMessage"));
                diagnostics.add(new CompileDiagnostic(file, kind, startLine, startCol, endLine, endCol, message));
            }
        }
        return new CompileOutcome(success, diagnostics);
    }

    private static Set<String> collectKnownConstantOwners(CompileRequest config, ReactorAbiCache reactorAbiCache) {
        Set<String> owners = new LinkedHashSet<>();
        if (config.getOutputLocation() != null && !config.getOutputLocation().isBlank()) {
            Path outDir = Path.of(config.getOutputLocation()).toAbsolutePath().normalize();
            Path stateFile = IncrementalCompilationEngine.resolveDefaultStateFile(outDir);
            IncrementalBuildState prevState = IncrementalBuildState.load(stateFile);
            if (prevState != null) {
                for (Map.Entry<String, ClassFileSummary> entry : prevState.getLocalClassSummaries().entrySet()) {
                    if (!entry.getValue().getCompileTimeConstants().isEmpty()) {
                        owners.add(entry.getKey());
                    }
                }
            }
        }
        if (config.getClasspathEntries() != null) {
            for (String rawCp : config.getClasspathEntries()) {
                Path cpPath = ClasspathEntryNormalizer.extractFilesystemPath(rawCp);
                if (cpPath != null) {
                    Map<String, ClassFileSummary> upstream = reactorAbiCache.getModuleSummaries(cpPath);
                    for (Map.Entry<String, ClassFileSummary> entry : upstream.entrySet()) {
                        if (!entry.getValue().getCompileTimeConstants().isEmpty()) {
                            owners.add(entry.getKey());
                        }
                    }
                }
            }
        }
        return owners;
    }

    private static Object resolveOneOutputPerInputStyle(ClassLoader loader) throws Exception {
        Class<?> styleClass = loader.loadClass(COMPILER_OUTPUT_STYLE_CLASS);
        return styleClass.getField("ONE_OUTPUT_FILE_PER_INPUT_FILE").get(null);
    }

    private static Throwable newPlexusCompilerException(ClassLoader loader, String message, Throwable cause) {
        try {
            Class<?> exClass = loader.loadClass(COMPILER_EXCEPTION_CLASS);
            Constructor<?> ctor = exClass.getConstructor(String.class, Throwable.class);
            return (Throwable) ctor.newInstance(message, cause);
        } catch (Exception reflectionFailure) {
            return new RuntimeException(message, cause);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object resolveEnumConstant(Class<?> enumClass, String name, String fallback) {
        try {
            return Enum.valueOf((Class<? extends Enum>) enumClass, name);
        } catch (IllegalArgumentException e) {
            return Enum.valueOf((Class<? extends Enum>) enumClass, fallback);
        }
    }

    private static CompileDiagnostic.Kind parseKind(String raw) {
        if (raw == null) {
            return CompileDiagnostic.Kind.OTHER;
        }
        try {
            return CompileDiagnostic.Kind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return CompileDiagnostic.Kind.OTHER;
        }
    }

    private static Object invokeTarget(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    private static Object invokeObjectOrDelegate(Object proxy, Object delegate, Method method, Object[] args)
            throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return handleObjectMethod(proxy, method, args);
        }
        return invokeTarget(delegate, method, args);
    }

    private static Object handleObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "CrossRealmIncrementalCompilerProxy@" + Integer.toHexString(System.identityHashCode(proxy));
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args != null && args.length == 1 ? args[0] : null);
            default -> null;
        };
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static void invokeOneArg(Object target, String methodName, Class<?> paramType, Object arg)
            throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(methodName, paramType);
        m.setAccessible(true);
        m.invoke(target, arg);
    }

    private static void invokeOneArgQuietly(Object target, String methodName, Class<?> paramType, Object arg) {
        try {
            invokeOneArg(target, methodName, paramType, arg);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static Method findMethod(Class<?> clazz, String name, int paramCount) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    private static File asFile(Object o) {
        return o instanceof File f ? f : null;
    }

    private static boolean asBoolean(Object o, boolean defaultValue) {
        return o instanceof Boolean b ? b : defaultValue;
    }

    private static int asInt(Object o, int defaultValue) {
        return o instanceof Number n ? n.intValue() : defaultValue;
    }

    private static String[] asStringArray(Object o) {
        return o instanceof String[] arr ? arr : null;
    }

    private static Set<File> asFileSet(Object o) {
        if (!(o instanceof Collection<?> col)) {
            return Collections.emptySet();
        }
        Set<File> result = new LinkedHashSet<>();
        for (Object item : col) {
            if (item instanceof File f) {
                result.add(f);
            }
        }
        return result;
    }

    private static List<String> toStringList(Object o) {
        if (!(o instanceof Collection<?> col)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(col.size());
        for (Object item : col) {
            if (item != null) {
                result.add(item.toString());
            }
        }
        return result;
    }

    private static List<Map.Entry<String, String>> asEntryList(Object o) {
        if (!(o instanceof Collection<?> col)) {
            return Collections.emptyList();
        }
        List<Map.Entry<String, String>> result = new ArrayList<>(col.size());
        for (Object item : col) {
            if (item instanceof Map.Entry<?, ?> entry && entry.getKey() != null) {
                String k = entry.getKey().toString();
                String v = entry.getValue() != null ? entry.getValue().toString() : null;
                result.add(new java.util.AbstractMap.SimpleImmutableEntry<>(k, v));
            }
        }
        return result;
    }
}
