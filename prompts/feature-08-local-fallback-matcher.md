# Feature 8 — Local Fallback Scene Matcher

> Paste this into a Claude Code session opened at the Bintro project root.

---

## Context

Bintro matches clip transcripts to screenplay scenes. Currently this is done via
the Claude API (`SceneMatcher.java`), but when no API key is configured the matcher
silently returns Unmatched for every clip.

We need a local fallback matcher that works without any API key, using keyword
overlap scoring between the transcript and each scene's text.

---

## Task

### 1. Create `LocalSceneMatcher.java`

Create `src/main/java/com/bintro/matching/LocalSceneMatcher.java`.

Implement `MatchResult match(String transcript, List<Scene> scenes)`:

**Scoring algorithm:**
- Normalise both the transcript and each scene's `fullText` + `heading`:
  - Lowercase
  - Strip punctuation
  - Split on whitespace into a `Set<String>` of words
  - Remove common stop words: `a, an, the, and, or, but, in, on, at, to, of,
    is, it, he, she, they, we, you, i, was, be, are, with, for, his, her, that,
    this, have, had, not, from, as, do, so, my, me, him, us, its, by, if, up`
- Score = number of words that appear in both the transcript set and the scene set
- Pick the scene with the highest score
- If the top score is 0 (no word overlap at all), return `MatchResult(0, 0.0)`
- Otherwise return `MatchResult(topSceneNumber, score / transcriptWordCount)`
  where confidence is capped at 1.0

**Add logging:**
```java
System.out.println("LocalSceneMatcher: transcript words=" + transcriptWords.size()
    + ", best scene=" + bestScene.sceneNumber()
    + ", score=" + bestScore);
```

### 2. Update `SceneMatcher.java`

In `match()`, after the API key check fails (key is null, blank, or equals
`YOUR_CLAUDE_API_KEY_HERE`), instead of returning `unmatched()` immediately,
delegate to `LocalSceneMatcher`:

```java
if (apiKey == null || apiKey.isBlank() || "YOUR_CLAUDE_API_KEY_HERE".equals(apiKey)) {
    System.out.println("SceneMatcher: no API key — using local keyword matcher");
    return new LocalSceneMatcher().match(transcript, scenes);
}
```

---

## Constraints

- Java 21 only — no new dependencies.
- Only create `LocalSceneMatcher.java` and modify `SceneMatcher.java`.
- After implementing, confirm: `./gradlew compileJava` passes.

## Done when

- Running the app without an API key logs "using local keyword matcher" and
  clips are matched to scenes based on word overlap instead of all going to Unmatched.
- Clips with no transcript overlap still go to Unmatched (score 0).
