# Feature 22 — Java 17 + Maven Port (Separate Branch)

> Paste this into a Claude Code session opened at the Bintro project root.
> This creates a separate branch — the existing Java 21/Gradle version is
> untouched on `main`.

---

## Goal

Create a parallel version of Bintro on a new git branch that uses:
- **Java 17** (LTS, widely supported)
- **Maven** (instead of Gradle)
- **JavaFX 17** (matches Java 17)

No functional changes — same app, same features, different build system.

---

## Step 0 — Create the branch

```bash
git checkout -b java17-maven
```

---

## Step 1 — Remove Gradle files

Delete these files (they have no Maven equivalent):
```
build.gradle
settings.gradle
gradlew
gradlew.bat
gradle/
.gradle/        ← if present
```

```bash
rm -rf build.gradle settings.gradle gradlew gradlew.bat gradle/ .gradle/
```

---

## Step 2 — Create `pom.xml`

Create `pom.xml` at the project root:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
             http://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>
    <groupId>com.bintro</groupId>
    <artifactId>bintro</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <javafx.version>17.0.11</javafx.version>
    </properties>

    <dependencies>

        <!-- JavaFX -->
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-controls</artifactId>
            <version>${javafx.version}</version>
        </dependency>
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-fxml</artifactId>
            <version>${javafx.version}</version>
        </dependency>
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-swing</artifactId>
            <version>${javafx.version}</version>
        </dependency>
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-web</artifactId>
            <version>${javafx.version}</version>
        </dependency>
        <dependency>
            <groupId>org.openjfx</groupId>
            <artifactId>javafx-media</artifactId>
            <version>${javafx.version}</version>
        </dependency>

        <!-- Video processing -->
        <dependency>
            <groupId>org.bytedeco</groupId>
            <artifactId>javacv-platform</artifactId>
            <version>1.5.10</version>
        </dependency>

        <!-- PDF parsing -->
        <dependency>
            <groupId>org.apache.pdfbox</groupId>
            <artifactId>pdfbox</artifactId>
            <version>3.0.5</version>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.3</version>
            <scope>test</scope>
        </dependency>

    </dependencies>

    <build>
        <plugins>

            <!-- Compiler — enforce Java 17 -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <source>17</source>
                    <target>17</target>
                </configuration>
            </plugin>

            <!-- Run the app: mvn javafx:run -->
            <plugin>
                <groupId>org.openjfx</groupId>
                <artifactId>javafx-maven-plugin</artifactId>
                <version>0.0.8</version>
                <configuration>
                    <mainClass>com.bintro.App</mainClass>
                </configuration>
            </plugin>

            <!-- Fat JAR for distribution: mvn package -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.5.2</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <transformers>
                                <transformer implementation=
                                  "org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>com.bintro.App</mainClass>
                                </transformer>
                            </transformers>
                            <filters>
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                        </configuration>
                    </execution>
                </executions>
            </plugin>

            <!-- Tests -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>

        </plugins>
    </build>

</project>
```

---

## Step 3 — Verify Java 17 compatibility in source files

Scan all `.java` files in `src/` for any Java 21-only features and fix them.

**Known safe in Java 17 (no changes needed):**
- Records (`record Clip(...)`) — Java 16+
- Sealed classes (`sealed interface`) — Java 17+
- Switch expressions (`switch(x) { case A -> ... }`) — Java 14+
- Text blocks (`"""..."""`) — Java 15+
- Pattern matching instanceof (`if (x instanceof String s)`) — Java 16+
- `HttpClient`, `Stream`, `var` — all pre-17

**Requires Java 21 — fix if found:**
- Switch pattern matching on types: `case String s when s.isEmpty()` — needs Java 21
  - Fix: replace with if-else chains or traditional instanceof checks

Run a search:
```bash
grep -rn "case.*when" src/main/java/
```

If any hits: rewrite those switch branches as if/else.

---

## Step 4 — Update `.gitignore`

Add Maven build output, remove Gradle entries:

Remove:
```
.gradle/
build/
```

Add:
```
target/
```

The final `.gitignore` should include both `build/` (keep for safety) and `target/`.

---

## Step 5 — Add `mvnw` wrapper (optional but recommended)

```bash
mvn wrapper:wrapper
```

This creates `mvnw`, `mvnw.cmd`, and `.mvn/` — equivalent to `gradlew`.

If Maven itself isn't installed:
```bash
brew install maven
```

---

## Step 6 — Verify the build

```bash
mvn compile
```

Fix any compilation errors. Common issues:
- Module path problems with JavaFX — the javafx-maven-plugin handles this automatically for `mvn javafx:run`, but plain `mvn compile` may warn. This is expected; `mvn javafx:run` is the correct run command.
- `javacv-platform` is a large download on first run — this is normal.

```bash
mvn javafx:run
```

App should launch identically to `./gradlew run`.

---

## Step 7 — Commit

```bash
git add pom.xml .gitignore
git commit -m "chore: migrate build to Maven, target Java 17"
```

---

## Maven command reference

| Gradle command         | Maven equivalent        |
|------------------------|-------------------------|
| `./gradlew run`        | `mvn javafx:run`        |
| `./gradlew compileJava`| `mvn compile`           |
| `./gradlew test`       | `mvn test`              |
| `./gradlew build`      | `mvn package`           |
| `./gradlew clean`      | `mvn clean`             |
| `./gradlew clean run`  | `mvn clean javafx:run`  |

---

## Constraints

- No functional changes to any `.java` source file beyond Java 21 → 17 fixes
- No new features in this branch — build system migration only
- The `main` branch (Java 21 / Gradle) is not touched
- `config.properties` and `whisper/` are identical between branches

## Done when

- `pom.xml` exists at project root
- `build.gradle` and `settings.gradle` are deleted
- `mvn compile` succeeds with no errors
- `mvn javafx:run` launches the app
- Git history shows a clean `java17-maven` branch off `main`
