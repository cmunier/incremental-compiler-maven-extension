package eu.munier.maven.incremental.state;

import eu.munier.maven.incremental.bytecode.ClassFileSummary;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Persistent incremental build state for a single compilation output directory.
 */
public final class IncrementalBuildState {

    private static final int MAGIC = 0x494E4343; // "INCC"
    private static final int SCHEMA_VERSION = 1;

    private String optionsFingerprint;
    private final Map<String, SourceFileRecord> sourceRecords = new LinkedHashMap<>();
    private final Map<String, ClassFileSummary> localClassSummaries = new LinkedHashMap<>();
    private final Map<String, byte[]> externalClassCombinedHashes = new LinkedHashMap<>();
    private final Map<String, Long> classpathJarFingerprints = new LinkedHashMap<>();
    private final Set<String> pendingDirtySources = new LinkedHashSet<>();

    public IncrementalBuildState(String optionsFingerprint) {
        this.optionsFingerprint = optionsFingerprint != null ? optionsFingerprint : "";
    }

    public String getOptionsFingerprint() {
        return optionsFingerprint;
    }

    public void setOptionsFingerprint(String optionsFingerprint) {
        this.optionsFingerprint = optionsFingerprint != null ? optionsFingerprint : "";
    }

    public Map<String, SourceFileRecord> getSourceRecords() {
        return sourceRecords;
    }

    public Map<String, ClassFileSummary> getLocalClassSummaries() {
        return localClassSummaries;
    }

    public Map<String, byte[]> getExternalClassCombinedHashes() {
        return externalClassCombinedHashes;
    }

    public Map<String, Long> getClasspathJarFingerprints() {
        return classpathJarFingerprints;
    }

    public Set<String> getPendingDirtySources() {
        return pendingDirtySources;
    }

    public Set<String> getAllExternalReferencedClasses() {
        Set<String> externalRefs = new LinkedHashSet<>();
        for (ClassFileSummary summary : localClassSummaries.values()) {
            for (String ref : summary.getReferencedClassNames()) {
                if (!localClassSummaries.containsKey(ref) && !isJdkStandardClass(ref)) {
                    externalRefs.add(ref);
                }
            }
        }
        for (SourceFileRecord record : sourceRecords.values()) {
            for (String ref : record.getAstReferencedClasses()) {
                if (!localClassSummaries.containsKey(ref) && !isJdkStandardClass(ref)) {
                    externalRefs.add(ref);
                }
            }
        }
        return externalRefs;
    }

    private static boolean isJdkStandardClass(String binaryName) {
        return binaryName.startsWith("java.")
                || binaryName.startsWith("javax.lang.")
                || binaryName.startsWith("javax.annotation.processing.")
                || binaryName.startsWith("jdk.");
    }

    public void save(Path stateFile) throws IOException {
        Path parent = stateFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmpFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tmpFile)))) {
            out.writeInt(MAGIC);
            out.writeInt(SCHEMA_VERSION);
            out.writeUTF(optionsFingerprint);

            out.writeInt(sourceRecords.size());
            for (SourceFileRecord record : sourceRecords.values()) {
                record.writeTo(out);
            }

            out.writeInt(localClassSummaries.size());
            for (ClassFileSummary summary : localClassSummaries.values()) {
                summary.writeTo(out);
            }

            out.writeInt(externalClassCombinedHashes.size());
            for (Map.Entry<String, byte[]> entry : externalClassCombinedHashes.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeInt(entry.getValue().length);
                out.write(entry.getValue());
            }

            out.writeInt(classpathJarFingerprints.size());
            for (Map.Entry<String, Long> entry : classpathJarFingerprints.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeLong(entry.getValue());
            }

            out.writeInt(pendingDirtySources.size());
            for (String dirty : pendingDirtySources) {
                out.writeUTF(dirty);
            }
        }
        Files.move(tmpFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
    }

    public static IncrementalBuildState load(Path stateFile) {
        if (!Files.isRegularFile(stateFile)) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(stateFile)))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version != SCHEMA_VERSION) {
                return null;
            }
            String fingerprint = in.readUTF();
            IncrementalBuildState state = new IncrementalBuildState(fingerprint);

            int sourceCount = in.readInt();
            for (int i = 0; i < sourceCount; i++) {
                SourceFileRecord record = SourceFileRecord.readFrom(in);
                state.sourceRecords.put(record.getSourcePath(), record);
            }

            int summaryCount = in.readInt();
            for (int i = 0; i < summaryCount; i++) {
                ClassFileSummary summary = ClassFileSummary.readFrom(in);
                state.localClassSummaries.put(summary.getBinaryClassName(), summary);
            }

            int extCount = in.readInt();
            for (int i = 0; i < extCount; i++) {
                String clsName = in.readUTF();
                int len = in.readInt();
                byte[] hash = new byte[len];
                in.readFully(hash);
                state.externalClassCombinedHashes.put(clsName, hash);
            }

            int jarCount = in.readInt();
            for (int i = 0; i < jarCount; i++) {
                state.classpathJarFingerprints.put(in.readUTF(), in.readLong());
            }

            int dirtyCount = in.readInt();
            for (int i = 0; i < dirtyCount; i++) {
                state.pendingDirtySources.add(in.readUTF());
            }

            return state;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
