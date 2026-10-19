package com.cosmo.assistant;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URL;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.net.ssl.HttpsURLConnection;

/** Downloads the official Turkish Vosk model only after an explicit user action. */
public final class VoiceModelManager {
    public static final String MODEL_NAME = "vosk-model-small-tr-0.3";
    public static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip";
    private static final long MAX_ARCHIVE_BYTES = 90L * 1024L * 1024L;
    private static final long MAX_EXTRACTED_BYTES = 250L * 1024L * 1024L;
    private static final Object LOCK = new Object();
    private static volatile Thread worker;
    private static volatile boolean cancelled;
    private static volatile HttpsURLConnection connection;

    public interface Listener {
        /** Callbacks run on the download thread. The UI must post updates to its main thread. */
        void onProgress(int percent);
        void onComplete();
        void onError(String message);
    }

    private VoiceModelManager() { }

    public static File modelDirectory(Context context) {
        return new File(context.getFilesDir(), MODEL_NAME);
    }

    public static boolean isReady(Context context) {
        return validModel(modelDirectory(context));
    }

    public static boolean isDownloading() {
        return worker != null;
    }

    public static void download(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (worker != null) {
                listener.onError("Dil modeli indirmesi zaten devam ediyor.");
                return;
            }
            cancelled = false;
            worker = new Thread(() -> downloadOnWorker(app, listener), "cosmo-model-download");
            worker.start();
        }
    }

    public static void cancelDownload() {
        synchronized (LOCK) {
            cancelled = true;
            if (worker != null) worker.interrupt();
            HttpsURLConnection current = connection;
            if (current != null) current.disconnect();
        }
    }

    private static void downloadOnWorker(Context context, Listener listener) {
        File staging = new File(context.getFilesDir(), ".cosmo-model-" + UUID.randomUUID());
        String failure = null;
        try {
            checkCancelled();
            if (!isReady(context)) {
                if (!staging.mkdir()) throw new IOException("Geçici model klasörü oluşturulamadı.");
                File archive = new File(staging, "model.zip");
                downloadArchive(archive, listener);
                checkCancelled();
                listener.onProgress(91);
                File extracted = new File(staging, "extracted");
                if (!extracted.mkdir()) throw new IOException("Model klasörü oluşturulamadı.");
                extractArchive(archive, extracted);
                File candidate = new File(extracted, MODEL_NAME);
                if (!validModel(candidate)) throw new IOException("İndirilen dil modeli eksik veya geçersiz.");
                checkCancelled();
                installAtomically(candidate, modelDirectory(context));
            }
            listener.onProgress(100);
        } catch (Exception error) {
            failure = cancelled || error instanceof InterruptedIOException
                    ? "Dil modeli indirmesi iptal edildi."
                    : "Dil modeli kurulamadı: " + safeMessage(error);
        } finally {
            HttpsURLConnection current = connection;
            if (current != null) current.disconnect();
            connection = null;
            deleteTree(staging);
            synchronized (LOCK) { worker = null; }
        }
        // Clear the active download before reporting completion, so retry is immediately possible.
        if (failure == null) listener.onComplete(); else listener.onError(failure);
    }

    private static void downloadArchive(File archive, Listener listener) throws IOException {
        URL address = new URL(MODEL_URL);
        for (int redirects = 0; redirects <= 3; redirects++) {
            checkCancelled();
            if (!"https".equalsIgnoreCase(address.getProtocol())
                    || !"alphacephei.com".equalsIgnoreCase(address.getHost())) {
                throw new IOException("Model yalnızca resmi HTTPS adresinden indirilebilir.");
            }
            HttpsURLConnection request = (HttpsURLConnection) address.openConnection();
            connection = request;
            request.setConnectTimeout(20_000);
            request.setReadTimeout(20_000);
            request.setInstanceFollowRedirects(false);
            request.setRequestProperty("Accept-Encoding", "identity");
            request.setRequestProperty("User-Agent", "COSMO-Android/1.0");
            int status = request.getResponseCode();
            if (status >= 300 && status <= 399) {
                String location = request.getHeaderField("Location");
                request.disconnect();
                if (location == null || redirects == 3) throw new IOException("Model adresi yönlendirmesi geçersiz.");
                address = new URL(address, location);
                continue;
            }
            if (status != HttpsURLConnection.HTTP_OK) {
                request.disconnect();
                throw new IOException("Sunucu HTTP " + status + " yanıtını verdi. Bağlantıyı kontrol edip yeniden deneyin.");
            }
            long length = request.getContentLengthLong();
            if (length > MAX_ARCHIVE_BYTES) throw new IOException("Model arşivi beklenenden büyük.");
            listener.onProgress(0);
            try (BufferedInputStream input = new BufferedInputStream(request.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(archive))) {
                byte[] buffer = new byte[32 * 1024];
                long total = 0;
                int previous = -1;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled();
                    total += read;
                    if (total > MAX_ARCHIVE_BYTES) throw new IOException("Model arşivi boyut sınırını aştı.");
                    output.write(buffer, 0, read);
                    int progress = length > 0 ? (int) Math.min(90L, total * 90L / length) : 0;
                    if (progress != previous) {
                        listener.onProgress(progress);
                        previous = progress;
                    }
                }
                if (total == 0 || (length > 0 && total != length)) {
                    throw new IOException("Model indirmesi tamamlanamadı.");
                }
            } finally {
                request.disconnect();
                connection = null;
            }
            return;
        }
        throw new IOException("Model adresine ulaşılamadı.");
    }

    private static void extractArchive(File archive, File destination) throws IOException {
        String root = destination.getCanonicalPath() + File.separator;
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[32 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled();
                if (++entries > 1000) throw new IOException("Arşivde beklenenden fazla dosya var.");
                String name = entry.getName();
                if (!(name.equals(MODEL_NAME + "/") || name.startsWith(MODEL_NAME + "/"))
                        || name.contains("\\") || name.contains(":")) {
                    throw new IOException("Arşivde geçersiz dosya yolu var.");
                }
                File target = new File(destination, name);
                if (!target.getCanonicalPath().startsWith(root)) {
                    throw new IOException("Arşiv güvenli model klasörünün dışına yazamaz.");
                }
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Model klasörü açılamadı.");
                } else {
                    File parent = target.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Model klasörü açılamadı.");
                    if (!target.createNewFile()) throw new IOException("Arşivde yinelenen dosya var.");
                    try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            checkCancelled();
                            total += read;
                            if (total > MAX_EXTRACTED_BYTES) throw new IOException("Açılan model boyut sınırını aştı.");
                            output.write(buffer, 0, read);
                        }
                    }
                }
                // ZipInputStream validates each entry's CRC while consuming it.
                zip.closeEntry();
            }
        }
        if (entries == 0) throw new IOException("Dil modeli arşivi boş.");
    }

    private static void installAtomically(File candidate, File target) throws IOException {
        if (validModel(target)) return; // Never replace a working model, including one in use.
        File backup = new File(target.getParentFile(), ".cosmo-old-model-" + UUID.randomUUID());
        boolean hadOld = target.exists();
        if (hadOld && !target.renameTo(backup)) throw new IOException("Eski model klasörü taşınamadı.");
        if (!candidate.renameTo(target)) {
            if (hadOld && !backup.renameTo(target)) throw new IOException("Model kurulamadı; eski klasör geri alınamadı.");
            throw new IOException("Dil modeli kalıcı klasöre taşınamadı.");
        }
        deleteTree(backup);
    }

    private static boolean validModel(File root) {
        String[] required = {"am/final.mdl", "conf/mfcc.conf", "conf/model.conf",
                "graph/phones/word_boundary.int"};
        for (String relative : required) {
            File file = new File(root, relative);
            if (!file.isFile() || file.length() <= 0) return false;
        }
        // Vosk supports both a static graph and a compact two-part dynamic graph.
        return nonEmpty(root, "graph/HCLG.fst")
                || (nonEmpty(root, "graph/HCLr.fst") && nonEmpty(root, "graph/Gr.fst"));
    }

    private static boolean nonEmpty(File root, String relative) {
        File file = new File(root, relative);
        return file.isFile() && file.length() > 0;
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("İptal edildi.");
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "Bağlantı veya depolama hatası." : message;
    }

    private static void deleteTree(File target) {
        if (target == null || !target.exists()) return;
        File[] children = target.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        // Only generated staging/invalid backup paths are removed; never the active model.
        target.delete();
    }
}
