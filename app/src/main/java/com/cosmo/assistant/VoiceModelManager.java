package com.cosmo.assistant;

import android.content.Context;
import android.os.StatFs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.net.URL;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.net.ssl.HttpsURLConnection;

/** Installs the verified, packaged wake-word and Turkish command models. */
public final class VoiceModelManager {
    public static final String MODEL_NAME = "vosk-model-small-tr-0.3";
    public static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip";
    public static final String MODEL_ARCHIVE_ASSET = "models/" + MODEL_NAME + ".zip";
    // Official archive downloaded over verified HTTPS and checked with both ZIP CRC and Vosk.
    public static final String MODEL_SHA256 = "8c8d07cec1bce31add14967c16891c84152cdf76d391e33c08278119b9ea96e5";
    public static final String WAKE_MODEL_NAME = "vosk-model-small-en-us-0.15";
    public static final String WAKE_MODEL_ARCHIVE_ASSET = "models/" + WAKE_MODEL_NAME + ".zip";
    public static final String WAKE_MODEL_SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498";
    private static final ModelSpec COMMAND_MODEL = new ModelSpec(MODEL_NAME, MODEL_SHA256, "Türkçe komut modeli");
    private static final ModelSpec WAKE_MODEL = new ModelSpec(WAKE_MODEL_NAME, WAKE_MODEL_SHA256, "FERİ sesleniş modeli");
    private static final ModelSpec[] MODELS = {COMMAND_MODEL, WAKE_MODEL};
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

    public static File wakeModelDirectory(Context context) {
        return new File(context.getFilesDir(), WAKE_MODEL_NAME);
    }

    public static boolean isReady(Context context) {
        return validModel(modelDirectory(context)) && validModel(wakeModelDirectory(context));
    }

    public static boolean isDownloading() {
        return worker != null;
    }

    public static boolean hasBundledModel(Context context) {
        return hasBundledModel(context, COMMAND_MODEL) && hasBundledModel(context, WAKE_MODEL);
    }

    private static boolean hasBundledModel(Context context, ModelSpec model) {
        try {
            String[] files = context.getAssets().list("models");
            if (files != null) for (String name : files) {
                if (name.equals(model.name + ".zip")) return true;
            }
        } catch (IOException ignored) {
            // A build without a packaged model can still use the verified HTTPS fallback.
        }
        return false;
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
                // A process killed during extraction may leave its private temporary directory.
                // Remove only our previous staging folders, never notes or the installed model.
                File[] abandoned = context.getFilesDir().listFiles((dir, name) -> name.startsWith(".cosmo-model-"));
                if (abandoned != null) for (File old : abandoned) deleteTree(old);
                // Both model archives are packaged. Leave room for both extracted models and,
                // in a custom build without assets, one downloaded archive at a time.
                long requiredSpace = 170L * 1024L * 1024L;
                long availableSpace = new StatFs(context.getFilesDir().getAbsolutePath()).getAvailableBytes();
                if (availableSpace > 0 && availableSpace < requiredSpace) {
                    throw new IOException("Ses modellerini açmak için en az 170 MB boş alan gerekiyor. Telefonda yer açıp yeniden dene.");
                }
                if (!staging.mkdir()) throw new IOException("Geçici model klasörü oluşturulamadı.");
                for (ModelSpec model : MODELS) {
                    prepareModel(context, staging, model, listener);
                }
            }
            if (!isReady(context)) throw new IOException("Model kalıcı klasöre kurulamadı. Boş alanı kontrol edip yeniden hazırla.");
            listener.onProgress(100);
        } catch (Exception error) {
            failure = cancelled || Thread.currentThread().isInterrupted()
                    ? "Model hazırlama iptal edildi. Yeniden başlatabilirsin."
                    : "Dil modeli kurulamadı: " + friendlyMessage(error);
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

    private static void prepareModel(Context context, File staging, ModelSpec model, Listener listener) throws IOException {
        File target = new File(context.getFilesDir(), model.name);
        if (validModel(target)) return; // An upgrade must preserve the installed Turkish model.
        checkCancelled();
        File extracted = new File(staging, "extracted");
        if (!extracted.mkdir()) throw new IOException("Model klasörü oluşturulamadı.");
        File archive = new File(staging, "model.zip");
        try {
            if (hasBundledModel(context, model)) {
                listener.onProgress(-1);
                try (InputStream input = context.getAssets().open(model.asset())) {
                    verifyArchive(input, model.sha256, model.label);
                }
                checkCancelled();
                listener.onProgress(90);
                try (InputStream input = context.getAssets().open(model.asset())) {
                    extractArchive(input, extracted, model.name);
                }
            } else {
                downloadArchive(archive, listener, model);
                checkCancelled();
                try (InputStream input = new FileInputStream(archive)) {
                    verifyArchive(input, model.sha256, model.label);
                }
                listener.onProgress(90);
                try (InputStream input = new FileInputStream(archive)) {
                    extractArchive(input, extracted, model.name);
                }
            }
            listener.onProgress(95);
            File candidate = new File(extracted, model.name);
            if (!validModel(candidate)) throw new IOException(model.label + " dosyaları eksik veya geçersiz. Güncel APK'yı kurup ses modellerini yeniden hazırla.");
            checkCancelled();
            installAtomically(candidate, target);
        } finally {
            // Extract and install one model at a time; never retain two staging copies.
            deleteTree(extracted);
            deleteTree(archive);
        }
    }

    private static void downloadArchive(File archive, Listener listener, ModelSpec model) throws IOException {
        URL address = new URL("https://alphacephei.com/vosk/models/" + model.name + ".zip");
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
                    int progress = length > 0 ? (int) Math.min(89L, total * 89L / length) : 0;
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
        try (InputStream input = new FileInputStream(archive)) { extractArchive(input, destination); }
    }

    private static void extractArchive(InputStream archive, File destination) throws IOException {
        extractArchive(archive, destination, MODEL_NAME);
    }

    private static void extractArchive(InputStream archive, File destination, String expectedModelName) throws IOException {
        String root = destination.getCanonicalPath() + File.separator;
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[32 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled();
                if (++entries > 1000) throw new IOException("Arşivde beklenenden fazla dosya var.");
                String name = entry.getName();
                if (!(name.equals(expectedModelName + "/") || name.startsWith(expectedModelName + "/"))
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
        // Vosk's official Turkish 0.3 model uses the legacy flat layout. It has no am/,
        // conf/ or graph/ directory and no model.conf; do not reject it as incomplete.
        boolean legacy = nonEmpty(root, "final.mdl") && nonEmpty(root, "mfcc.conf") && graphReady(root, "");
        boolean modern = nonEmpty(root, "am/final.mdl") && nonEmpty(root, "conf/mfcc.conf")
                && nonEmpty(root, "conf/model.conf") && graphReady(root, "graph/");
        if (!legacy && !modern) return false;
        if (new File(root, "ivector").exists()) {
            String[] ivectorFiles = {"final.ie", "final.dubm", "final.mat", "global_cmvn.stats", "online_cmvn.conf", "splice.conf"};
            for (String name : ivectorFiles) if (!nonEmpty(root, "ivector/" + name)) return false;
        }
        return true;
    }

    private static boolean graphReady(File root, String prefix) {
        return nonEmpty(root, prefix + "HCLG.fst")
                || (nonEmpty(root, prefix + "HCLr.fst") && nonEmpty(root, prefix + "Gr.fst")
                && nonEmpty(root, prefix + "disambig_tid.int"));
    }

    private static boolean nonEmpty(File root, String relative) {
        File file = new File(root, relative);
        return file.isFile() && file.length() > 0;
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("İptal edildi.");
    }

    private static void verifyArchive(InputStream input) throws IOException {
        verifyArchive(input, MODEL_SHA256, COMMAND_MODEL.label);
    }

    private static void verifyArchive(InputStream input, String expectedSha256, String label) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 doğrulaması kullanılamıyor.", impossible); }
        byte[] buffer = new byte[32 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            checkCancelled();
            total += count;
            if (total > MAX_ARCHIVE_BYTES) throw new IOException("Model arşivi boyut sınırını aştı.");
            digest.update(buffer, 0, count);
        }
        StringBuilder checksum = new StringBuilder();
        for (byte value : digest.digest()) checksum.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        if (!expectedSha256.equals(checksum.toString())) {
            throw new IOException(label + " SHA-256 doğrulaması başarısız. Güncel APK'yı yeniden indirip mevcut uygulamanın üzerine kur.");
        }
    }

    private static final class ModelSpec {
        final String name;
        final String sha256;
        final String label;

        ModelSpec(String name, String sha256, String label) {
            this.name = name;
            this.sha256 = sha256;
            this.label = label;
        }

        String asset() { return "models/" + name + ".zip"; }
    }

    private static String friendlyMessage(Exception error) {
        if (error instanceof SocketTimeoutException) return "Model sunucusu zamanında yanıt vermedi. İnternet bağlantısını kontrol edip tekrar dene.";
        if (error instanceof UnknownHostException) return "Model sunucusuna ulaşılamadı. İnternet bağlantısını kontrol edip tekrar dene.";
        String message = safeMessage(error);
        if (message.contains("ENOSPC") || message.contains("No space left")) return "Telefonda yeterli boş alan yok. Yer açıp modeli yeniden hazırla.";
        return message;
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
