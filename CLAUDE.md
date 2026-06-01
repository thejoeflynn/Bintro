# Bintro — Foundation Prompt

> Paste this at the start of every Claude Code session before requesting new features.
> It gives the AI full context so you don't have to re-explain the project each time.

---

## What is Bintro?

Bintro is a desktop application for Mac and Windows that helps video editors and DITs (Digital Image Technicians) organize raw footage during the import process. The user provides a folder of raw clips and a screenplay. Bintro transcribes each clip, analyzes it, matches it to the correct scene in the script, and sorts the footage into labeled folders — ready for import into an editing application.

## Core User Workflow

1. User opens Bintro and starts a new project
2. User selects a folder of raw footage clips
3. User uploads a screenplay (PDF or Final Draft .fdx format)
4. Bintro parses the screenplay into individual scenes
5. Bintro processes each clip:
   - Extracts file metadata (filename, timecode, duration)
   - Transcribes dialogue using whisper.cpp (runs locally, no internet required)
   - Sends transcript + scene descriptions to the Claude API for matching
6. Each clip is matched to a scene number
7. Footage is sorted into scene-labeled folders (e.g., Scene_01/, Scene_02/, Unmatched/)
8. User reviews matches and can manually correct any mismatches
9. User exports the organized project

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| UI | JavaFX |
| Build tool | Gradle |
| Video processing / metadata | JavaCV (FFmpeg bindings for Java) |
| Transcription | whisper.cpp called as a subprocess via Java's ProcessBuilder |
| Script parsing — PDF | Apache PDFBox |
| Script parsing — Final Draft | Custom XML parser (.fdx is XML-based) |
| AI matching | Anthropic Claude API via Java's HttpClient (REST) |

## Project Structure

```
bintro/
├── build.gradle
├── settings.gradle
├── whisper/                        # whisper.cpp binary and models (bundled)
│   ├── whisper                     # compiled binary (Mac) or whisper.exe (Windows)
│   └── models/
│       └── ggml-base.en.bin        # default model
├── src/
│   └── main/
│       ├── java/com/bintro/
│       │   ├── App.java            # JavaFX entry point
│       │   ├── ui/                 # Controllers and scene management
│       │   ├── parser/             # Script parsing (PDF + FDX)
│       │   ├── media/              # Clip metadata extraction via JavaCV
│       │   ├── transcription/      # whisper.cpp ProcessBuilder wrapper
│       │   ├── matching/           # Claude API matching logic
│       │   └── export/             # Folder organization and output
│       └── resources/
│           ├── fxml/               # JavaFX layout files
│           └── css/                # Styles
```

## Key Constraints

- **All transcription runs locally.** whisper.cpp must be bundled with the app or documented as a required setup step. No paid transcription APIs.
- **Claude API is the only external API.** It is used only for scene matching, not transcription.
- **MVP scope is a single scene's worth of footage.** Do not over-engineer for multi-scene workflows until the MVP is complete.
- **Output folder format:** `Scene_01/`, `Scene_02/`, `Unmatched/` inside a user-chosen output directory.
- **Java 21.** Use modern Java features (records, sealed classes, pattern matching) where appropriate.

## MVP Scope (Build This First)

- [ ] Import a folder of clips
- [ ] Upload a screenplay (PDF or .fdx)
- [ ] Parse screenplay into scenes
- [ ] Transcribe each clip via whisper.cpp
- [ ] Match clips to scenes via Claude API
- [ ] Sort clips into scene-labeled output folders
- [ ] Basic JavaFX UI to drive the above workflow

## Out of Scope (for now)

- Export to Premiere Pro / Final Cut Pro / DaVinci Resolve
- Annotated script PDF output
- Multi-user or cloud sync
- Batch multi-scene processing beyond MVP
