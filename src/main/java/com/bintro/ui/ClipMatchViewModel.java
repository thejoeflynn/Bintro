package com.bintro.ui;

import com.bintro.media.Clip;
import com.bintro.parser.Scene;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.List;

/**
 * View-model for one row of the clip-match review table.
 *
 * <p>Wraps a {@link Clip}, the transcript text, and the matched scene number in
 * JavaFX properties so a {@code TableView} can bind to them. The
 * {@link #sceneHeadingProperty()} is derived from {@link #sceneNumberProperty()}
 * via lookup into the supplied {@code scenes} list, and updates automatically
 * whenever the user edits the scene number.
 */
public class ClipMatchViewModel {

    /** Heading text shown when the scene number is 0 (no match). */
    public static final String UNMATCHED = "Unmatched";

    /** Heading text shown when the scene number doesn't exist in the script. */
    public static final String UNKNOWN = "Unknown";

    private final Clip clip;
    private final StringProperty filename;
    private final StringProperty transcript;
    private final IntegerProperty sceneNumber;
    private final StringProperty sceneHeading;
    private final List<Scene> scenes;

    public ClipMatchViewModel(Clip clip, String transcript, int sceneNumber, List<Scene> scenes) {
        this.clip = clip;
        this.scenes = scenes == null ? List.of() : scenes;
        this.filename = new SimpleStringProperty(clip == null ? "" : clip.filename());
        this.transcript = new SimpleStringProperty(transcript == null ? "" : transcript);
        this.sceneNumber = new SimpleIntegerProperty(sceneNumber);
        this.sceneHeading = new SimpleStringProperty(lookupHeading(sceneNumber));
        this.sceneNumber.addListener((obs, oldV, newV) ->
            this.sceneHeading.set(lookupHeading(newV == null ? 0 : newV.intValue())));
    }

    private String lookupHeading(int n) {
        if (n <= 0) {
            return UNMATCHED;
        }
        for (Scene s : scenes) {
            if (s.sceneNumber() == n) {
                return s.heading();
            }
        }
        return UNKNOWN;
    }

    public Clip clip() {
        return clip;
    }

    public int sceneNumber() {
        return sceneNumber.get();
    }

    public StringProperty filenameProperty() {
        return filename;
    }

    public StringProperty transcriptProperty() {
        return transcript;
    }

    public IntegerProperty sceneNumberProperty() {
        return sceneNumber;
    }

    public StringProperty sceneHeadingProperty() {
        return sceneHeading;
    }
}
