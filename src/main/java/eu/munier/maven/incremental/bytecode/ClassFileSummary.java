package eu.munier.maven.incremental.bytecode;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immutable summary of a compiled {@code .class} file containing its ABI fingerprint,
 * hierarchy fingerprint, compile-time constants, and all referenced binary class names.
 */
public final class ClassFileSummary {

    private final String binaryClassName;
    private final String sourceFileName;
    private final String superClassName;
    private final List<String> interfaceNames;
    private final byte[] abiHash;
    private final byte[] hierarchyHash;
    private final Map<String, String> compileTimeConstants;
    private final byte[] constantsHash;
    private final Set<String> referencedClassNames;

    public ClassFileSummary(
            String binaryClassName,
            String superClassName,
            List<String> interfaceNames,
            byte[] abiHash,
            byte[] hierarchyHash,
            Map<String, Object> compileTimeConstants,
            Set<String> referencedClassNames) {
        this(
                binaryClassName,
                null,
                superClassName,
                interfaceNames,
                abiHash,
                hierarchyHash,
                compileTimeConstants,
                referencedClassNames);
    }

    public ClassFileSummary(
            String binaryClassName,
            String sourceFileName,
            String superClassName,
            List<String> interfaceNames,
            byte[] abiHash,
            byte[] hierarchyHash,
            Map<String, Object> compileTimeConstants,
            Set<String> referencedClassNames) {
        this.binaryClassName = Objects.requireNonNull(binaryClassName, "binaryClassName");
        this.sourceFileName = sourceFileName;
        this.superClassName = superClassName;
        this.interfaceNames = Collections.unmodifiableList(new ArrayList<>(interfaceNames));
        this.abiHash = abiHash.clone();
        this.hierarchyHash = hierarchyHash.clone();

        Map<String, String> sortedConstants = new TreeMap<>();
        if (compileTimeConstants != null) {
            for (Map.Entry<String, Object> entry : compileTimeConstants.entrySet()) {
                if (entry.getValue() != null) {
                    sortedConstants.put(entry.getKey(),
                            entry.getValue().getClass().getName() + ":" + entry.getValue());
                }
            }
        }
        this.compileTimeConstants = Collections.unmodifiableMap(new LinkedHashMap<>(sortedConstants));
        this.constantsHash = computeConstantsHash(this.compileTimeConstants);
        this.referencedClassNames = Collections.unmodifiableSet(new LinkedHashSet<>(referencedClassNames));
    }

    private ClassFileSummary(
            String binaryClassName,
            String sourceFileName,
            String superClassName,
            List<String> interfaceNames,
            byte[] abiHash,
            byte[] hierarchyHash,
            Map<String, String> normalizedConstants,
            byte[] constantsHash,
            Set<String> referencedClassNames) {
        this.binaryClassName = binaryClassName;
        this.sourceFileName = sourceFileName;
        this.superClassName = superClassName;
        this.interfaceNames = Collections.unmodifiableList(interfaceNames);
        this.abiHash = abiHash;
        this.hierarchyHash = hierarchyHash;
        this.compileTimeConstants = Collections.unmodifiableMap(normalizedConstants);
        this.constantsHash = constantsHash;
        this.referencedClassNames = Collections.unmodifiableSet(referencedClassNames);
    }

    private static byte[] computeConstantsHash(Map<String, String> sortedConstants) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> entry : sortedConstants.entrySet()) {
                md.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                md.update((byte) '=');
                md.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public String getBinaryClassName() {
        return binaryClassName;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public String getSuperClassName() {
        return superClassName;
    }

    public List<String> getInterfaceNames() {
        return interfaceNames;
    }

    public byte[] getAbiHash() {
        return abiHash.clone();
    }

    public byte[] getHierarchyHash() {
        return hierarchyHash.clone();
    }

    public Map<String, String> getCompileTimeConstants() {
        return compileTimeConstants;
    }

    public byte[] getConstantsHash() {
        return constantsHash.clone();
    }

    public Set<String> getReferencedClassNames() {
        return referencedClassNames;
    }

    public boolean hasSameAbi(ClassFileSummary other) {
        return other != null && Arrays.equals(this.abiHash, other.abiHash);
    }

    public boolean hasSameHierarchy(ClassFileSummary other) {
        return other != null && Arrays.equals(this.hierarchyHash, other.hierarchyHash);
    }

    public boolean hasSameConstants(ClassFileSummary other) {
        return other != null && Arrays.equals(this.constantsHash, other.constantsHash);
    }

    public void writeTo(DataOutputStream out) throws IOException {
        out.writeUTF(binaryClassName);
        out.writeBoolean(sourceFileName != null);
        if (sourceFileName != null) {
            out.writeUTF(sourceFileName);
        }
        out.writeBoolean(superClassName != null);
        if (superClassName != null) {
            out.writeUTF(superClassName);
        }
        out.writeInt(interfaceNames.size());
        for (String iface : interfaceNames) {
            out.writeUTF(iface);
        }
        writeBytes(out, abiHash);
        writeBytes(out, hierarchyHash);
        out.writeInt(compileTimeConstants.size());
        for (Map.Entry<String, String> entry : compileTimeConstants.entrySet()) {
            out.writeUTF(entry.getKey());
            out.writeUTF(entry.getValue());
        }
        writeBytes(out, constantsHash);
        out.writeInt(referencedClassNames.size());
        for (String ref : referencedClassNames) {
            out.writeUTF(ref);
        }
    }

    public static ClassFileSummary readFrom(DataInputStream in) throws IOException {
        String binaryClassName = in.readUTF();
        String sourceFileName = in.readBoolean() ? in.readUTF() : null;
        String superClassName = in.readBoolean() ? in.readUTF() : null;
        int ifaceCount = in.readInt();
        List<String> interfaces = new ArrayList<>(ifaceCount);
        for (int i = 0; i < ifaceCount; i++) {
            interfaces.add(in.readUTF());
        }
        byte[] abiHash = readBytes(in);
        byte[] hierarchyHash = readBytes(in);
        int constCount = in.readInt();
        Map<String, String> constants = new LinkedHashMap<>(constCount);
        for (int i = 0; i < constCount; i++) {
            constants.put(in.readUTF(), in.readUTF());
        }
        byte[] constantsHash = readBytes(in);
        int refCount = in.readInt();
        Set<String> refs = new LinkedHashSet<>(refCount);
        for (int i = 0; i < refCount; i++) {
            refs.add(in.readUTF());
        }
        return new ClassFileSummary(
                binaryClassName,
                sourceFileName,
                superClassName,
                interfaces,
                abiHash,
                hierarchyHash,
                constants,
                constantsHash,
                refs);
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return bytes;
    }
}
