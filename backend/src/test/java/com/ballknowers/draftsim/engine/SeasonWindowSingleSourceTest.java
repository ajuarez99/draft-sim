package com.ballknowers.draftsim.engine;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spec 013 T070: the early-season threshold is declared once, in main code. */
class SeasonWindowSingleSourceTest {

    @Test
    void exactlyOneDeclarationOfTheEarlyThreshold() throws IOException {
        Pattern declaration = Pattern.compile("(int|Integer)[ ]+EARLY_THRESHOLD_WEEKS[ ]*=");
        long count;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            count = files.filter(f -> f.toString().endsWith(".java")).mapToLong(f -> {
                try {
                    return declaration.matcher(Files.readString(f)).results().count();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }).sum();
        }
        assertEquals(1, count);
        assertEquals(4, SeasonWindow.EARLY_THRESHOLD_WEEKS);
    }
}
