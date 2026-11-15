package eu.munier.maven.incremental.plugin;

import eu.munier.maven.incremental.state.ReactorAbiCache;
import java.lang.reflect.Field;
import java.util.Properties;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.execution.MojoExecutionListener;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.compiler.manager.CompilerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maven 3.x {@link MojoExecutionListener} that transparently configures both
 * {@code maven-compiler-plugin} and {@code tycho-compiler-plugin} for class-level
 * incremental compilation.
 */
@Named("incremental-compile-listener")
@Singleton
public class IncrementalCompileExecutionListener implements MojoExecutionListener {

    private static final Logger LOG = LoggerFactory.getLogger(IncrementalCompileExecutionListener.class);

    public static final String MAVEN_COMPILER_GROUP_ID = "org.apache.maven.plugins";
    public static final String MAVEN_COMPILER_ARTIFACT_ID = "maven-compiler-plugin";
    public static final String TYCHO_COMPILER_GROUP_ID = "org.eclipse.tycho";
    public static final String TYCHO_COMPILER_ARTIFACT_ID = "tycho-compiler-plugin";
    public static final String INCREMENTAL_ENABLED_PROPERTY = "maven.compiler.incremental";

    private ReactorAbiCache reactorAbiCache;

    public IncrementalCompileExecutionListener() {
        this(new ReactorAbiCache());
    }

    @Inject
    public IncrementalCompileExecutionListener(ReactorAbiCache reactorAbiCache) {
        this.reactorAbiCache = reactorAbiCache != null ? reactorAbiCache : new ReactorAbiCache();
    }

    @Override
    public void beforeMojoExecution(MojoExecutionEvent event) throws MojoExecutionException {
        if (event == null || event.getExecution() == null || event.getMojo() == null) {
            return;
        }
        MojoExecution execution = event.getExecution();
        String goal = execution.getGoal();
        if (!"compile".equals(goal) && !"testCompile".equals(goal)) {
            return;
        }

        String groupId = execution.getGroupId();
        String artifactId = execution.getArtifactId();
        boolean isMavenCompiler = MAVEN_COMPILER_GROUP_ID.equals(groupId)
                && MAVEN_COMPILER_ARTIFACT_ID.equals(artifactId);
        boolean isTychoCompiler = TYCHO_COMPILER_GROUP_ID.equals(groupId)
                && TYCHO_COMPILER_ARTIFACT_ID.equals(artifactId);

        if (!isMavenCompiler && !isTychoCompiler) {
            return;
        }

        if (!isIncrementalEnabled(event.getSession(), event.getProject())) {
            LOG.debug("[IncrementalCompiler] Disabled via property {}", INCREMENTAL_ENABLED_PROPERTY);
            return;
        }

        Mojo mojo = event.getMojo();
        configureMojoForIncrementalCompile(mojo, isTychoCompiler);
    }

    public void configureMojoForIncrementalCompile(Mojo mojo, boolean isTychoCompiler) {
        // 1. Disable Maven's module-wiping useIncrementalCompilation so target/classes is preserved
        setBooleanFieldIfPresent(mojo, "useIncrementalCompilation", false);

        // 2. For standard maven-compiler-plugin, switch default "javac" compilerId to "incremental-javac".
        // For tycho-compiler-plugin, keep compilerId="jdt" so OSGi access rules stay enabled.
        if (!isTychoCompiler) {
            Object currentCompilerId = getFieldValue(mojo, "compilerId");
            if (currentCompilerId == null || "javac".equals(currentCompilerId)) {
                setFieldValue(mojo, "compilerId", IncrementalJavacCompiler.COMPILER_ID);
            }
        }

        // 3. Wrap the Mojo's CompilerManager so both "javac" and Tycho's "jdt" are decorated
        Object existingManager = getFieldValue(mojo, "compilerManager");
        if (existingManager instanceof CompilerManager cm
                && !(existingManager instanceof IncrementalCompilerManagerDecorator)) {
            setFieldValue(
                    mojo,
                    "compilerManager",
                    new IncrementalCompilerManagerDecorator(cm, getReactorAbiCache()));
        }
    }

    @Override
    public void afterMojoExecutionSuccess(MojoExecutionEvent event) throws MojoExecutionException {
        // State and ReactorAbiCache are updated directly by IncrementalCompilationEngine
    }

    @Override
    public void afterExecutionFailure(MojoExecutionEvent event) {
        // Pending dirty sources are persisted by IncrementalCompilationEngine
    }

    public ReactorAbiCache getReactorAbiCache() {
        if (reactorAbiCache == null) {
            reactorAbiCache = new ReactorAbiCache();
        }
        return reactorAbiCache;
    }

    private static boolean isIncrementalEnabled(MavenSession session, MavenProject project) {
        String sysProp = System.getProperty(INCREMENTAL_ENABLED_PROPERTY);
        if (sysProp != null) {
            return Boolean.parseBoolean(sysProp);
        }
        if (session != null) {
            Properties userProps = session.getUserProperties();
            if (userProps != null && userProps.containsKey(INCREMENTAL_ENABLED_PROPERTY)) {
                return Boolean.parseBoolean(userProps.getProperty(INCREMENTAL_ENABLED_PROPERTY));
            }
        }
        if (project != null && project.getProperties() != null
                && project.getProperties().containsKey(INCREMENTAL_ENABLED_PROPERTY)) {
            return Boolean.parseBoolean(project.getProperties().getProperty(INCREMENTAL_ENABLED_PROPERTY));
        }
        return true;
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field f = current.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Object getFieldValue(Object target, String fieldName) {
        Field f = findField(target.getClass(), fieldName);
        if (f == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static void setFieldValue(Object target, String fieldName, Object value) {
        Field f = findField(target.getClass(), fieldName);
        if (f == null) {
            return;
        }
        try {
            f.set(target, value);
        } catch (IllegalAccessException ignored) {
        }
    }

    private static void setBooleanFieldIfPresent(Object target, String fieldName, boolean value) {
        Field f = findField(target.getClass(), fieldName);
        if (f == null) {
            return;
        }
        try {
            if (f.getType() == boolean.class) {
                f.setBoolean(target, value);
            } else if (f.getType() == Boolean.class) {
                f.set(target, value);
            }
        } catch (IllegalAccessException ignored) {
        }
    }
}
