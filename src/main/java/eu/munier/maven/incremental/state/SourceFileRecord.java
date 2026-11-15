package eu.munier.maven.incremental.state;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Persisted metadata for a single {@code .java} source file in an incremental compilation state.
 */
public final class SourceFileRecord {

    private final String sourcePath;
    private final long lastModifiedMillis;
    private final long fileSize;
    private final byte[] contentSha256;
    private final String packageName;
    private final Set<String> wildcardImports;
    private final Set<String> definedClassNames;
    private final Set<String> generatedClassFilePaths;
    private final Set<String> aptGeneratedSourcePaths;
    private final Set<String> astReferencedClasses;

    public SourceFileRecord(
            String sourcePath,
            long lastModifiedMillis,
            long fileSize,
            byte[] contentSha256,
            String packageName,
            Set<String> wildcardImports,
            Set<String> definedClassNames,
            Set<String> generatedClassFilePaths,
            Set<String> aptGeneratedSourcePaths,
            Set<String> astReferencedClasses) {
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.lastModifiedMillis = lastModifiedMillis;
        this.fileSize = fileSize;
        this.contentSha256 = contentSha256.clone();
        this.packageName = packageName != null ? packageName : "";
        this.wildcardImports = unmodifiableCopy(wildcardImports);
        this.definedClassNames = unmodifiableCopy(definedClassNames);
        this.generatedClassFilePaths = unmodifiableCopy(generatedClassFilePaths);
        this.aptGeneratedSourcePaths = unmodifiableCopy(aptGeneratedSourcePaths);
        this.astReferencedClasses = unmodifiableCopy(astReferencedClasses);
    }

    private static Set<String> unmodifiableCopy(Set<String> set) {
        if (set == null || set.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(set));
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public long getLastModifiedMillis() {
        return lastModifiedMillis;
    }

    public long getFileSize() {
        return fileSize;
    }

    public byte[] getContentSha256() {
        return contentSha256.clone();
    }

    public boolean hasSameContentHash(byte[] otherSha256) {
        return Arrays.equals(this.contentSha256, otherSha256);
    }

    public String getPackageName() {
        return packageName;
    }

    public Set<String> getWildcardImports() {
        return wildcardImports;
    }

    public Set<String> getDefinedClassNames() {
        return definedClassNames;
    }

    public Set<String> getGeneratedClassFilePaths() {
        return generatedClassFilePaths;
    }

    public Set<String> getAptGeneratedSourcePaths() {
        return aptGeneratedSourcePaths;
    }

    public Set<String> getAstReferencedClasses() {
        return astReferencedClasses;
    }

    public SourceFileRecord withUpdatedTimestamp(long newLastModifiedMillis) {
        return new SourceFileRecord(
                sourcePath,
                newLastModifiedMillis,
                fileSize,
                contentSha256,
                packageName,
                wildcardImports,
                definedClassNames,
                generatedClassFilePaths,
                aptGeneratedSourcePaths,
                astReferencedClasses);
    }

    public void writeTo(DataOutputStream out) throws IOException {
        out.writeUTF(sourcePath);
        out.writeLong(lastModifiedMillis);
        out.writeLong(fileSize);
        out.writeInt(contentSha256.length);
        out.write(contentSha256);
        out.writeUTF(packageName);
        writeSet(out, wildcardImports);
        writeSet(out, definedClassNames);
        writeSet(out, generatedClassFilePaths);
        writeSet(out, aptGeneratedSourcePaths);
        writeSet(out, astReferencedClasses);
    }

    public static SourceFileRecord readFrom(DataInputStream in) throws IOException {
        String sourcePath = in.readUTF();
        long lastModifiedMillis = in.readLong();
        long fileSize = in.readLong();
        int shaLen = in.readInt();
        byte[] sha256 = new byte[shaLen];
        in.readFully(sha256);
        String packageName = in.readUTF();
        Set<String> wildcardImports = readSet(in);
        Set<String> definedClassNames = readSet(in);
        Set<String> generatedClassFilePaths = readSet(in);
        Set<String> aptGeneratedSourcePaths = readSet(in);
        Set<String> astReferencedClasses = readSet(in);
        return new SourceFileRecord(
                sourcePath,
                lastModifiedMillis,
                fileSize,
                sha256,
                packageName,
                wildcardImports,
                definedClassNames,
                generatedClassFilePaths,
                aptGeneratedSourcePaths,
                astReferencedClasses);
    }

    private static void writeSet(DataOutputStream out, Set<String> items) throws IOException {
        out.writeInt(items.size());
        for (String item : items) {
            out.writeUTF(item);
        }
    }

    private static Set<String> readSet(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count == 0) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>(count);
        for (int i = 0; i < count; i++) {
            result.add(in.readUTF());
        }
        return result;
    }
}
