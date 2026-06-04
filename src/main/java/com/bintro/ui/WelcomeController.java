package com.bintro.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * Drives the launch-screen window: dark background, branded logo, and the
 * New Project / Open Project entry points. New Project opens the full
 * MainView in a new {@code Stage} and closes the welcome window.
 */
public class WelcomeController {

    private static final String LOGO_RESOURCE = "/images/bintro-logo.png";
    private static final String MAIN_FXML = "/fxml/MainView.fxml";
    private static final String THEME_CSS = "/css/theme.css";

    @FXML private ImageView logoImage;
    @FXML private Button newProjectButton;
    @FXML private Button openProjectButton;

    @FXML
    private void initialize() {
        // Stream-based loading is more reliable than URL-based when the
        // resource lives inside a packaged JAR.
        try (InputStream in = getClass().getResourceAsStream(LOGO_RESOURCE)) {
            if (in == null) {
                System.err.println("WelcomeController: logo resource not found at "
                    + LOGO_RESOURCE);
                return;
            }
            logoImage.setImage(new Image(in));
        } catch (Exception e) {
            System.err.println("WelcomeController: failed to load logo: " + e);
        }
    }

    @FXML
    private void onNewProject() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(MAIN_FXML));
            Parent root = loader.load();
            Stage mainStage = new Stage();
            mainStage.setTitle("Bintro");
            Scene scene = new Scene(root, 900, 600);
            applyTheme(scene);
            mainStage.setScene(scene);
            mainStage.show();

            // Close the welcome window once the main one is up.
            Stage welcomeStage = (Stage) newProjectButton.getScene().getWindow();
            welcomeStage.close();
        } catch (IOException e) {
            System.err.println("WelcomeController: failed to open main window: " + e);
            e.printStackTrace();
        }
    }

    @FXML
    private void onOpenProject() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Bintro Project");
        chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter("Bintro Project", "*.bintro")
        );
        File chosen = chooser.showOpenDialog(openProjectButton.getScene().getWindow());
        if (chosen != null) {
            // Stub — project file loading lands in a later feature.
            System.out.println("WelcomeController: open project (stub): "
                + chosen.getAbsolutePath());
        }
    }

    private void applyTheme(Scene scene) {
        URL css = getClass().getResource(THEME_CSS);
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
    }
}
