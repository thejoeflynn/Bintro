package com.bintro;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Static accessor for {@code config.properties}.
 * <p>Loaded once on first access; lookups are project-root first, then classpath.
 * Returns {@code null} for missing keys — callers decide how to handle.
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
            Path file = Path.of("config.properties");
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    loaded.load(in);
                } catch (IOException e) {
                    System.err.println("Config: failed to read " + file.toAbsolutePath() + ": " + e.getMessage());
                }
            } else {
                try (InputStream in = Config.class.getResourceAsStream("/config.properties")) {
                    if (in != null) {
                        loaded.load(in);
                    }
                } catch (IOException e) {
                    System.err.println("Config: failed to read classpath config.properties: " + e.getMessage());
                }
            }
            props = loaded;
            return loaded;
        }
    }
}
