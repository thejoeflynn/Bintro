package com.bintro;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.InputStream;
import java.net.URL;

public class App extends Application {

    private static final String WELCOME_FXML = "/fxml/WelcomeView.fxml";
    private static final String THEME_CSS = "/css/theme.css";

    @Override
    public void start(Stage stage) throws Exception {
        loadFragmentCore();

        Parent root = FXMLLoader.load(App.class.getResource(WELCOME_FXML));
        Scene scene = new Scene(root, 480, 400);
        URL css = App.class.getResource(THEME_CSS);
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
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
