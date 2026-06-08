package com.bintro;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.InputStream;
import java.net.URL;
import java.util.Set;

public class App extends Application {

    private static final String WELCOME_FXML = "/fxml/WelcomeView.fxml";
    private static final String THEME_CSS = "/css/theme.css";

    private static final Set<String> KNOWN_THEMES = Set.of(
        "light", "dark", "navy", "forest");

    /**
     * Active theme name (process-wide). Updated by the theme menu so newly
     * spawned main windows pick up the same look as the existing one.
     */
    private static volatile String activeTheme = "light";

    public static String getActiveTheme() {
        return activeTheme;
    }

    public static void setActiveTheme(String theme) {
        if (theme != null && KNOWN_THEMES.contains(theme)) {
            activeTheme = theme;
        }
    }

    /**
     * Applies the active theme's class to a scene root, removing any prior
     * theme class first so theme changes are idempotent.
     */
    public static void applyActiveTheme(Scene scene) {
        if (scene == null || scene.getRoot() == null) {
            return;
        }
        Parent root = scene.getRoot();
        root.getStyleClass().removeAll("theme-light", "theme-dark",
            "theme-navy", "theme-forest");
        if (!"light".equals(activeTheme)) {
            // The base palette IS the Light theme — only non-default themes
            // need an explicit class.
            root.getStyleClass().add("theme-" + activeTheme);
        }
        // JavaFX *should* re-apply CSS automatically when style classes
        // change, but a forced reapplication makes the swap immediate even
        // when descendant rules are gated on the root's class set.
        root.applyCss();
    }

    /**
     * Attaches the theme stylesheet to a scene if it isn't already present.
     * Safe to call multiple times.
     */
    public static void attachThemeStylesheet(Scene scene) {
        if (scene == null) {
            return;
        }
        URL css = App.class.getResource(THEME_CSS);
        if (css == null) {
            return;
        }
        String href = css.toExternalForm();
        if (!scene.getStylesheets().contains(href)) {
            scene.getStylesheets().add(href);
        }
    }

    @Override
    public void start(Stage stage) throws Exception {
        loadFragmentCore();

        Parent root = FXMLLoader.load(App.class.getResource(WELCOME_FXML));
        Scene scene = new Scene(root, 480, 400);
        attachThemeStylesheet(scene);
        // Welcome screen has its own brand-locked dark styling; the theme
        // class still gets applied so any class-styled child controls render
        // consistently if they appear later.
        applyActiveTheme(scene);
        stage.setTitle("Bintro");
        stage.setResizable(false);
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Loads the FragmentCore brand font from the classpath if present. The
     * font is treated as an optional asset — the app launches normally with
     * the CSS fallback chain if the file is missing.
     */
    private static void loadFragmentCore() {
        String[] candidates = {"/fonts/FragmentCore.ttf", "/fonts/FragmentCore.otf"};
        for (String path : candidates) {
            try (InputStream in = App.class.getResourceAsStream(path)) {
                if (in != null) {
                    Font.loadFont(in, 12);
                    return;
                }
            } catch (Exception ignored) {
                // Ignore — fall through to the next candidate / CSS fallback.
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
