package com.bintro.matching;

import com.bintro.Config;
import com.bintro.media.Clip;
import com.bintro.parser.Scene;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Visual-only scene matcher for clips with no usable dialogue (B-roll, insert
 * shots, scenic plates). Extracts a handful of evenly-spaced key frames with
 * JavaCV's {@link FFmpegFrameGrabber}, base64-encodes them as JPEGs, and ships
 * them to an OpenAI-compatible vision model running locally in Msty (default
 * Gemma 4).
 *
 * <p>On any error — backend unreachable, frame extraction fails, response not
 * parseable — returns {@link MatchResult}({@code 0}, {@code 0.0}) so the
 * clip is flagged Unmatched and the pipeline keeps going.
 */
public class VisualMatcher {

    private static final String CHAT_PATH = "/chat/completions";
    private static final int DEFAULT_FRAME_COUNT = 4;
    private static final int MAX_SCENE_PREVIEW_CHARS = 200;
    private static final int MAX_TOKENS = 200;
    /** JPEG encoding quality; PDFBox-rendered frames don't need lossless. */
    private static final float JPEG_QUALITY = 0.85f;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    public MatchResult match(Clip clip, List<Scene> scenes) throws IOException {
        System.out.println("VisualMatcher: matching clip '" + clip.filename()
            + "' against " + scenes.size() + " scenes (no dialogue)");

        String backendUrl = Config.get("ai.backend.url");
        String model = Config.get("ai.visual.model");
        if (backendUrl == null || backendUrl.isBlank()
            || model == null || model.isBlank()) {
            System.err.println("VisualMatcher: ai.backend.url or ai.visual.model "
                + "unset — returning Unmatched");
            return unmatched();
        }
        int frameCount = parseIntDefault(Config.get("ai.visual.frames"), DEFAULT_FRAME_COUNT);

        List<String> framesBase64;
        try {
            framesBase64 = extractFramesBase64(clip, frameCount);
        } catch (Exception e) {
            System.err.println("VisualMatcher: frame extraction failed for '"
                + clip.filename() + "': " + e.getClass().getSimpleName() + ": "
                + e.getMessage());
            return unmatched();
        }
        if (framesBase64.isEmpty()) {
            System.err.println("VisualMatcher: no frames extracted from '"
                + clip.filename() + "' — returning Unmatched");
            return unmatched();
        }
        System.out.println("VisualMatcher: extracted " + framesBase64.size()
            + " frame(s) from '" + clip.filename() + "'");

        String prompt = buildPrompt(scenes);
        String body = buildRequestBody(model, prompt, framesBase64);

        try {
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SceneMatcher.joinUrl(backendUrl, CHAT_PATH)))
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                System.err.println("VisualMatcher: HTTP " + resp.statusCode()
                    + " for clip '" + clip.filename() + "'");
                System.err.println("VisualMatcher: response body: " + resp.body());
                return unmatched();
            }
            int sceneNumber = extractSceneNumberLast(resp.body());
            System.out.println("VisualMatcher: clip '" + clip.filename()
                + "' -> scene " + sceneNumber);
            return sceneNumber > 0
                ? new MatchResult(sceneNumber, 1.0)
                : unmatched();
        } catch (Exception e) {
            System.err.println("VisualMatcher: Msty not reachable at " + backendUrl
                + " — returning Unmatched (" + e.getClass().getSimpleName()
                + ": " + e.getMessage() + ")");
            return unmatched();
        }
    }

    /**
     * Grabs {@code frameCount} evenly-spaced frames from the clip and
     * returns them as base64-encoded JPEG strings. Best-effort: any single
     * frame that fails to grab is skipped rather than aborting the batch.
     */
    static List<String> extractFramesBase64(Clip clip, int frameCount) throws IOException {
        List<String> out = new ArrayList<>(frameCount);
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(clip.file())) {
            grabber.start();
            long totalFrames = grabber.getLengthInFrames();
            if (totalFrames <= 0) {
                return out;
            }
            try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
                for (int i = 0; i < frameCount; i++) {
                    long frameNum = (totalFrames / (long) (frameCount + 1)) * (i + 1);
                    try {
                        grabber.setFrameNumber((int) Math.min(frameNum, totalFrames - 1));
                        Frame frame = grabber.grabImage();
                        if (frame == null) {
                            continue;
                        }
                        BufferedImage img = converter.convert(frame);
                        if (img == null) {
                            continue;
                        }
                        String base64 = encodeJpegBase64(img);
                        if (base64 != null) {
                            out.add(base64);
                        }
                    } catch (Exception e) {
                        // Skip this frame; keep collecting the rest.
                        System.err.println("VisualMatcher: failed to grab frame "
                            + frameNum + " of '" + clip.filename() + "': "
                            + e.getMessage());
                    }
                }
            }
            grabber.stop();
        }
        return out;
    }

    private static String encodeJpegBase64(BufferedImage img) throws IOException {
        // JPEG can't encode an alpha channel; force RGB.
        BufferedImage rgb = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(img, 0, 0, null);
        }
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024)) {
            // ImageIO's default JPEG writer respects quality via writeParam,
            // but the simple write() path is sufficient for vision-LM input.
            if (!ImageIO.write(rgb, "jpg", baos)) {
                return null;
            }
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

    private static String buildPrompt(List<Scene> scenes) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are helping a film editor sort raw footage into screenplay scenes.\n\n");
        sb.append("These frames are from a video clip with no dialogue.\n");
        sb.append("Describe in 1-2 sentences what is visually happening: the setting, ");
        sb.append("any visible characters, actions, and mood.\n\n");
        sb.append("Then, given these screenplay scenes, which scene number best matches?\n");
        sb.append("Respond with ONLY an integer scene number, or 0 if no scene matches.\n\n");
        sb.append("Scenes:\n");
        for (Scene s : scenes) {
            sb.append(s.sceneNumber()).append(". ").append(s.heading()).append('\n');
            String body = s.fullText() == null ? "" : s.fullText();
            String preview = body.length() > MAX_SCENE_PREVIEW_CHARS
                ? body.substring(0, MAX_SCENE_PREVIEW_CHARS) + "…"
                : body;
            sb.append(preview).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * Builds the OpenAI vision-style chat-completion body: a single user
     * message whose {@code content} array starts with the text prompt and
     * is followed by one {@code image_url} entry per frame.
     */
    private static String buildRequestBody(String model, String prompt,
                                            List<String> framesBase64) {
        StringBuilder content = new StringBuilder();
        content.append('[');
        content.append("{\"type\":\"text\",\"text\":")
            .append(SceneMatcher.jsonString(prompt))
            .append('}');
        for (String b64 : framesBase64) {
            content.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":")
                .append(SceneMatcher.jsonString("data:image/jpeg;base64," + b64))
                .append("}}");
        }
        content.append(']');

        return "{"
            + "\"model\":" + SceneMatcher.jsonString(model) + ","
            + "\"messages\":[{\"role\":\"user\",\"content\":" + content + "}],"
            + "\"max_tokens\":" + MAX_TOKENS + ","
            + "\"temperature\":0.2"
            + "}";
    }

    /**
     * Walks every {@code "content":"…"} field, runs {@link #lastInteger} on
     * each string, and returns the last integer it saw. Vision-model
     * responses include a description before the final scene-number answer,
     * so the LAST integer is the right one to pick.
     */
    static int extractSceneNumberLast(String responseJson) {
        int found = 0;
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
            Integer n = lastInteger(text);
            if (n != null) {
                found = n;
            }
            idx = responseJson.indexOf("\"content\":", quoteEnd);
        }
        return found;
    }

    static Integer lastInteger(String s) {
        // Scan right-to-left for a contiguous digit run.
        int end = -1;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (Character.isDigit(c)) {
                if (end < 0) {
                    end = i;
                }
            } else if (end >= 0) {
                try {
                    return Integer.parseInt(s.substring(i + 1, end + 1));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        if (end >= 0) {
            try {
                return Integer.parseInt(s.substring(0, end + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static int findClosingQuote(String json, int from) {
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
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

    private static int parseIntDefault(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static MatchResult unmatched() {
        return new MatchResult(0, 0.0);
    }
}
