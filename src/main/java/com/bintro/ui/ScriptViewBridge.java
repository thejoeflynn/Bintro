package com.bintro.ui;

import javafx.application.Platform;
import javafx.scene.control.TableView;

/**
 * Java object exposed on the WebView's {@code window} as {@code window.bintro}.
 * The script viewer's inline {@code <script>} calls back into this class when
 * a user clicks a gutter bar.
 *
 * <p>The bridge holds a reference to the {@code TableView} rather than a
 * snapshot of the rows, so it always resolves filenames against the current
 * items list — including after re-renders following scene-number edits.
 */
public class ScriptViewBridge {

    private final TableView<ClipMatchViewModel> table;

    public ScriptViewBridge(TableView<ClipMatchViewModel> table) {
        this.table = table;
    }

    /**
     * Called from JS as {@code window.bintro.selectClip(filename)}.
     * Selects the matching row in the clip table and scrolls it into view.
     * Wrapped in {@code Platform.runLater} so callers can invoke it from any
     * thread the JS engine happens to dispatch from.
     */
    public void selectClip(String filename) {
        if (filename == null) {
            return;
        }
        Platform.runLater(() -> {
            for (ClipMatchViewModel vm : table.getItems()) {
                if (filename.equals(vm.getFilename())) {
                    table.getSelectionModel().select(vm);
                    table.scrollTo(vm);
                    return;
                }
            }
        });
    }
}
