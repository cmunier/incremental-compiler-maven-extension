# Incremental Compiler Maven Extension (`incremental-compiler-maven-extension`)

[![CI & Coverage](https://github.com/cmunier/incremental-compiler-maven-extension/actions/workflows/ci.yml/badge.svg)](https://github.com/cmunier/incremental-compiler-maven-extension/actions/workflows/ci.yml)
![Coverage](.github/badges/jacoco.svg)
![Branches](.github/badges/branches.svg)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

A modular extension for **Apache Maven 3.x** that brings true **class-level incremental compilation** (comparable to Eclipse JDT, IntelliJ IDEA JPS, and Gradle) to both `maven-compiler-plugin` and `tycho-compiler-plugin`.

---

## Why this extension exists

Out of the box, `maven-compiler-plugin` only supports **module-level** incremental compilation:
- When `useIncrementalCompilation=true` (Maven's default), modifying a single `.java` file causes Maven to **delete the entire `target/classes` directory** and recompile every source file in the module.
- When `useIncrementalCompilation=false`, Maven only recompiles files whose `.class` file is older than the `.java` file, completely ignoring inter-class dependencies, ABI changes, deleted classes, and inlined `static final` constants.

`incremental-compiler-maven-extension` replaces this all-or-nothing behavior with a dependency-aware, ABI-driven incremental compilation engine while keeping `org.ow2.asm:asm` as its **only** runtime dependency.

---

## Key features

1. **Two-tier bytecode & ABI fingerprinting (`org.ow2.asm:asm`)**
   - Distinguishes between internal implementation changes (method bodies, private members, comments/formatting) and externally visible changes to a class's signature (`abiHash`), inheritance hierarchy (`hierarchyHash`), or `static final` compile-time constants (`constantsHash`).
   - If a modified class's ABI and constants remain unchanged, dependent classes in the same module and in downstream reactor modules are **not** recompiled.

2. **Hybrid dependency analysis (Bytecode Constant Pool + JSR-199 AST)**
   - **Bytecode level**: Scans the JVM Constant Pool (`CONSTANT_Class`, `CONSTANT_NameAndType`, `CONSTANT_MethodType`), method/field descriptors, generic `Signature` attributes, `PermittedSubclasses` (`sealed` types), and annotations using `ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES`.
   - **Source / AST level**: Uses a JSR-199 `TaskListener` (`com.sun.source.util.Trees`) during the `ANALYZE` phase to capture references that are erased or inlined in bytecode—most notably inlined `public static final` constants, `@Retention(SOURCE)` annotations, static imports, and transitive cross-module supertypes.

3. **JLS edge-case parity**
   - **Transitive cross-module supertypes**: Detects abstract method additions in upstream interfaces/superclasses even when a downstream concrete class only references an intermediate abstract class.
   - **Type shadowing & wildcard imports (JLS §6.5.2)**: Automatically recompiles affected sources when a newly added or deleted top-level type in a package shadows or unshadows an on-demand `import pkg.*;`.
   - **Secondary top-level types (JLS §7.6)**: Tracks multiple top-level classes defined within a single `.java` file via the bytecode `SourceFile` attribute.
   - **`package-info.java` handling (JLS §7.4.1)**: Avoids redundant recompilation when `package-info.java` produces no `.class` output (source-only annotations or documentation) while recompiling package members when `package-info.java` changes.
   - **Java 17+ `record` & `sealed` hierarchies (JLS §8.1.1.2)**: Tracks `RecordComponent` and `PermittedSubclasses` attributes so exhaustive `switch` expressions and subtypes are revalidated when `permits` clauses change.

4. **Incremental JSR-269 annotation processing (APT)**
   - Wraps the compiler's `StandardJavaFileManager` with a `TrackingJavaFileManager` to record exact provenance between originating `.java` sources, APT-generated `.java` sources (e.g., MapStruct, Immutables), and their compiled `.class` files.
   - Automatically deletes stale generated `.java` and `.class` files before recompiling modified originating sources or when sources are removed.

5. **Seamless support for `maven-compiler-plugin` and `tycho-compiler-plugin`**
   - Works in pure Maven builds, pure Eclipse Tycho / OSGi builds, and mixed Maven + Tycho multi-module reactors.
   - Preserves Tycho's `JDTCompiler` (`compilerId="jdt"`), OSGi access rules (`bundle.jar[+org/example/api/*;?**/*]`), and `.settings/org.eclipse.jdt.core.prefs` via `IncrementalDelegatingCompiler`.
   - Shares class ABI snapshots across all modules in the reactor through a `@Singleton` `ReactorAbiCache`.

6. **Crash-safe state persistence & automatic full rebuild detection**
   - Persists incremental state atomically in `target/incremental-compiler/classes/state.bin` (and `test-classes/state.bin`).
   - Records `pendingDirtySources` *before* each compilation round: if a build fails due to a compile error in a dependent class, the next build will reliably re-attempt compilation even if no files were edited in between.
   - Automatically triggers a clean full module rebuild whenever compiler flags (`--release`, `-source`, `-target`, `-encoding`, `-parameters`, `-g`, `-proc`, custom compiler args) or OSGi descriptors (`META-INF/MANIFEST.MF`, `build.properties`, `.settings/org.eclipse.jdt.core.prefs`) change.

---

## Requirements

- **Java**: 17 or newer (tested on Java 17 and Java 21 LTS)
- **Apache Maven**: 3.8.x / 3.9.x
- **Supported compiler plugins**:
  - `org.apache.maven.plugins:maven-compiler-plugin` (3.x)
  - `org.eclipse.tycho:tycho-compiler-plugin` (3.x / 4.x / 5.x)

---

## Installation & Usage

### Option 1: Via `.mvn/extensions.xml` (Recommended – zero POM changes)

Add `.mvn/extensions.xml` to the root directory of your multi-module project:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>eu.munier.maven.extensions</groupId>
    <artifactId>incremental-compiler-maven-extension</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </extension>
</extensions>
```

With this in place, `IncrementalCompileExecutionListener` automatically intercepts `compile` and `testCompile` executions of both `maven-compiler-plugin` and `tycho-compiler-plugin` across the entire reactor.

### Option 2: As a plugin extension in your `pom.xml`

```xml
<build>
  <plugins>
    <!-- Standard Maven modules -->
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-compiler-plugin</artifactId>
      <version>3.13.0</version>
      <extensions>true</extensions>
      <dependencies>
        <dependency>
          <groupId>eu.munier.maven.extensions</groupId>
          <artifactId>incremental-compiler-maven-extension</artifactId>
          <version>1.0.0-SNAPSHOT</version>
        </dependency>
      </dependencies>
    </plugin>

    <!-- Optional: If Eclipse Tycho is also used in the reactor -->
    <plugin>
      <groupId>org.eclipse.tycho</groupId>
      <artifactId>tycho-compiler-plugin</artifactId>
      <version>${tycho.version}</version>
      <extensions>true</extensions>
      <dependencies>
        <dependency>
          <groupId>eu.munier.maven.extensions</groupId>
          <artifactId>incremental-compiler-maven-extension</artifactId>
          <version>1.0.0-SNAPSHOT</version>
        </dependency>
      </dependencies>
    </plugin>
  </plugins>
</build>
```

---

## Sample console output

During `compile` and `testCompile`, the extension logs exact source counts and percentages for full builds, multi-round incremental builds, and no-op runs:

```text
# Initial build (or after compiler option changes)
[INFO] [IncrementalCompiler] Full build (initial build (no previous incremental state)): compiling 500 of 500 source file(s) (100.0%), kept 0 unchanged (0.0%) in .../target/classes

# Subsequent build with no changes (or touched files with identical SHA-256 content)
[INFO] [IncrementalCompiler] Up-to-date: compiled 0 of 500 source file(s) (0.0%), kept 500 unchanged (100.0%) in .../target/classes

# Incremental build after modifying a class whose public signature changed
[INFO] [IncrementalCompiler] Round 1: compiling 1 of 500 source file(s) (direct/upstream changes) in .../target/classes
[INFO] [IncrementalCompiler] Round 2: compiling 2 of 500 source file(s) (ABI-affected dependents) in .../target/classes
[INFO] [IncrementalCompiler] Summary: compiled 3 of 500 source file(s) (0.6%) in 2 round(s), kept 497 unchanged (99.4%), deleted 3 stale class file(s) in .../target/classes
```

---

## Configuration properties

| Property | Default | Description |
|---|---|---|
| `-Dmaven.compiler.incremental` | `true` | Set to `false` (via CLI `-Dmaven.compiler.incremental=false` or `<properties>` in `pom.xml`) to temporarily disable class-level incremental compilation and fall back to default plugin behavior. |

---

## License

This project is licensed under the [MIT License](LICENSE).
