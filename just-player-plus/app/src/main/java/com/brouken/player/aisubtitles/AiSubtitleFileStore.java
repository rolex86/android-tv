package com.brouken.player.aisubtitles;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Private local cache isolated from the legacy subtitle cache cleanup. */
public final class AiSubtitleFileStore {
    private static final String DIRECTORY = "ai_subtitles";

    public static final class StoredSubtitle {
        public final String cacheKey;
        public final String language;
        public final String label;
        public final Uri uri;

        StoredSubtitle(String cacheKey, String language, String label, Uri uri) {
            this.cacheKey = cacheKey;
            this.language = language;
            this.label = label;
            this.uri = uri;
        }
    }

    private final File directory;

    public AiSubtitleFileStore(Context context) {
        directory = new File(context.getCacheDir(), DIRECTORY);
    }

    public static String sourceFingerprint(String subtitleText, String targetLanguage) {
        return sha256(normalizeLineEndings(subtitleText) + "\n" + targetLanguage);
    }

    @Nullable
    public StoredSubtitle find(String fingerprint, String targetLanguage) {
        if (!isSafeKey(fingerprint)) {
            return null;
        }
        File metadata = new File(directory, fingerprint + ".json");
        if (!metadata.isFile()) {
            return null;
        }
        try (FileInputStream input = new FileInputStream(metadata)) {
            byte[] bytes = new byte[(int) Math.min(metadata.length(), 4096L)];
            int count = input.read(bytes);
            if (count <= 0) {
                return null;
            }
            JSONObject json = new JSONObject(new String(
                    bytes, 0, count, StandardCharsets.UTF_8));
            String cacheKey = json.optString("cacheKey", "");
            String language = json.optString("language", "");
            String label = json.optString("label", "");
            File subtitle = new File(directory, cacheKey + ".srt");
            if (!isSafeKey(cacheKey) || !targetLanguage.equals(language)
                    || label.isEmpty() || !subtitle.isFile()
                    || subtitle.length() <= 0L
                    || subtitle.length() > AiSubtitleSourceLoader.MAX_INPUT_BYTES) {
                return null;
            }
            return new StoredSubtitle(
                    cacheKey, language, AiSubtitleTrackFactory.CZECH_AI_LABEL,
                    Uri.fromFile(subtitle));
        } catch (IOException | JSONException ignored) {
            return null;
        }
    }

    public StoredSubtitle store(String fingerprint, AiSubtitleResult result) throws IOException {
        if (!isSafeKey(fingerprint) || !isSafeKey(result.cacheKey)) {
            throw new IOException("Unsafe cache key");
        }
        ensureDirectory();
        File subtitle = new File(directory, result.cacheKey + ".srt");
        writeAtomically(subtitle, result.subtitleText.getBytes(StandardCharsets.UTF_8));
        try {
            JSONObject metadata = new JSONObject();
            metadata.put("cacheKey", result.cacheKey);
            metadata.put("language", result.language);
            metadata.put("label", AiSubtitleTrackFactory.CZECH_AI_LABEL);
            writeAtomically(new File(directory, fingerprint + ".json"),
                    metadata.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException error) {
            throw new IOException("Cannot write cache metadata", error);
        }
        return new StoredSubtitle(
                result.cacheKey, result.language, AiSubtitleTrackFactory.CZECH_AI_LABEL,
                Uri.fromFile(subtitle));
    }

    private void ensureDirectory() throws IOException {
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.isDirectory()) {
            throw new IOException("Cannot create AI subtitle cache");
        }
    }

    private static void writeAtomically(File destination, byte[] data) throws IOException {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(data);
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IOException("Cannot replace cached subtitle");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Cannot finalize cached subtitle");
        }
    }

    private static boolean isSafeKey(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{8,128}");
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                encoded.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
