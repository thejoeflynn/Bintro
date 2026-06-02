package com.bintro.parser;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptParserFdxTest {

    @Test
    void parsesScenesFromHardcodedFdxXml() throws IOException {
        String fdx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FinalDraft DocumentType="Script" Template="No" Version="5">
              <Content>
                <Paragraph Type="Scene Heading"><Text>INT. KITCHEN - DAY</Text></Paragraph>
                <Paragraph Type="Action"><Text>Alice cracks an egg into a bowl.</Text></Paragraph>
                <Paragraph Type="Character"><Text>ALICE</Text></Paragraph>
                <Paragraph Type="Dialogue"><Text>Coffee?</Text></Paragraph>
                <Paragraph Type="Character"><Text>BOB</Text></Paragraph>
                <Paragraph Type="Dialogue"><Text>Please.</Text></Paragraph>
                <Paragraph Type="Scene Heading"><Text>EXT. PARKING LOT - NIGHT</Text></Paragraph>
                <Paragraph Type="Action"><Text>Bob waits by his car.</Text></Paragraph>
                <Paragraph Type="Character"><Text>BOB</Text></Paragraph>
                <Paragraph Type="Dialogue"><Text>Took you long enough.</Text></Paragraph>
                <Paragraph Type="Scene Heading"><Text>INT./EXT. CAR - CONTINUOUS</Text></Paragraph>
                <Paragraph Type="Action"><Text>They drive in silence.</Text></Paragraph>
              </Content>
            </FinalDraft>
            """;

        ScriptParser parser = new ScriptParser();
        List<Scene> scenes = parser.parseFromFDX(
            new ByteArrayInputStream(fdx.getBytes(StandardCharsets.UTF_8))
        );

        assertEquals(3, scenes.size());

        assertEquals(1, scenes.get(0).sceneNumber());
        assertEquals(2, scenes.get(1).sceneNumber());
        assertEquals(3, scenes.get(2).sceneNumber());

        assertEquals("INT. KITCHEN - DAY", scenes.get(0).heading());
        assertEquals("EXT. PARKING LOT - NIGHT", scenes.get(1).heading());
        assertEquals("INT./EXT. CAR - CONTINUOUS", scenes.get(2).heading());

        // Action lines should appear in the scene body
        assertTrue(scenes.get(0).fullText().contains("Alice cracks an egg"));
        assertTrue(scenes.get(1).fullText().contains("Bob waits by his car"));
        assertTrue(scenes.get(2).fullText().contains("They drive in silence"));

        // Dialogue lines should appear in the scene body
        assertTrue(scenes.get(0).fullText().contains("Coffee?"));
        assertTrue(scenes.get(0).fullText().contains("Please."));
        assertTrue(scenes.get(1).fullText().contains("Took you long enough"));

        // Character paragraphs are not Action or Dialogue, so they're excluded
        // (per the spec — only Action + Dialogue land in fullText)
        assertFalse(scenes.get(0).fullText().contains("ALICE"),
            "Character paragraphs should not be included in fullText");
    }

    @Test
    void parseFactoryDispatchesByExtension() {
        ScriptParser parser = new ScriptParser();
        // .txt should be rejected
        IllegalArgumentException ex = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse(new java.io.File("/tmp/not-a-script.txt"))
        );
        assertTrue(ex.getMessage().contains("not-a-script.txt"));
    }
}
