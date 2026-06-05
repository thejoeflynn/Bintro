package com.bintro.export;

import com.bintro.media.Clip;

/**
 * One row of the export plan: a clip, its assigned scene number, and an
 * optional pre-computed export base name (without extension). When
 * {@code exportName} is null/blank, {@link Exporter} computes one from the
 * scene number + per-scene counter.
 */
public record ClipMatch(Clip clip, int sceneNumber, String exportName) {

    /** Back-compat constructor — exporter computes the name. */
    public ClipMatch(Clip clip, int sceneNumber) {
        this(clip, sceneNumber, null);
    }
}
