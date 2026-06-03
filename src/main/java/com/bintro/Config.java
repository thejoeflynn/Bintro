package com.bintro;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Static accessor for {@code config.properties}.
 *
 * <p>Lookup order on first access:
 * <ol>
 *   <li>{@code $user.dir/config.properties} — the JVM's working directory.</li>
 *   <li>The directory containing the running JAR (for packaged distributions).</li>
 *   <li>Classpath fallback ({@code /config.properties}).</li>
 * </ol>
 *
 * <p>Each step is logged to stderr so misses are visible. Returns {@code null}
 * for missing keys.
 */
public final class Config {

    private static volatile Properties props;

    private Config() {
    }

    public static String get(String key) {
        return ensureLoaded().getProperty(key);
    }

    /** Returns the value or {@code defaultValue} when the key is missing. */
    public static String get(String key, String defaultValue) {
        String v = ensureLoaded().getProperty(key);
        return v != null ? v : defaultValue;
    }

    /** Test/diagnostic hook — drops the cached properties so the next get() re-reads. */
    static synchronized void reset() {
        props = null;
    }

    private static Properties ensureLoaded() {
        Properties p = props;
        if (p != null) {
            return p;
        }
        synchronized (Config.class) {
            if (props != null) {
                return props;
            }
            Properties loaded = new Properties();

            // 1. user.dir
            Path userDir = Path.of(System.getProperty("user.dir", "."), "config.properties");
            if (tryLoadFromFile(loaded, userDir)) {
                props = loaded;
                return loaded;
            }

            // 2. JAR dir
            Path jarDir = jarDirConfigPath();
            if (jarDir != null && tryLoadFromFile(loaded, jarDir)) {
                props = loaded;
                return loaded;
            }

            // 3. classpath
            tryLoadFromClasspath(loaded);

            props = loaded;
            return loaded;
        }
    }

    private static boolean tryLoadFromFile(Properties target, Path path) {
        System.err.println("Config: looking for config.properties at " + path.toAbsolutePath());
        if (!Files.isRegularFile(path)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(path)) {
            target.load(in);
            System.err.println("Config: loaded " + target.size() + " properties from " + path.toAbsolutePath());
            return true;
        } catch (IOException e) {
            System.err.println("Config: failed to read " + path.toAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    private static void tryLoadFromClasspath(Properties target) {
        System.err.println("Config: looking for config.properties on classpath at /config.properties");
        try (InputStream in = Config.class.getResourceAsStream("/config.properties")) {
            if (in == null) {
                System.err.println("Config: classpath /config.properties not found");
                return;
            }
            target.load(in);
            System.err.println("Config: loaded " + target.size() + " properties from classpath /config.properties");
        } catch (IOException e) {
            System.err.println("Config: classpath load failed: " + e.getMessage());
        }
    }

    private static Path jarDirConfigPath() {
        try {
            URL loc = Config.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc == null) {
                return null;
            }
            Path codePath = Path.of(loc.toURI());
            Path dir = Files.isDirectory(codePath) ? codePath : codePath.getParent();
            return dir == null ? null : dir.resolve("config.properties");
        } catch (Exception e) {
            return null;
        }
    }
}
