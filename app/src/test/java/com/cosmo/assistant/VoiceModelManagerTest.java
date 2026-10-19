package com.cosmo.assistant;

import android.content.Context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** Exercises safe ZIP handling and both actual packaged models without network access. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VoiceModelManagerTest {
    @Rule public final TemporaryFolder files = new TemporaryFolder();
    private static final String MODEL = VoiceModelManager.MODEL_NAME;
    private static final String WAKE_MODEL = VoiceModelManager.WAKE_MODEL_NAME;

    @Test public void validDynamicGraphExtractsAndBecomesReady() throws Exception {
        Map<String, byte[]> entries = baseModel();
        entries.put(MODEL + "/graph/HCLr.fst", bytes("graph-left"));
        entries.put(MODEL + "/graph/Gr.fst", bytes("graph-right"));
        entries.put(MODEL + "/graph/disambig_tid.int", bytes("1 2"));
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
        entries.put(MODEL + "/graph/disambig_tid.int", bytes("1 2"));
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

    @Test public void legacyFlatLayoutDoesNotRequireModernDirectories() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String name : new String[]{"final.mdl", "mfcc.conf", "HCLr.fst", "Gr.fst", "disambig_tid.int"})
            entries.put(MODEL + "/" + name, bytes("legacy"));
        File destination = files.newFolder("legacy");
        extract(archive(entries), destination);
        File model = new File(destination, MODEL);
        assertTrue(validModel(model));
        assertFalse(new File(model, "am/final.mdl").exists());
        assertFalse(new File(model, "conf/model.conf").exists());
        assertTrue(new File(model, "disambig_tid.int").delete());
        assertFalse(validModel(model));
    }

    @Test public void partialIvectorDirectoryIsNotAReadyModel() throws Exception {
        Map<String, byte[]> entries = baseModel();
        entries.put(MODEL + "/graph/HCLG.fst", bytes("static"));
        entries.put(MODEL + "/ivector/final.ie", bytes("incomplete-ivector"));
        File destination = files.newFolder("partial-ivector");
        extract(archive(entries), destination);
        File model = new File(destination, MODEL);
        assertFalse(validModel(model));
        for (String name : new String[]{"final.dubm", "final.mat", "global_cmvn.stats", "online_cmvn.conf", "splice.conf"})
            Files.write(new File(model, "ivector/" + name).toPath(), bytes("ivector"));
        assertTrue(validModel(model));
    }

    @Test public void differentArchiveCannotBypassThePinnedChecksum() throws Exception {
        Method verify = VoiceModelManager.class.getDeclaredMethod("verifyArchive", java.io.InputStream.class);
        verify.setAccessible(true);
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> verify.invoke(null, new ByteArrayInputStream(bytes("not-the-official-model"))));
        assertTrue(error.getCause() instanceof IOException);
        assertTrue(error.getCause().getMessage().contains("SHA-256"));
    }

    @Test public void realPackagedModelsInstallOfflineAndUpgradePreservesTurkishModel() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        assertTrue(VoiceModelManager.hasBundledModel(context));
        File model = VoiceModelManager.modelDirectory(context);
        File wakeModel = VoiceModelManager.wakeModelDirectory(context);
        removeGeneratedModel(model);
        removeGeneratedModel(wakeModel);
        try {
            awaitPreparation(context);
            assertTrue(VoiceModelManager.isReady(context));
            assertTrue(new File(model, "final.mdl").length() > 1_000_000L);
            assertTrue(new File(model, "Gr.fst").length() > 1_000_000L);
            assertFalse(new File(model, "am").exists());
            assertFalse(new File(model, "conf/model.conf").exists());
            assertTrue(new File(wakeModel, "am/final.mdl").length() > 1_000_000L);
            assertTrue(new File(wakeModel, "graph/HCLr.fst").length() > 1_000_000L);
            assertTrue(new File(wakeModel, "conf/model.conf").isFile());
            File sentinel = new File(model, "repeat-preparation-sentinel");
            Files.write(sentinel.toPath(), bytes("keep-existing-installation"));
            File notes = new File(context.getFilesDir(), "cosma-upgrade-test-notes");
            Files.write(notes.toPath(), bytes("keep-user-notes"));
            try {
                // Simulate upgrading 0.1.1: the Turkish model and notes already exist,
                // and preparation must add only the new FERİ wake-word model.
                removeGeneratedModel(wakeModel);
                assertFalse("Both models are required before listening", VoiceModelManager.isReady(context));
                awaitPreparation(context);
                assertTrue("An upgrade must reuse the valid Turkish model", sentinel.isFile());
                assertEquals("keep-user-notes", new String(Files.readAllBytes(notes.toPath()), StandardCharsets.UTF_8));
                assertTrue(VoiceModelManager.isReady(context));
                File wakeSentinel = new File(wakeModel, "repeat-wake-model-sentinel");
                Files.write(wakeSentinel.toPath(), bytes("keep-existing-wake-installation"));
                awaitPreparation(context);
                assertTrue("Repeat preparation must reuse both valid models", sentinel.isFile() && wakeSentinel.isFile());
                assertTrue(VoiceModelManager.isReady(context));
            } finally {
                assertTrue(notes.delete());
            }
        } finally {
            removeGeneratedModel(model);
            removeGeneratedModel(wakeModel);
        }
    }

    @Test public void wakeArchiveHasItsOwnExpectedRootAndPinnedChecksum() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(WAKE_MODEL + "/am/final.mdl", bytes("wake-model"));
        entries.put(WAKE_MODEL + "/conf/model.conf", bytes("wake-config"));
        entries.put(WAKE_MODEL + "/conf/mfcc.conf", bytes("mfcc-config"));
        entries.put(WAKE_MODEL + "/graph/HCLr.fst", bytes("graph-left"));
        entries.put(WAKE_MODEL + "/graph/Gr.fst", bytes("graph-right"));
        entries.put(WAKE_MODEL + "/graph/disambig_tid.int", bytes("1 2"));
        File archive = archive(entries);
        File destination = files.newFolder("wake");
        Method extract = VoiceModelManager.class.getDeclaredMethod("extractArchive", java.io.InputStream.class, File.class, String.class);
        extract.setAccessible(true);
        try (java.io.InputStream input = Files.newInputStream(archive.toPath())) {
            extract.invoke(null, input, destination, WAKE_MODEL);
        }
        assertTrue(validModel(new File(destination, WAKE_MODEL)));
        File wrongDestination = files.newFolder("wake-wrong-root");
        assertThrows(IOException.class, () -> extract(archive, wrongDestination));

        Method verify = VoiceModelManager.class.getDeclaredMethod("verifyArchive", java.io.InputStream.class, String.class, String.class);
        verify.setAccessible(true);
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> verify.invoke(null, new ByteArrayInputStream(bytes("not-the-official-wake-model")), VoiceModelManager.WAKE_MODEL_SHA256, "FERİ"));
        assertTrue(error.getCause() instanceof IOException);
        assertTrue(error.getCause().getMessage().contains("FERİ SHA-256"));
    }

    private static void awaitPreparation(Context context) throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<Boolean> readyAt100 = new AtomicReference<>(false);
        VoiceModelManager.download(context, new VoiceModelManager.Listener() {
            @Override public void onProgress(int percent) {
                if (percent == 100) readyAt100.set(VoiceModelManager.isReady(context));
                if (percent >= 0 && percent < 90) error.set("A packaged installation must not use network-download progress");
            }
            @Override public void onComplete() { finished.countDown(); }
            @Override public void onError(String message) { error.set(message); finished.countDown(); }
        });
        assertTrue("Model preparation timed out", finished.await(60, TimeUnit.SECONDS));
        assertNull("Model installation failed: " + error.get(), error.get());
        assertTrue("100% may only be emitted after model readiness", readyAt100.get());
        assertFalse(VoiceModelManager.isDownloading());
    }

    private static void removeGeneratedModel(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) removeGeneratedModel(child);
        if (!file.delete()) throw new IOException("Test fixture cleanup failed: " + file);
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
