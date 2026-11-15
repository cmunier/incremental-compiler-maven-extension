package eu.munier.maven.incremental.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileObject;

/**
 * {@link TaskListener} that inspects analyzed ASTs via the official {@link Trees} API
 * to record compile-time type dependencies that may be erased or inlined in bytecode,
 * such as {@code public static final} compile-time constants and {@code SOURCE}-retention annotations.
 */
public final class AstDependencyCollector implements TaskListener {

    private final Trees trees;
    private final Elements elements;
    private final Map<Path, Set<String>> sourceToReferencedClasses = new LinkedHashMap<>();
    private final Map<Path, String> sourceToPackageName = new LinkedHashMap<>();
    private final Map<Path, Set<String>> sourceToWildcardImports = new LinkedHashMap<>();

    public AstDependencyCollector(JavacTask task) {
        this.trees = Trees.instance(task);
        this.elements = task.getElements();
    }

    @Override
    public void started(TaskEvent event) {
        // No action on start
    }

    @Override
    public void finished(TaskEvent event) {
        if (event.getKind() != TaskEvent.Kind.ANALYZE) {
            return;
        }
        CompilationUnitTree unit = event.getCompilationUnit();
        JavaFileObject sourceFile = event.getSourceFile();
        if (unit == null || sourceFile == null) {
            return;
        }
        Path sourcePath = toPath(sourceFile);
        if (sourcePath == null) {
            return;
        }

        ExpressionTree pkgTree = unit.getPackageName();
        String pkgName = pkgTree != null ? pkgTree.toString() : "";
        sourceToPackageName.put(sourcePath, pkgName);

        Set<String> wildcards = sourceToWildcardImports.computeIfAbsent(
                sourcePath, k -> new LinkedHashSet<>());
        for (ImportTree imp : unit.getImports()) {
            if (imp.getQualifiedIdentifier() != null) {
                String qName = imp.getQualifiedIdentifier().toString();
                if (qName.endsWith(".*")) {
                    String prefix = qName.substring(0, qName.length() - 2);
                    wildcards.add(prefix);
                    if (imp.isStatic()) {
                        recordBinaryClassName(sourcePath, prefix);
                    }
                } else if (imp.isStatic()) {
                    int lastDot = qName.lastIndexOf('.');
                    if (lastDot > 0) {
                        recordBinaryClassName(sourcePath, qName.substring(0, lastDot));
                    }
                }
            }
        }

        new TreePathScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void p) {
                if (tree == null) {
                    return null;
                }
                TreePath currentPath = new TreePath(getCurrentPath(), tree);
                try {
                    Element element = trees.getElement(currentPath);
                    if (element != null) {
                        recordElementReference(sourcePath, element);
                    }
                } catch (RuntimeException ignored) {
                    // Safely ignore partially resolved trees
                }
                try {
                    TypeMirror typeMirror = trees.getTypeMirror(currentPath);
                    if (typeMirror != null) {
                        recordTypeMirrorReference(sourcePath, typeMirror);
                    }
                } catch (RuntimeException ignored) {
                    // Safely ignore partially resolved type mirrors
                }
                return super.scan(tree, p);
            }
        }.scan(unit, null);
    }

    private void recordElementReference(Path sourcePath, Element element) {
        Element curr = element;
        while (curr != null) {
            if (curr instanceof TypeElement typeElement) {
                recordTypeElementAndSupertypes(sourcePath, typeElement, new LinkedHashSet<>());
            }
            curr = curr.getEnclosingElement();
        }
    }

    private void recordTypeElementAndSupertypes(
            Path sourcePath,
            TypeElement typeElement,
            Set<String> visitedBinaryNames) {
        CharSequence binaryNameSeq = elements.getBinaryName(typeElement);
        if (binaryNameSeq == null || binaryNameSeq.isEmpty()) {
            return;
        }
        String binaryName = binaryNameSeq.toString();
        if (binaryName.startsWith("java.lang.") || !visitedBinaryNames.add(binaryName)) {
            return;
        }
        recordBinaryClassName(sourcePath, binaryName);

        TypeMirror superclass = typeElement.getSuperclass();
        if (superclass instanceof DeclaredType superDeclared
                && superDeclared.asElement() instanceof TypeElement superTypeEl) {
            recordTypeElementAndSupertypes(sourcePath, superTypeEl, visitedBinaryNames);
        }
        for (TypeMirror iface : typeElement.getInterfaces()) {
            if (iface instanceof DeclaredType ifaceDeclared
                    && ifaceDeclared.asElement() instanceof TypeElement ifaceTypeEl) {
                recordTypeElementAndSupertypes(sourcePath, ifaceTypeEl, visitedBinaryNames);
            }
        }
    }

    private void recordTypeMirrorReference(Path sourcePath, TypeMirror type) {
        if (type == null || type.getKind() == TypeKind.NONE || type.getKind() == TypeKind.ERROR) {
            return;
        }
        if (type instanceof DeclaredType declaredType) {
            Element asElement = declaredType.asElement();
            if (asElement != null) {
                recordElementReference(sourcePath, asElement);
            }
            List<? extends TypeMirror> typeArgs = declaredType.getTypeArguments();
            if (typeArgs != null) {
                for (TypeMirror arg : typeArgs) {
                    recordTypeMirrorReference(sourcePath, arg);
                }
            }
        } else if (type instanceof ArrayType arrayType) {
            recordTypeMirrorReference(sourcePath, arrayType.getComponentType());
        } else if (type instanceof WildcardType wildcardType) {
            recordTypeMirrorReference(sourcePath, wildcardType.getExtendsBound());
            recordTypeMirrorReference(sourcePath, wildcardType.getSuperBound());
        } else if (type instanceof IntersectionType intersectionType) {
            for (TypeMirror bound : intersectionType.getBounds()) {
                recordTypeMirrorReference(sourcePath, bound);
            }
        } else if (type instanceof UnionType unionType) {
            for (TypeMirror alt : unionType.getAlternatives()) {
                recordTypeMirrorReference(sourcePath, alt);
            }
        }
    }

    private void recordBinaryClassName(Path sourcePath, String binaryName) {
        if (binaryName == null || binaryName.isEmpty() || binaryName.startsWith("java.lang.")) {
            return;
        }
        sourceToReferencedClasses
                .computeIfAbsent(sourcePath, k -> new LinkedHashSet<>())
                .add(binaryName);
    }

    private static Path toPath(JavaFileObject fileObject) {
        try {
            URI uri = fileObject.toUri();
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                return Path.of(uri).toAbsolutePath().normalize();
            }
        } catch (RuntimeException ignored) {
            // Non-file URI
        }
        return null;
    }

    public Set<String> getReferencedClasses(Path sourcePath) {
        return sourceToReferencedClasses.getOrDefault(
                sourcePath.toAbsolutePath().normalize(), Collections.emptySet());
    }

    public String getPackageName(Path sourcePath) {
        return sourceToPackageName.getOrDefault(
                sourcePath.toAbsolutePath().normalize(), "");
    }

    public Set<String> getWildcardImports(Path sourcePath) {
        return sourceToWildcardImports.getOrDefault(
                sourcePath.toAbsolutePath().normalize(), Collections.emptySet());
    }
}
