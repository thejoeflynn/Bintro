package com.bintro.matching;

import com.bintro.Config;
import com.bintro.media.Clip;
import com.bintro.parser.Scene;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Matches a clip transcript to a screenplay scene via an OpenAI-compatible
 * chat-completions endpoint — by default the Msty app running locally at
 * {@code http://localhost:11434/v1}.
 *
 * <p>The request body is hand-rolled JSON to avoid adding a JSON dependency.
 * On any error (backend not reachable, non-2xx, parse failure) the matcher
 * silently falls back to {@link LocalSceneMatcher} so the pipeline always
 * makes forward progress.
 */
public class SceneMatcher {

    private static final String CHAT_PATH = "/chat/completions";
    private static final int MAX_SCENE_PREVIEW_CHARS = 200;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    public MatchResult match(Clip clip, String transcript, List<Scene> scenes) {
        System.out.println("SceneMatcher: matching clip '" + clip.filename()
            + "' against " + scenes.size() + " scenes, transcript length="
            + (transcript == null ? 0 : transcript.length()) + " chars");

        String backendUrl = Config.get("ai.backend.url");
        String model = Config.get("ai.match.model");
        if (backendUrl == null || backendUrl.isBlank()
            || model == null || model.isBlank()) {
            System.out.println("SceneMatcher: ai.backend.url or ai.match.model unset — "
                + "using local keyword matcher");
            return new LocalSceneMatcher().match(transcript, scenes);
        }
        System.out.println("SceneMatcher: using Msty backend at " + backendUrl
            + " with model " + model);

        try {
            String prompt = buildPrompt(transcript, scenes);
            String body = buildRequestBody(model, prompt);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(joinUrl(backendUrl, CHAT_PATH)))
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                System.err.println("SceneMatcher: HTTP " + resp.statusCode()
                    + " for clip '" + clip.filename() + "' — falling back to keyword match");
                System.err.println("SceneMatcher: response body: " + resp.body());
                return new LocalSceneMatcher().match(transcript, scenes);
            }

            int sceneNumber = extractSceneNumber(resp.body());
            System.out.println("SceneMatcher: clip '" + clip.filename() + "' -> scene " + sceneNumber);
            return sceneNumber > 0
                ? new MatchResult(sceneNumber, 1.0)
                : unmatched();
        } catch (Exception e) {
            System.err.println("SceneMatcher: Msty not reachable at " + backendUrl
                + " — falling back to keyword match (" + e.getClass().getSimpleName()
                + ": " + e.getMessage() + ")");
            return new LocalSceneMatcher().match(transcript, scenes);
        }
    }

    private static MatchResult unmatched() {
        return new MatchResult(0, 0.0);
    }

    static String joinUrl(String base, String path) {
        if (base.endsWith("/") && path.startsWith("/")) {
            return base + path.substring(1);
        }
        if (!base.endsWith("/") && !path.startsWith("/")) {
            return base + "/" + path;
        }
        return base + path;
    }

    private static String buildPrompt(String transcript, List<Scene> scenes) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are helping a film editor sort raw footage into screenplay scenes.\n\n");
        sb.append("Footage transcript:\n");
        sb.append("---\n");
        sb.append(transcript == null ? "" : transcript.strip());
        sb.append("\n---\n\n");
        sb.append("Candidate scenes:\n");
        for (Scene s : scenes) {
            sb.append(s.sceneNumber()).append(". ").append(s.heading()).append('\n');
            String body = s.fullText() == null ? "" : s.fullText();
            String preview = body.length() > MAX_SCENE_PREVIEW_CHARS
                ? body.substring(0, MAX_SCENE_PREVIEW_CHARS) + "…"
                : body;
            sb.append(preview).append("\n\n");
        }
        sb.append("Which scene number best matches the footage transcript? ");
        sb.append("Respond with ONLY a single integer — the scene number, or 0 if no scene matches.");
        return sb.toString();
    }

    private static String buildRequestBody(String model, String prompt) {
        return "{"
            + "\"model\":" + jsonString(model) + ","
            + "\"messages\":[{\"role\":\"user\",\"content\":" + jsonString(prompt) + "}],"
            + "\"max_tokens\":16,"
            + "\"temperature\":0.1"
            + "}";
    }

    /**
     * Extracts the integer from the first {@code "content":"…"} field in an
     * OpenAI-style chat-completions response. Tolerant of leading/trailing
     * non-digit characters (e.g. {@code "Scene 3"} → {@code 3}).
     */
    static int extractSceneNumber(String responseJson) {
        int idx = responseJson.indexOf("\"content\":");
        while (idx >= 0) {
            int quoteStart = responseJson.indexOf('"', idx + "\"content\":".length());
            if (quoteStart < 0) {
                break;
            }
            int quoteEnd = findClosingQuote(responseJson, quoteStart + 1);
            if (quoteEnd < 0) {
                break;
            }
            String text = unescape(responseJson.substring(quoteStart + 1, quoteEnd));
            Integer n = firstInteger(text);
            if (n != null) {
                return n;
            }
            idx = responseJson.indexOf("\"content\":", quoteEnd);
        }
        return 0;
    }

    private static int findClosingQuote(String json, int from) {
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++; // skip escaped char
                continue;
            }
            if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static Integer firstInteger(String s) {
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                num.append(c);
            } else if (num.length() > 0) {
                break;
            }
        }
        if (num.length() == 0) {
            return null;
        }
        try {
            return Integer.parseInt(num.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
