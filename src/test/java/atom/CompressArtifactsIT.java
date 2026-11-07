package atom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The compress-artifacts atom, started the way the runner starts it.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made — the archive is really a zip, the round trip is byte for
 * byte, UTF-8 and spaced names survive, the stream was actually deflated, the same input produces the same bytes,
 * exclude and deleteSource behave, and an empty match is refused — written as JUnit.
 */
class CompressArtifactsIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("the dist tree round-trips byte for byte, nested, spaced and UTF-8 names included")
    void packsTheTree() throws Exception {
        AtomResult run = distTree()
                .input("patterns", "dist/**/*")
                .input("outputFile", "out/artifacts.zip")
                .run();

        run.assertOk();
        assertEquals("5", run.field("filesAdded"), "five files are under dist/:\n" + run);
        byte[] archive = run.bytes("out/artifacts.zip");
        assertNotNull(archive, "no archive was written:\n" + run);

        Map<String, byte[]> entries = unzip(archive);
        for (String path : List.of("dist/app.jar", "dist/nested/deep.txt", "dist/a file with spaces.txt",
                "dist/报告.txt", "dist/big.txt")) {
            assertTrue(entries.containsKey(path), path + " is not in the archive: " + entries.keySet());
            assertArrayEquals(run.bytes(path), entries.get(path), path + " differs after the round trip");
        }
        assertFalse(entries.containsKey("logs/build.log"), "a file outside the pattern was packed: " + entries.keySet());
    }

    @Test
    @DisplayName("20 KB of one repeated character really is deflated")
    void compresses() throws Exception {
        AtomResult run = distTree()
                .input("patterns", "dist/**/*")
                .input("outputFile", "out/artifacts.zip")
                .run();

        run.assertOk();
        double ratio = Double.parseDouble(run.field("ratio"));
        assertTrue(ratio < 50, "the ratio says nothing was compressed: " + run.field("ratio"));
    }

    @Test
    @DisplayName("the same input produces the same bytes and sorted entries")
    void isDeterministic() throws Exception {
        AtomResult first = distTree()
                .input("patterns", "dist/**/*")
                .input("outputFile", "out/artifacts.zip")
                .run();
        first.assertOk();

        AtomResult second = distTree()
                .input("patterns", "dist/**/*")
                .input("outputFile", "out/artifacts.zip")
                .run();
        second.assertOk();

        assertArrayEquals(first.bytes("out/artifacts.zip"), second.bytes("out/artifacts.zip"),
                "the same input must produce the same archive");
        List<String> names = new ArrayList<>(unzip(second.bytes("out/artifacts.zip")).keySet());
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(null);
        assertEquals(sorted, names, "entry order is not stable");
    }

    @Test
    @DisplayName("exclude drops the matching files and the archive never packs itself")
    void excludesAndDoesNotPackItself() throws Exception {
        AtomResult run = distTree()
                .input("patterns", "**/*")
                .input("exclude", "**/*.log")
                .input("outputFile", "out/artifacts.zip")
                .run();

        run.assertOk();
        Map<String, byte[]> entries = unzip(run.bytes("out/artifacts.zip"));
        assertTrue(entries.keySet().stream().noneMatch(name -> name.endsWith(".log")),
                "a .log file was packed: " + entries.keySet());
        assertTrue(entries.keySet().stream().noneMatch(name -> name.contains("artifacts.zip")),
                "the archive packed itself: " + entries.keySet());
    }

    @Test
    @DisplayName("deleteSource empties the source tree but keeps the archive")
    void deletesTheSources() throws Exception {
        AtomResult run = distTree()
                .input("patterns", "dist/**/*")
                .input("outputFile", "out/lean.zip")
                .input("deleteSource", "true")
                .run();

        run.assertOk();
        assertTrue(run.exists("out/lean.zip"), "the archive should survive:\n" + run);
        assertEquals(5, unzip(run.bytes("out/lean.zip")).size(), "the archive still holds the five files");
        for (String path : List.of("dist/app.jar", "dist/nested/deep.txt", "dist/a file with spaces.txt",
                "dist/报告.txt", "dist/big.txt")) {
            assertFalse(run.exists(path), path + " should be gone");
        }
    }

    @Test
    @DisplayName("an empty match is refused by name")
    void refusesAnEmptyMatch() throws Exception {
        AtomResult run = atom.input("patterns", "dist/**/*").run();

        run.assertRefused();
        assertTrue(run.field("message").contains("no file matched"),
                "the refusal must name the pattern:\n" + run);
    }

    /** The workspace the old smoke script built: a dist tree with nested, spaced, UTF-8 and large files. */
    private Atom distTree() {
        return atom.file("dist/app.jar", "jar-bytes\n")
                .file("dist/nested/deep.txt", "deep\n")
                .file("dist/a file with spaces.txt", "spaced\n")
                .file("dist/报告.txt", "zh\n")
                .file("dist/big.txt", "x".repeat(20000))
                .file("logs/build.log", "noise\n");
    }

    /** Entries in archive order, so the entry-order check can see what the writer produced. */
    private static Map<String, byte[]> unzip(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}
