package com.brouken.player.aisubtitles;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/** Private local storage for validated server results. The server owns cache invalidation. */
public final class AiSubtitleFileStore {
    private static final String DIRECTORY = "ai_subtitles";
    private static final int MAX_FILES = 20;
    private static final long MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L;

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

    public StoredSubtitle store(AiSubtitleResult result) throws IOException {
        if (!isSafeKey(result.cacheKey)
                || !"srt".equals(result.outputFormat)
                || result.language == null || result.language.trim().isEmpty()
                || result.label == null || result.label.trim().isEmpty()
                || result.label.length() > 120
                || result.label.indexOf('\r') >= 0 || result.label.indexOf('\n') >= 0
                || result.subtitleText == null || !result.subtitleText.contains(" --> ")) {
            throw new IOException("Invalid translation result");
        }
        byte[] data = result.subtitleText.getBytes(StandardCharsets.UTF_8);
        if (data.length == 0 || data.length > AiSubtitleSourceLoader.MAX_INPUT_BYTES) {
            throw new IOException("Invalid subtitle size");
        }
        ensureDirectory();
        File subtitle = new File(directory, result.cacheKey + ".srt");
        writeAtomically(subtitle, data);
        prune();
        return new StoredSubtitle(
                result.cacheKey, result.language, result.label, Uri.fromFile(subtitle));
    }

    private void ensureDirectory() throws IOException {
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.isDirectory()) {
            throw new IOException("Cannot create AI subtitle cache");
        }
    }

    private void prune() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".srt"));
        if (files == null || files.length == 0) {
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        for (int index = 0; index < files.length; index++) {
            if (index >= MAX_FILES || files[index].lastModified() < cutoff) {
                files[index].delete();
            }
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
}
