package com.bintro.parser;

import java.util.List;

/**
 * One screenplay scene.
 *
 * <p>{@code heading} and {@code fullText} are kept byte-identical to the
 * pre-Feature-10 shape (Action+Dialogue for FDX, non-slugline lines for PDF)
 * so {@code LocalSceneMatcher}, the Claude prompt builder and existing parser
 * tests don't change behaviour. {@code elements} is a parallel structured
 * view used by {@code ScriptRenderer} for proper screenplay formatting and
 * per-element gutter-bar spans.
 */
public record Scene(int sceneNumber, String heading, String fullText, List<ScriptElement> elements) {

    /**
     * Back-compat constructor for callers that don't yet supply structured
     * elements — the renderer falls back to treating the scene as a single
     * action block.
     */
    public Scene(int sceneNumber, String heading, String fullText) {
        this(sceneNumber, heading, fullText, List.of());
    }
}
