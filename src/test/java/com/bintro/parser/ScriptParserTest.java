package com.bintro.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptParserTest {

    @Test
    void parsesScenesFromHardcodedScreenplay() {
        String screenplay = """
            FADE IN:

            INT. KITCHEN - DAY

            Alice cracks an egg into a bowl. Steam rises from a kettle.

            ALICE
            Coffee?

            BOB
            Please.

            EXT. PARKING LOT - NIGHT

            Bob waits by his car, breath fogging in the cold air.

            BOB
            Took you long enough.

            INT./EXT. CAR - CONTINUOUS

            They drive in silence.

            FADE OUT.
            """;

        ScriptParser parser = new ScriptParser();
        List<Scene> scenes = parser.parseFromText(screenplay);

        assertEquals(3, scenes.size(), "should detect three sluglines");

        assertEquals(1, scenes.get(0).sceneNumber());
        assertEquals(2, scenes.get(1).sceneNumber());
        assertEquals(3, scenes.get(2).sceneNumber());

        assertEquals("INT. KITCHEN - DAY", scenes.get(0).heading());
        assertEquals("EXT. PARKING LOT - NIGHT", scenes.get(1).heading());
        assertEquals("INT./EXT. CAR - CONTINUOUS", scenes.get(2).heading());

        assertTrue(scenes.get(0).fullText().contains("Alice cracks an egg"),
            "scene 1 body should contain its action line");
        assertTrue(scenes.get(0).fullText().contains("Coffee?"),
            "scene 1 body should contain its dialogue");
        assertTrue(scenes.get(1).fullText().contains("Took you long enough"));
        assertTrue(scenes.get(2).fullText().contains("They drive in silence"));

        assertTrue(!scenes.get(0).fullText().contains("FADE IN"),
            "text before the first slugline should be discarded");
    }

    @Test
    void ignoresContentBeforeFirstSlugline() {
        String screenplay = """
            Some preamble text.
            More preamble.

            INT. ROOM - DAY

            A simple scene.
            """;

        List<Scene> scenes = new ScriptParser().parseFromText(screenplay);

        assertEquals(1, scenes.size());
        assertEquals("INT. ROOM - DAY", scenes.get(0).heading());
        assertTrue(scenes.get(0).fullText().contains("A simple scene"));
        assertTrue(!scenes.get(0).fullText().contains("preamble"));
    }
}
