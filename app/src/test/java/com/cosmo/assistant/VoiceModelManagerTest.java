package com.cosmo.assistant;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** Exercises real ZIP/CRC/path behavior without network access or a large model fixture. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VoiceModelManagerTest {
    @Rule public final TemporaryFolder files = new TemporaryFolder();
    private static final String MODEL = VoiceModelManager.MODEL_NAME;

    @Test public void validDynamicGraphExtractsAndBecomesReady() throws Exception {
        Map<String, byte[]> entries = baseModel();
        entries.put(MODEL + "/graph/HCLr.fst", bytes("graph-left"));
        entries.put(MODEL + "/graph/Gr.fst", bytes("graph-right"));
        File destination = files.newFolder("dynamic");
        extract(archive(entries), destination);
        File model = new File(destination, MODEL);
        assertTrue(validModel(model));
        assertEquals("model", new String(Files.readAllBytes(new File(model, "am/final.mdl").toPath()), StandardCharsets.UTF_8));
    }

    @Test public void validStaticGraphIsAlsoReady() throws Exception {
        Map<String, byte[]> entries = baseModel();
        entries.put(MODEL + "/graph/HCLG.fst", bytes("static-graph"));
        File destination = files.newFolder("static");
        extract(archive(entries), destination);
        assertTrue(validModel(new File(destination, MODEL)));
    }

    @Test public void incompleteGraphOrEmptyAcousticModelIsNotReady() throws Exception {
        Map<String, byte[]> entries = baseModel();
        entries.put(MODEL + "/graph/HCLr.fst", bytes("missing-right-graph"));
        File destination = files.newFolder("incomplete");
        extract(archive(entries), destination);
        File model = new File(destination, MODEL);
        assertFalse(validModel(model));
        File rightGraph = new File(model, "graph/Gr.fst");
        Files.write(rightGraph.toPath(), bytes("graph"));
        assertTrue(validModel(model));
        Files.write(new File(model, "am/final.mdl").toPath(), new byte[0]);
        assertFalse(validModel(model));
    }

    @Test public void traversalCannotWriteOutsideDestination() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(MODEL + "/../../escaped.txt", bytes("must-not-be-written"));
        File destination = files.newFolder("traversal");
        assertThrows(IOException.class, () -> extract(archive(entries), destination));
        assertFalse(new File(destination.getParentFile(), "escaped.txt").exists());
    }

    @Test public void unexpectedRootIsRejected() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("different-model/am/final.mdl", bytes("wrong-root"));
        File destination = files.newFolder("wrong-root");
        assertThrows(IOException.class, () -> extract(archive(entries), destination));
        assertFalse(new File(destination, "different-model").exists());
    }

    @Test public void duplicateEntriesCannotOverwriteExtractedFiles() throws Exception {
        String first = MODEL + "/am/final.mdl";
        String second = MODEL + "/am/other.mdl";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(first, bytes("original"));
        entries.put(second, bytes("replacement"));
        File archive = archive(entries);
        // ZipOutputStream prohibits duplicates. Change same-length names in its local + central
        // headers to create a valid duplicate-entry archive while preserving the content CRCs.
        byte[] encoded = Files.readAllBytes(archive.toPath());
        replaceBytes(encoded, bytes(second), bytes(first));
        Files.write(archive.toPath(), encoded);
        File destination = files.newFolder("duplicates");
        IOException error = assertThrows(IOException.class, () -> extract(archive, destination));
        assertTrue(error.getMessage().contains("yinelenen"));
        assertEquals("original", new String(Files.readAllBytes(new File(destination, first).toPath()), StandardCharsets.UTF_8));
    }

    private Map<String, byte[]> baseModel() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(MODEL + "/am/final.mdl", bytes("model"));
        entries.put(MODEL + "/conf/model.conf", bytes("model-config"));
        entries.put(MODEL + "/conf/mfcc.conf", bytes("mfcc-config"));
        entries.put(MODEL + "/graph/phones/word_boundary.int", bytes("word-boundaries"));
        return entries;
    }

    private File archive(Map<String, byte[]> entries) throws IOException {
        File archive = files.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archive))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return archive;
    }

    private static void extract(File archive, File destination) throws Exception {
        Method method = VoiceModelManager.class.getDeclaredMethod("extractArchive", File.class, File.class);
        method.setAccessible(true);
        try { method.invoke(null, archive, destination); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw new AssertionError(error.getCause());
        }
    }

    private static boolean validModel(File model) throws Exception {
        Method method = VoiceModelManager.class.getDeclaredMethod("validModel", File.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, model);
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }

    private static void replaceBytes(byte[] data, byte[] from, byte[] to) {
        assertEquals(from.length, to.length);
        int replacements = 0;
        for (int offset = 0; offset <= data.length - from.length; offset++) {
            boolean matches = true;
            for (int i = 0; i < from.length; i++) {
                if (data[offset + i] != from[i]) { matches = false; break; }
            }
            if (matches) {
                System.arraycopy(to, 0, data, offset, to.length);
                replacements++;
                offset += from.length - 1;
            }
        }
        assertEquals("ZIP local and central headers must both be changed", 2, replacements);
    }
}
