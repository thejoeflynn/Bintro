# Feature 20 — Themes: Light, Dark, Navy, Forest

> Paste this into a Claude Code session opened at the Bintro project root.

---

## Context

Bintro has a `theme.css` file with CSS custom properties already set up for
theming. This feature implements four themes — Light, Dark, Navy, Forest —
switchable from the View menu, with the selection persisting in `config.properties`.

---

## Theme color values

### Light (default)
```
bg:           #f5f4f0
bg-raised:    #ffffff
bg-sunken:    #eeecea
border:       #e0dfd9
text:         #1a1a1a
text-muted:   #888888
accent:       #111111
accent-text:  #ffffff
```

### Dark
```
bg:           #1a1a1a
bg-raised:    #222222
bg-sunken:    #1e1e1e
border:       #333333
text:         #cccccc
text-muted:   #555555
accent:       #e8e8e8
accent-text:  #111111
```

### Navy
```
bg:           #0d1f3c
bg-raised:    #122444
bg-sunken:    #102238
border:       #1e3560
text:         #b8cfe8
text-muted:   #3a5a8a
accent:       #4a8fd4
accent-text:  #ffffff
```

### Forest
```
bg:           #0d1f10
bg-raised:    #122416
bg-sunken:    #102214
border:       #1e4025
text:         #a8d4b0
text-muted:   #3a6a45
accent:       #4a9458
accent-text:  #ffffff
```

### Shared (all themes)
Gutter bar palette — these never change:
```
clip-0: #4a90d9
clip-1: #e07b54
clip-2: #5aaa6a
clip-3: #b07dd4
clip-4: #c8a830
clip-5: #4abcbc
```

---

## Task

### 1. Rewrite `theme.css`

Replace the contents of `src/main/resources/css/theme.css` with all four
theme definitions using JavaFX CSS custom properties:

```css
/* ── Light (default) ─────────────────────────── */
.root {
    -bintro-bg:          #f5f4f0;
    -bintro-bg-raised:   #ffffff;
    -bintro-bg-sunken:   #eeecea;
    -bintro-border:      #e0dfd9;
    -bintro-text:        #1a1a1a;
    -bintro-text-muted:  #888888;
    -bintro-accent:      #111111;
    -bintro-accent-text: #ffffff;

    -clip-0: #4a90d9;
    -clip-1: #e07b54;
    -clip-2: #5aaa6a;
    -clip-3: #b07dd4;
    -clip-4: #c8a830;
    -clip-5: #4abcbc;

    -fx-background-color: -bintro-bg;
    -fx-font-family: 'Courier New';
    -fx-font-size: 13px;
}

/* ── Dark ────────────────────────────────────── */
.root.theme-dark {
    -bintro-bg:          #1a1a1a;
    -bintro-bg-raised:   #222222;
    -bintro-bg-sunken:   #1e1e1e;
    -bintro-border:      #333333;
    -bintro-text:        #cccccc;
    -bintro-text-muted:  #555555;
    -bintro-accent:      #e8e8e8;
    -bintro-accent-text: #111111;
    -fx-background-color: -bintro-bg;
}

/* ── Navy ────────────────────────────────────── */
.root.theme-navy {
    -bintro-bg:          #0d1f3c;
    -bintro-bg-raised:   #122444;
    -bintro-bg-sunken:   #102238;
    -bintro-border:      #1e3560;
    -bintro-text:        #b8cfe8;
    -bintro-text-muted:  #3a5a8a;
    -bintro-accent:      #4a8fd4;
    -bintro-accent-text: #ffffff;
    -fx-background-color: -bintro-bg;
}

/* ── Forest ──────────────────────────────────── */
.root.theme-forest {
    -bintro-bg:          #0d1f10;
    -bintro-bg-raised:   #122416;
    -bintro-bg-sunken:   #102214;
    -bintro-border:      #1e4025;
    -bintro-text:        #a8d4b0;
    -bintro-text-muted:  #3a6a45;
    -bintro-accent:      #4a9458;
    -bintro-accent-text: #ffffff;
    -fx-background-color: -bintro-bg;
}

/* ── Component styles (use variables, work in all themes) ── */
.bintro-toolbar {
    -fx-background-color: -bintro-bg-raised;
    -fx-border-color: -bintro-border;
    -fx-border-width: 0 0 1 0;
    -fx-padding: 7 12 7 12;
    -fx-spacing: 8;
}

.bintro-btn {
    -fx-background-color: -bintro-accent;
    -fx-text-fill: -bintro-accent-text;
    -fx-font-size: 11px;
    -fx-padding: 5 14 5 14;
    -fx-background-radius: 4;
    -fx-border-color: transparent;
    -fx-cursor: hand;
}
.bintro-btn:hover   { -fx-opacity: 0.85; }
.bintro-btn:pressed { -fx-opacity: 0.7; }

.bintro-btn-ghost {
    -fx-background-color: transparent;
    -fx-text-fill: -bintro-text-muted;
    -fx-font-size: 11px;
    -fx-padding: 5 14 5 14;
    -fx-background-radius: 4;
    -fx-border-color: -bintro-border;
    -fx-border-width: 1;
    -fx-border-radius: 4;
    -fx-cursor: hand;
}
.bintro-btn-ghost:hover { -fx-background-color: -bintro-bg-sunken; }

.bintro-status-bar {
    -fx-background-color: -bintro-bg-sunken;
    -fx-border-color: -bintro-border;
    -fx-border-width: 1 0 0 0;
    -fx-padding: 5 12 5 12;
}

.bintro-panel-header {
    -fx-background-color: -bintro-bg-raised;
    -fx-border-color: -bintro-border;
    -fx-border-width: 0 0 1 0;
    -fx-padding: 7 12 7 12;
}

.bintro-list-cell {
    -fx-background-color: -bintro-bg;
    -fx-text-fill: -bintro-text;
    -fx-font-size: 10px;
    -fx-padding: 5 12 5 12;
}
.bintro-list-cell:selected {
    -fx-background-color: -bintro-accent;
    -fx-text-fill: -bintro-accent-text;
}
```

### 2. Create `ThemeManager.java`

Create `src/main/java/com/bintro/ui/ThemeManager.java`.

```java
public class ThemeManager {

    public enum Theme {
        LIGHT("Light", ""),
        DARK("Dark", "theme-dark"),
        NAVY("Navy", "theme-navy"),
        FOREST("Forest", "theme-forest");

        public final String label;
        public final String styleClass;

        Theme(String label, String styleClass) {
            this.label = label;
            this.styleClass = styleClass;
        }

        public static Theme fromKey(String key) {
            for (Theme t : values()) {
                if (t.name().equalsIgnoreCase(key)) return t;
            }
            return LIGHT;
        }
    }

    private static Theme current = Theme.LIGHT;

    /** Apply a theme to all open scenes. */
    public static void apply(Theme theme, Scene... scenes) {
        current = theme;
        for (Scene scene : scenes) {
            Parent root = scene.getRoot();
            // Remove all existing theme classes
            root.getStyleClass().removeIf(c -> c.startsWith("theme-"));
            // Apply new theme class (empty string = light, no class needed)
            if (!theme.styleClass.isEmpty()) {
                root.getStyleClass().add(theme.styleClass);
            }
        }
        // Persist to config
        Config.set("ui.theme", theme.name());
    }

    public static Theme current() { return current; }

    /** Load saved theme from config, default Light. */
    public static Theme load() {
        return Theme.fromKey(Config.get("ui.theme", "LIGHT"));
    }
}
```

### 3. Update `Config.java`

Add a `set(String key, String value)` method so `ThemeManager` can persist
the selected theme:

```java
public static void set(String key, String value) {
    ensureLoaded().setProperty(key, value);
    // Write back to config.properties
    Path file = Path.of(System.getProperty("user.dir"), "config.properties");
    try (OutputStream out = Files.newOutputStream(file)) {
        props.store(out, "Bintro Configuration");
    } catch (IOException e) {
        System.err.println("Config: failed to save: " + e.getMessage());
    }
}
```

### 4. Add `ui.theme` to `config.properties`

```properties
# UI theme: LIGHT, DARK, NAVY, FOREST
ui.theme=LIGHT
```

### 5. Update `App.java`

After loading both scenes (welcome + main), apply the saved theme to both:

```java
Theme savedTheme = ThemeManager.load();
ThemeManager.apply(savedTheme, welcomeScene, mainScene);
```

Store both scenes as fields so `ThemeManager` can reach them from anywhere.

### 6. Wire up the View menu in `MainController`

The View → Theme submenu already has items stubbed as disabled. Enable them
and wire each to `ThemeManager.apply()`:

```java
private void applyTheme(ThemeManager.Theme theme) {
    // Get both scenes
    Scene mainScene = statusLabel.getScene();
    ThemeManager.apply(theme, mainScene);
    // Update checkmarks in the menu
    menuThemeLight.setSelected(theme == Theme.LIGHT);
    menuThemeDark.setSelected(theme == Theme.DARK);
    menuThemeNavy.setSelected(theme == Theme.NAVY);
    menuThemeForest.setSelected(theme == Theme.FOREST);
    // Re-render the script WebView with updated colors if loaded
    if (scenes != null && !scenes.isEmpty()) {
        refreshScriptView();
    }
}
```

Add `@FXML` fields for each menu item:
```java
@FXML private RadioMenuItem menuThemeLight;
@FXML private RadioMenuItem menuThemeDark;
@FXML private RadioMenuItem menuThemeNavy;
@FXML private RadioMenuItem menuThemeForest;
```

In `MainView.fxml`, update the Theme submenu to use `RadioMenuItem` in a
`ToggleGroup`, with `onAction` handlers:
```xml
<Menu text="Theme">
  <items>
    <RadioMenuItem fx:id="menuThemeLight"  text="Light"  onAction="#onThemeLight"  selected="true"/>
    <RadioMenuItem fx:id="menuThemeDark"   text="Dark"   onAction="#onThemeDark"/>
    <RadioMenuItem fx:id="menuThemeNavy"   text="Navy"   onAction="#onThemeNavy"/>
    <RadioMenuItem fx:id="menuThemeForest" text="Forest" onAction="#onThemeForest"/>
  </items>
</Menu>
```

### 7. Update script WebView HTML colors

`ScriptRenderer.render()` currently hardcodes colors in its embedded CSS.
Update it to accept a `ThemeManager.Theme` parameter and output
theme-appropriate colors:

```java
public static String render(List<Scene> scenes,
                            Map<Integer, List<ClipMatchViewModel>> byScene,
                            ThemeManager.Theme theme) { ... }
```

Use a `switch` on theme to pick the page/text colors:
```java
String pageBg   = switch(theme) {
    case DARK   -> "#1e1e1e";
    case NAVY   -> "#0d1f3c";
    case FOREST -> "#0d1f10";
    default     -> "#ffffff";
};
String pageText = switch(theme) {
    case DARK   -> "#cccccc";
    case NAVY   -> "#b8cfe8";
    case FOREST -> "#a8d4b0";
    default     -> "#111111";
};
String bodyBg   = switch(theme) {
    case DARK   -> "#111111";
    case NAVY   -> "#081628";
    case FOREST -> "#081510";
    default     -> "#e8e8e8";
};
```

Pass `ThemeManager.current()` when calling `render()` in `MainController`.

### 8. Update `MainView.fxml` and `WelcomeView.fxml`

Replace all hardcoded `style=""` color values with the CSS class equivalents
from `theme.css`:

- Toolbar `HBox` → add `styleClass="bintro-toolbar"`
- Primary buttons → add `styleClass="bintro-btn"`
- Ghost buttons → add `styleClass="bintro-btn-ghost"`
- Status bar → add `styleClass="bintro-status-bar"`
- Panel headers → add `styleClass="bintro-panel-header"`

Make sure both FXML files reference `theme.css`:
```xml
<stylesheets>
  <URL value="@../css/theme.css"/>
</stylesheets>
```

---

## Constraints

- Java 21, JavaFX 21 — no new dependencies
- Theme switch must be instant — no restart required
- Selected theme persists across app restarts via `config.properties`
- The welcome screen inherits the active theme
- The script WebView re-renders with theme colors when theme changes
- Create: `ui/ThemeManager.java`
- Modify: `css/theme.css`, `Config.java`, `App.java`, `MainController.java`,
  `MainView.fxml`, `WelcomeView.fxml`, `ui/ScriptRenderer.java`,
  `config.properties`, `config.properties.example`
- After implementing: `./gradlew compileJava` passes, `./gradlew run` launches

## Done when

- All four themes apply instantly from View → Theme menu
- Active theme is checked in the menu with a radio checkmark
- Theme persists after restarting the app
- Script WebView updates colors to match the active theme
- Welcome screen respects the active theme
- `./gradlew compileJava` passes with no errors
