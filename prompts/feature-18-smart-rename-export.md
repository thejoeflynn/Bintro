# Feature 18 — Smart Rename on Export

> Paste this into a Claude Code session opened at the Bintro project root.

---

## Context

Currently `Exporter.java` copies clips into scene folders using their original
camera filenames. This feature renames them on export to a clean, editor-friendly
format and writes a CSV log so original filenames are never lost.

---

## Rename format

```
{scene_number_padded}_{counter}.{extension}
```

Examples:
- `01_001.mp4` — first clip matched to scene 1
- `01_002.mp4` — second clip matched to scene 1  
- `03_001.mxf` — first clip matched to scene 3
- `Unmatched_001.mp4` — clip with no scene match

Rules:
- Scene number zero-padded to 2 digits (`01`, `02`... `99`, `100`)
- Counter zero-padded to 3 digits, resets per scene
- File extension preserved from original (lowercase)
- No scene heading in the filename — the folder name already conveys that

---

## Task

### 1. Update `Exporter.java`

Rewrite `export(List<ClipMatch> matches, File outputDir)`:

**Group matches by scene number** first, then process each group:
```java
Map<Integer, List<ClipMatch>> byScene = matches.stream()
    .collect(Collectors.groupingBy(ClipMatch::sceneNumber));
```

**For each clip in a group**, build the new filename:
```java
String ext = getExtension(clip.filename()).toLowerCase();
String newName = sceneNumber == 0
    ? String.format("Unmatched_%03d.%s", counter, ext)
    : String.format("%02d_%03d.%s", sceneNumber, counter, ext);
```

Copy the file to the output folder using the new name:
```java
Files.copy(clip.file().toPath(),
    outputDir.toPath().resolve(folderName).resolve(newName),
    StandardCopyOption.REPLACE_EXISTING);
```

**Helper:**
```java
private static String getExtension(String filename) {
    int dot = filename.lastIndexOf('.');
    return dot >= 0 ? filename.substring(dot + 1) : "mp4";
}
```

### 2. Write `rename_log.csv`

After all files are exported, write a `rename_log.csv` to the root of `outputDir`:

```csv
Original Filename,New Filename,Scene Number,Scene Heading,Clip Duration,Export Time
Sony_3423.mp4,01_001.mp4,1,INT. COFFEE SHOP - DAY,00:02:14.000,2026-06-05T14:32:00
YTDown_Princess_Bride.mp4,Unmatched_001.mp4,0,Unmatched,00:01:45.000,2026-06-05T14:32:01
```

Fields:
- `Original Filename` — `clip.filename()`
- `New Filename` — the renamed file
- `Scene Number` — 0 for unmatched
- `Scene Heading` — looked up from `List<Scene>` by scene number, "Unmatched" if 0
- `Clip Duration` — formatted as `HH:MM:SS.mmm` from `clip.duration()`
- `Export Time` — `LocalDateTime.now()` formatted as ISO-8601

**Update `Exporter.export()` signature** to accept `List<Scene> scenes` so headings
can be looked up for the log:
```java
public void export(List<ClipMatch> matches, List<Scene> scenes, File outputDir)
```

Update the call site in `MainController` to pass `scenesSnapshot`.

### 3. Manual rename in the review table

In `MainController` / `ClipMatchViewModel`, the "Clip" column currently shows
the original filename. Add a second editable column **"Export Name"** to the
`TableView`:

- Default value: the computed rename (e.g. `01_001`) — shown without extension,
  editable as plain text
- If the user types a custom name, that overrides the computed name on export
- Extension is always appended automatically from the original file
- Column header: "Export Name"
- Style: italic when using the computed default, normal weight when user-edited

In `ClipMatchViewModel` add:
```java
private final StringProperty exportName;   // computed default, user-overridable
private boolean exportNameCustomized = false;
```

`Exporter` reads `vm.getExportName()` instead of computing the name itself when
called from the UI flow. The `ClipMatch` record may need to carry the export name
through — add a field or pass it separately.

### 4. Show rename preview in the UI

After Phase 1 completes (before export), the "Export Name" column populates
with the computed names so the editor can see and edit them before anything
is written to disk. Update `MainController.refreshExportNames()` to compute
and set all export names on the view models after matching completes.

---

## Constraints

- Java 21 only — no new dependencies
- Original files are never moved or deleted — only copied
- If a filename collision occurs (same computed name already exists in output),
  increment the counter until unique
- Modify: `Exporter.java`, `MainController.java`, `ClipMatchViewModel.java`
- Add no new files
- After implementing: `./gradlew compileJava` passes, `./gradlew run` launches

## Done when

- Exported files are named `01_001.mp4`, `01_002.mp4`, `03_001.mp4` etc.
- Unmatched clips export as `Unmatched_001.mp4`
- `rename_log.csv` appears in the output root after every export
- The review table shows an editable "Export Name" column before export
- Manually entered names are used on export instead of the computed default
- `./gradlew compileJava` passes with no errors
