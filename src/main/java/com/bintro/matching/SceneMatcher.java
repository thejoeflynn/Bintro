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
 * Matches a clip transcript to a screenplay scene via the Claude Messages API.
 *
 * <p>The request body is hand-rolled JSON to avoid adding a JSON dependency.
 * On any error (network, non-2xx, parse failure) the matcher returns
 * {@link MatchResult}({@code 0}, {@code 0.0}) — "Unmatched".
 */
public class SceneMatcher {

    private static final String ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-haiku-4-5-20251001";
    private static final String API_VERSION = "2023-06-01";
    private static final int MAX_SCENE_PREVIEW_CHARS = 200;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    public MatchResult match(Clip clip, String transcript, List<Scene> scenes) {
        System.out.println("SceneMatcher: matching clip '" + clip.filename()
            + "' against " + scenes.size() + " scenes, transcript length="
            + (transcript == null ? 0 : transcript.length()) + " chars");

        String apiKey = Config.get("claude.api.key");
        if (apiKey == null || apiKey.isBlank() || "YOUR_CLAUDE_API_KEY_HERE".equals(apiKey)) {
            System.err.println("SceneMatcher: claude.api.key is missing or unset — returning Unmatched");
            return unmatched();
        }

        try {
            String prompt = buildPrompt(transcript, scenes);
            String body = buildRequestBody(prompt);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                System.err.println("SceneMatcher: HTTP " + resp.statusCode()
                    + " for clip '" + clip.filename() + "'");
                System.err.println("SceneMatcher: response body: " + resp.body());
                return unmatched();
            }

            int sceneNumber = extractSceneNumber(resp.body());
            System.out.println("SceneMatcher: clip '" + clip.filename() + "' -> scene " + sceneNumber);
            return sceneNumber > 0
                ? new MatchResult(sceneNumber, 1.0)
                : unmatched();
        } catch (Exception e) {
            System.err.println("SceneMatcher: exception while matching '" + clip.filename()
                + "': " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
            return unmatched();
        }
    }

    private static MatchResult unmatched() {
        return new MatchResult(0, 0.0);
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

    private static String buildRequestBody(String prompt) {
        return "{"
            + "\"model\":" + jsonString(MODEL) + ","
            + "\"max_tokens\":16,"
            + "\"messages\":[{\"role\":\"user\",\"content\":" + jsonString(prompt) + "}]"
            + "}";
    }

    /**
     * Extracts the integer from the first {@code "text":"…"} field in a Messages
     * API response. Tolerant of leading/trailing non-digit characters in the
     * content (e.g. {@code "Scene 3"} → {@code 3}).
     */
    static int extractSceneNumber(String responseJson) {
        int textIdx = responseJson.indexOf("\"text\":");
        while (textIdx >= 0) {
            int quoteStart = responseJson.indexOf('"', textIdx + 7);
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
            textIdx = responseJson.indexOf("\"text\":", quoteEnd);
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

    private static Integer firstInteger(String s) {
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

    private static String jsonString(String s) {
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
