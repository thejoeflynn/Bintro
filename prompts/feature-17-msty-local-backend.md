# Feature 17 — Replace Claude API with Msty Local Backend

> Paste this into a Claude Code session opened at the Bintro project root.

---

## Context

Bintro currently calls the Anthropic Claude API for scene matching. We are
replacing this entirely with Msty, a local AI app already running on the
user's machine. Msty exposes an OpenAI-compatible API at:

```
http://localhost:11434/v1
```

No internet connection, no API key, no cost per call.

The models available in Msty:
- **Qwen 2.5** — used for dialogue scene matching (transcript → scene)
- **Gemma 4** — used for visual scene matching (video frames → scene description)

---

## Task

### 1. Update `config.properties` (and `config.properties.example`)

Remove the `claude.api.key` and `claude.model` entries. Replace with:

```properties
# Local AI backend (Msty, Ollama, or any OpenAI-compatible server)
ai.backend.url=http://localhost:11434/v1

# Model for dialogue scene matching (transcript → scene)
ai.match.model=qwen2.5

# Model for visual scene matching (video frames → scene description)
ai.visual.model=gemma4

# Number of key frames to extract per clip for visual matching
ai.visual.frames=4
```

---

### 2. Rewrite `SceneMatcher.java`

Replace the Anthropic-specific implementation with an OpenAI-compatible client.

**Endpoint:** `POST {ai.backend.url}/chat/completions`

**Request body (standard OpenAI format):**
```json
{
  "model": "<ai.match.model>",
  "messages": [
    { "role": "user", "content": "<prompt>" }
  ],
  "max_tokens": 16,
  "temperature": 0.1
}
```

**Remove:**
- All `x-api-key` and `anthropic-version` headers
- The Anthropic-specific JSON structure
- The `claude.api.key` check

**Add:**
- Read `ai.backend.url` and `ai.match.model` from `Config`
- If either is blank, fall back to `LocalSceneMatcher` (keyword matching)
- Log: `"SceneMatcher: using Msty backend at " + backendUrl + " with model " + model`

**Response parsing:**
OpenAI-compatible response format:
```json
{
  "choices": [{ "message": { "content": "3" } }]
}
```

Parse the scene number from `choices[0].message.content` using the existing
`firstInteger()` method (keep it unchanged).

**Error handling:**
- If the HTTP call fails or Msty isn't running: log the error clearly
  (`"SceneMatcher: Msty not reachable at " + backendUrl + " — falling back to keyword match"`)
  and fall back to `LocalSceneMatcher`
- If the response can't be parsed: same fallback

---

### 3. Create `VisualMatcher.java`

Create `src/main/java/com/bintro/matching/VisualMatcher.java`.

This handles clips with `MatchType.VISUAL` — insert shots, B-roll, and any
clip with no usable transcript.

```java
public class VisualMatcher {
    public MatchResult match(Clip clip, List<Scene> scenes) throws IOException { ... }
}
```

**Step 1 — Extract key frames:**

Use JavaCV's `FFmpegFrameGrabber` to extract N evenly-spaced frames from the clip
(N = `Config.get("ai.visual.frames", "4")` parsed as int):

```java
try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(clip.file())) {
    grabber.start();
    long totalFrames = grabber.getLengthInFrames();
    for (int i = 0; i < frameCount; i++) {
        long frameNum = (totalFrames / (frameCount + 1)) * (i + 1);
        grabber.setFrameNumber((int) frameNum);
        Frame frame = grabber.grabImage();
        // Convert to base64 JPEG
    }
}
```

Convert each `Frame` to a base64-encoded JPEG using `Java2DFrameConverter` +
`ImageIO.write()` to a `ByteArrayOutputStream`.

**Step 2 — Send to Gemma 4 via Msty:**

Use the OpenAI vision message format:
```json
{
  "model": "<ai.visual.model>",
  "messages": [{
    "role": "user",
    "content": [
      { "type": "text", "text": "<prompt>" },
      { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,<base64>" } },
      { "type": "image_url", "image_url": { "url": "data:image/jpeg;base64,<base64>" } }
    ]
  }],
  "max_tokens": 200,
  "temperature": 0.2
}
```

**Prompt for visual matching:**
```
You are helping a film editor sort raw footage into screenplay scenes.

These frames are from a video clip with no dialogue.
Describe in 1-2 sentences what is visually happening: the setting, any
visible characters, actions, and mood.

Then, given these screenplay scenes, which scene number best matches?
Respond with ONLY an integer scene number, or 0 if no scene matches.

Scenes:
1. INT. COFFEE SHOP - DAY
A busy morning. Customers crowd the counter.
...
```

**Step 3 — Parse result:**
Same `firstInteger()` approach as `SceneMatcher`.

If the response is 0 or unparseable, return `MatchResult(0, 0.0)`.

---

### 4. Update `MainController.java`

In the pipeline loop, determine which matcher to use per clip:

```java
String transcript = "";
MatchResult result;

if (clip has a non-empty transcript from Transcriber) {
    // Dialogue path
    result = sceneMatcher.match(clip, transcript, scenes);
    vm.setMatchType(MatchType.DIALOGUE);
} else {
    // Visual path — no usable dialogue
    result = new VisualMatcher().match(clip, scenes);
    vm.setMatchType(MatchType.VISUAL);
}
```

A clip uses the visual path if:
- Transcription failed, OR
- The transcript is blank or under 5 words after stripping whitespace

Update the status label during visual matching:
```
"Visually analysing " + clip.filename() + " (" + idx + " of " + total + ")…"
```

---

### 5. Remove all Claude API references

- Delete or comment out the `claude.api.key` and `claude.model` checks in
  `SceneMatcher.java` and `Config.java`
- Update `config.properties.example` to remove Claude entries and add Msty entries
- If `LocalSceneMatcher` references Claude anywhere, clean that up too

---

## Constraints

- Java 21, JavaFX 21 — no new Gradle dependencies
  (`FFmpegFrameGrabber`, `Java2DFrameConverter`, `ImageIO` are all already available)
- Msty must be running for matching to work. If it's not, the app falls back
  to `LocalSceneMatcher` gracefully — it never crashes
- Do not remove `LocalSceneMatcher` — it remains the offline fallback
- After implementing: `./gradlew compileJava` passes, `./gradlew run` launches

## Done when

- `config.properties` has no Claude API references — only Msty backend settings
- Running the pipeline calls Msty at `http://localhost:11434/v1` for matching
- Clips with dialogue use Qwen 2.5 for matching
- Clips with no transcript use Gemma 4 with frame extraction for visual matching
- If Msty is unreachable, the app falls back to keyword matching with a clear log message
- `./gradlew compileJava` passes with no errors
