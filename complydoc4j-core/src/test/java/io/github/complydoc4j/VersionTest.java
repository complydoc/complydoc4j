package io.github.complydoc4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class VersionTest {

    /**
     * A report records {@link Complydoc4j#VERSION} as the version that wrote it, so it must be
     * the version the build publishes: a release tag is checked against both.
     */
    @Test
    void theVersionAReportRecordsIsTheOneTheBuildPublishes() throws Exception {
        String pom = Files.readString(Path.of("../pom.xml"));
        Matcher version = Pattern.compile("<artifactId>complydoc4j</artifactId>\\s*<version>([^<]+)</version>")
                .matcher(pom);
        version.find();
        assertEquals(version.group(1), Complydoc4j.VERSION);
    }
}
