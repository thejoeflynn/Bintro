package com.bintro.matching;

/**
 * How a clip was matched to a scene.
 *
 * <ul>
 *   <li>{@link #DIALOGUE} — matched by transcript word overlap (the default
 *       and only current path).</li>
 *   <li>{@link #VISUAL} — visual / insert shot with no dialogue; future
 *       feature. The script renderer already understands this case and
 *       highlights the scene heading instead of words in the body.</li>
 * </ul>
 */
public enum MatchType {
    DIALOGUE,
    VISUAL
}
