package com.bintro.parser;

/**
 * A single structured line of a screenplay, tagged by element type so the
 * UI can format it the way a printed script would (centred character cues,
 * indented dialogue, etc.) and so per-element gutter-bar spans can be
 * computed.
 */
public record ScriptElement(ElementType type, String text) {

    public enum ElementType {
        SCENE_HEADING,
        ACTION,
        CHARACTER,
        DIALOGUE,
        PARENTHETICAL,
        TRANSITION,
        OTHER
    }
}
