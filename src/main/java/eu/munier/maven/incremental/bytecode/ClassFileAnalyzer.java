package eu.munier.maven.incremental.bytecode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

/**
 * Analyzes JVM {@code .class} files using OW2 ASM to extract:
 * <ul>
 *   <li>ABI fingerprint (ignoring private members and method implementation bodies)</li>
 *   <li>Class hierarchy fingerprint (superclass, interfaces, permitted subclasses, modifiers)</li>
 *   <li>Compile-time constants ({@code static final} primitive/String {@code ConstantValue}s)</li>
 *   <li>All referenced binary class names (via Constant Pool, descriptors, generic signatures, and annotations)</li>
 * </ul>
 */
public final class ClassFileAnalyzer {

    private static final int CONSTANT_CLASS_TAG = 7;
    private static final int CONSTANT_NAME_AND_TYPE_TAG = 12;
    private static final int CONSTANT_METHOD_TYPE_TAG = 16;

    public ClassFileSummary analyze(Path classFile) throws IOException {
        return analyze(Files.readAllBytes(classFile));
    }

    public ClassFileSummary analyze(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        AbiAndReferenceClassVisitor visitor = new AbiAndReferenceClassVisitor();
        reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);

        scanConstantPoolReferences(reader, classBytes, visitor);

        return visitor.buildSummary();
    }

    private void scanConstantPoolReferences(
            ClassReader reader,
            byte[] classBytes,
            AbiAndReferenceClassVisitor visitor) {
        int itemCount = reader.getItemCount();
        char[] charBuffer = new char[Math.max(1024, reader.getMaxStringLength())];

        for (int i = 1; i < itemCount; i++) {
            int offset = reader.getItem(i);
            if (offset <= 0 || offset >= classBytes.length) {
                continue;
            }
            int tag = classBytes[offset - 1] & 0xFF;
            if (tag == CONSTANT_CLASS_TAG) {
                String rawClassName = reader.readUTF8(offset, charBuffer);
                visitor.addInternalOrArrayReference(rawClassName);
            } else if (tag == CONSTANT_NAME_AND_TYPE_TAG) {
                if (offset + 2 < classBytes.length) {
                    String descriptor = reader.readUTF8(offset + 2, charBuffer);
                    visitor.addDescriptorReferences(descriptor);
                }
            } else if (tag == CONSTANT_METHOD_TYPE_TAG) {
                String descriptor = reader.readUTF8(offset, charBuffer);
                visitor.addDescriptorReferences(descriptor);
            }
        }
    }

    public static String internalToBinary(String internalName) {
        if (internalName == null) {
            return null;
        }
        return internalName.replace('/', '.');
    }

    private static final class AbiAndReferenceClassVisitor extends ClassVisitor {

        private String internalClassName;
        private String binaryClassName;
        private String sourceFileName;
        private int classAccess;
        private String classSignature;
        private String superClassName;
        private final List<String> interfaceNames = new ArrayList<>();
        private final List<String> permittedSubclasses = new ArrayList<>();
        private final List<String> recordComponents = new ArrayList<>();
        private final List<String> classAnnotations = new ArrayList<>();
        private final List<String> innerClassEntries = new ArrayList<>();
        private final List<String> nonPrivateFields = new ArrayList<>();
        private final List<String> nonPrivateMethods = new ArrayList<>();
        private final Map<String, Object> compileTimeConstants = new LinkedHashMap<>();
        private final Set<String> referencedClasses = new TreeSet<>();

        AbiAndReferenceClassVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(
                int version,
                int access,
                String name,
                String signature,
                String superName,
                String[] interfaces) {
            this.internalClassName = name;
            this.binaryClassName = internalToBinary(name);
            this.classAccess = access & ~(Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC);
            this.classSignature = signature;
            this.superClassName = internalToBinary(superName);

            addInternalReference(superName);
            if (interfaces != null) {
                for (String iface : interfaces) {
                    interfaceNames.add(internalToBinary(iface));
                    addInternalReference(iface);
                }
                Collections.sort(interfaceNames);
            }
            addSignatureReferences(signature, false);
        }

        @Override
        public void visitSource(String source, String debug) {
            this.sourceFileName = source;
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            if (permittedSubclass != null) {
                permittedSubclasses.add(internalToBinary(permittedSubclass));
                addInternalReference(permittedSubclass);
            }
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(
                String name,
                String descriptor,
                String signature) {
            addDescriptorReferences(descriptor);
            addSignatureReferences(signature, true);
            List<String> componentAnnotations = new ArrayList<>();
            return new RecordComponentVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    addDescriptorReferences(desc);
                    return createAnnotationRecorder(desc, visible, componentAnnotations);
                }

                @Override
                public void visitEnd() {
                    Collections.sort(componentAnnotations);
                    recordComponents.add(name + ":" + descriptor + ":" + Objects.toString(signature, "")
                            + ":" + componentAnnotations);
                }
            };
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            addDescriptorReferences(descriptor);
            return createAnnotationRecorder(descriptor, visible, classAnnotations);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(
                int typeRef,
                TypePath typePath,
                String descriptor,
                boolean visible) {
            addDescriptorReferences(descriptor);
            return createAnnotationRecorder(descriptor, visible, classAnnotations);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            addInternalReference(name);
            addInternalReference(outerName);
            if ((access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) == 0
                    && name != null
                    && internalClassName != null
                    && (name.equals(internalClassName) || name.startsWith(internalClassName + "$"))) {
                innerClassEntries.add(name + ":" + Objects.toString(outerName, "") + ":"
                        + Objects.toString(innerName, "") + ":" + access);
            }
        }

        @Override
        public FieldVisitor visitField(
                int access,
                String name,
                String descriptor,
                String signature,
                Object value) {
            addDescriptorReferences(descriptor);
            addSignatureReferences(signature, true);

            boolean isAbiVisible = (access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) == 0;
            if (isAbiVisible
                    && (access & Opcodes.ACC_STATIC) != 0
                    && (access & Opcodes.ACC_FINAL) != 0
                    && value != null) {
                compileTimeConstants.put(name, value);
            }

            List<String> fieldAnnotations = new ArrayList<>();
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    addDescriptorReferences(desc);
                    return createAnnotationRecorder(desc, visible, fieldAnnotations);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(
                        int typeRef,
                        TypePath typePath,
                        String desc,
                        boolean visible) {
                    addDescriptorReferences(desc);
                    return createAnnotationRecorder(desc, visible, fieldAnnotations);
                }

                @Override
                public void visitEnd() {
                    if (isAbiVisible) {
                        Collections.sort(fieldAnnotations);
                        String constRepr = value != null ? (value.getClass().getName() + "=" + value) : "";
                        nonPrivateFields.add(access + "|" + name + "|" + descriptor + "|"
                                + Objects.toString(signature, "") + "|" + constRepr + "|" + fieldAnnotations);
                    }
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions) {
            addDescriptorReferences(descriptor);
            addSignatureReferences(signature, false);
            List<String> exceptionList = new ArrayList<>();
            if (exceptions != null) {
                for (String ex : exceptions) {
                    exceptionList.add(internalToBinary(ex));
                    addInternalReference(ex);
                }
                Collections.sort(exceptionList);
            }

            boolean isAbiVisible = (access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC)) == 0
                    && !"<clinit>".equals(name);
            List<String> methodAnnotations = new ArrayList<>();
            List<String> defaultValHolder = new ArrayList<>();

            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return createAnnotationValueRecorder("default", defaultValHolder);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    addDescriptorReferences(desc);
                    return createAnnotationRecorder(desc, visible, methodAnnotations);
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(
                        int parameter,
                        String desc,
                        boolean visible) {
                    addDescriptorReferences(desc);
                    return createAnnotationRecorder("param" + parameter + ":" + desc, visible, methodAnnotations);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(
                        int typeRef,
                        TypePath typePath,
                        String desc,
                        boolean visible) {
                    addDescriptorReferences(desc);
                    return createAnnotationRecorder(desc, visible, methodAnnotations);
                }

                @Override
                public void visitEnd() {
                    if (isAbiVisible) {
                        Collections.sort(methodAnnotations);
                        nonPrivateMethods.add(access + "|" + name + "|" + descriptor + "|"
                                + Objects.toString(signature, "") + "|" + exceptionList + "|"
                                + defaultValHolder + "|" + methodAnnotations);
                    }
                }
            };
        }

        private AnnotationVisitor createAnnotationRecorder(
                String descriptor,
                boolean visible,
                List<String> sink) {
            List<String> values = new ArrayList<>();
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    if (value instanceof Type typeVal) {
                        addTypeReference(typeVal);
                    }
                    values.add(Objects.toString(name, "") + "=" + formatAnnotationScalar(value));
                }

                @Override
                public void visitEnum(String name, String desc, String value) {
                    addDescriptorReferences(desc);
                    values.add(Objects.toString(name, "") + "=" + desc + "." + value);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String name, String desc) {
                    addDescriptorReferences(desc);
                    return createAnnotationRecorder(Objects.toString(name, "") + "@" + desc, visible, values);
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    return createAnnotationRecorder(Objects.toString(name, "") + "[]", visible, values);
                }

                @Override
                public void visitEnd() {
                    sink.add((visible ? "V:" : "I:") + descriptor + values);
                }
            };
        }

        private AnnotationVisitor createAnnotationValueRecorder(String prefix, List<String> sink) {
            return createAnnotationRecorder(prefix, true, sink);
        }

        private String formatAnnotationScalar(Object value) {
            if (value == null) {
                return "null";
            }
            if (value instanceof byte[] b) {
                return Arrays.toString(b);
            }
            if (value instanceof short[] s) {
                return Arrays.toString(s);
            }
            if (value instanceof int[] i) {
                return Arrays.toString(i);
            }
            if (value instanceof long[] l) {
                return Arrays.toString(l);
            }
            if (value instanceof float[] f) {
                return Arrays.toString(f);
            }
            if (value instanceof double[] d) {
                return Arrays.toString(d);
            }
            if (value instanceof boolean[] z) {
                return Arrays.toString(z);
            }
            if (value instanceof char[] c) {
                return Arrays.toString(c);
            }
            if (value instanceof Object[] o) {
                return Arrays.deepToString(o);
            }
            return value.toString();
        }

        void addInternalOrArrayReference(String rawName) {
            if (rawName == null || rawName.isEmpty()) {
                return;
            }
            if (rawName.charAt(0) == '[') {
                addDescriptorReferences(rawName);
            } else {
                addInternalReference(rawName);
            }
        }

        void addInternalReference(String internalName) {
            if (internalName == null || internalName.isEmpty() || internalName.charAt(0) == '[') {
                return;
            }
            String binary = internalToBinary(internalName);
            if (!binary.equals(this.binaryClassName) && !isStandardBootstrapPrimitiveWrapperOrObject(binary)) {
                referencedClasses.add(binary);
            }
        }

        private boolean isStandardBootstrapPrimitiveWrapperOrObject(String binaryName) {
            return "java.lang.Object".equals(binaryName);
        }

        void addDescriptorReferences(String descriptor) {
            if (descriptor == null || descriptor.isEmpty()) {
                return;
            }
            try {
                if (descriptor.charAt(0) == '(') {
                    Type methodType = Type.getMethodType(descriptor);
                    addTypeReference(methodType.getReturnType());
                    for (Type argType : methodType.getArgumentTypes()) {
                        addTypeReference(argType);
                    }
                } else {
                    addTypeReference(Type.getType(descriptor));
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed descriptors
            }
        }

        private void addTypeReference(Type type) {
            if (type == null) {
                return;
            }
            int sort = type.getSort();
            if (sort == Type.ARRAY) {
                addTypeReference(type.getElementType());
            } else if (sort == Type.OBJECT) {
                addInternalReference(type.getInternalName());
            } else if (sort == Type.METHOD) {
                addTypeReference(type.getReturnType());
                for (Type arg : type.getArgumentTypes()) {
                    addTypeReference(arg);
                }
            }
        }

        private void addSignatureReferences(String signature, boolean isTypeSignature) {
            if (signature == null || signature.isEmpty()) {
                return;
            }
            try {
                SignatureReader sigReader = new SignatureReader(signature);
                SignatureVisitor sigVisitor = new SignatureVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitClassType(String name) {
                        addInternalReference(name);
                    }

                    @Override
                    public void visitInnerClassType(String name) {
                        addInternalReference(name);
                    }
                };
                if (isTypeSignature) {
                    sigReader.acceptType(sigVisitor);
                } else {
                    sigReader.accept(sigVisitor);
                }
            } catch (RuntimeException ignored) {
                // Ignore non-standard signature strings
            }
        }

        ClassFileSummary buildSummary() {
            Collections.sort(permittedSubclasses);
            Collections.sort(classAnnotations);
            Collections.sort(innerClassEntries);
            Collections.sort(nonPrivateFields);
            Collections.sort(nonPrivateMethods);

            byte[] hierarchyHash = computeHierarchyHash();
            byte[] abiHash = computeAbiHash(hierarchyHash);

            return new ClassFileSummary(
                    binaryClassName,
                    sourceFileName,
                    superClassName,
                    interfaceNames,
                    abiHash,
                    hierarchyHash,
                    compileTimeConstants,
                    referencedClasses);
        }

        private byte[] computeHierarchyHash() {
            MessageDigest md = newSha256();
            int hierarchyFlags = classAccess & (Opcodes.ACC_PUBLIC
                    | Opcodes.ACC_PROTECTED
                    | Opcodes.ACC_FINAL
                    | Opcodes.ACC_ABSTRACT
                    | Opcodes.ACC_INTERFACE
                    | Opcodes.ACC_ENUM
                    | Opcodes.ACC_ANNOTATION
                    | Opcodes.ACC_RECORD);
            updateString(md, "CLS:" + binaryClassName + ":" + hierarchyFlags);
            updateString(md, "SUP:" + Objects.toString(superClassName, ""));
            for (String iface : interfaceNames) {
                updateString(md, "IFC:" + iface);
            }
            for (String permitted : permittedSubclasses) {
                updateString(md, "PRM:" + permitted);
            }
            return md.digest();
        }

        private byte[] computeAbiHash(byte[] hierarchyHash) {
            MessageDigest md = newSha256();
            md.update(hierarchyHash);
            updateString(md, "ACC:" + classAccess);
            updateString(md, "SIG:" + Objects.toString(classSignature, ""));
            for (String rc : recordComponents) {
                updateString(md, "REC:" + rc);
            }
            for (String ann : classAnnotations) {
                updateString(md, "ANN:" + ann);
            }
            for (String inner : innerClassEntries) {
                updateString(md, "INR:" + inner);
            }
            for (String field : nonPrivateFields) {
                updateString(md, "FLD:" + field);
            }
            for (String method : nonPrivateMethods) {
                updateString(md, "MTD:" + method);
            }
            return md.digest();
        }

        private static MessageDigest newSha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }

        private static void updateString(MessageDigest md, String value) {
            md.update(value.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
        }
    }

    private static final class Objects {
        static String toString(Object o, String defaultVal) {
            return o != null ? o.toString() : defaultVal;
        }
    }
}
