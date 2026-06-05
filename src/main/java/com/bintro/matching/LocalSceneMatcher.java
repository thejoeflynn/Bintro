package com.bintro.matching;

import com.bintro.parser.Scene;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Offline keyword-overlap matcher used when no AI backend is reachable.
 * Scores each scene by the number of non-stopword tokens it shares with the
 * transcript and returns the highest-scoring scene.
 */
public class LocalSceneMatcher {

    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "of",
        "is", "it", "he", "she", "they", "we", "you", "i", "was", "be", "are",
        "with", "for", "his", "her", "that", "this", "have", "had", "not",
        "from", "as", "do", "so", "my", "me", "him", "us", "its", "by", "if", "up"
    );

    public MatchResult match(String transcript, List<Scene> scenes) {
        Set<String> transcriptWords = tokenise(transcript);
        if (transcriptWords.isEmpty() || scenes == null || scenes.isEmpty()) {
            return new MatchResult(0, 0.0);
        }

        Scene bestScene = null;
        int bestScore = 0;
        for (Scene s : scenes) {
            String combined = (s.heading() == null ? "" : s.heading())
                + " " + (s.fullText() == null ? "" : s.fullText());
            Set<String> sceneWords = tokenise(combined);

            int score = 0;
            for (String w : transcriptWords) {
                if (sceneWords.contains(w)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestScene = s;
            }
        }

        System.out.println("LocalSceneMatcher: transcript words=" + transcriptWords.size()
            + ", best scene=" + (bestScene == null ? 0 : bestScene.sceneNumber())
            + ", score=" + bestScore);

        if (bestScore == 0 || bestScene == null) {
            return new MatchResult(0, 0.0);
        }
        double confidence = Math.min(1.0, (double) bestScore / transcriptWords.size());
        return new MatchResult(bestScene.sceneNumber(), confidence);
    }

    private static Set<String> tokenise(String text) {
        Set<String> words = new HashSet<>();
        if (text == null || text.isBlank()) {
            return words;
        }
        String cleaned = text.toLowerCase().replaceAll("[^\\p{L}\\p{Nd}\\s]", " ");
        for (String token : cleaned.split("\\s+")) {
            if (token.isEmpty() || STOP_WORDS.contains(token)) {
                continue;
            }
            words.add(token);
        }
        return words;
    }
}
