package com.brouken.player;

import android.content.Context;
import android.util.AtomicFile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Persists only the non-sensitive routing subset of validated Stremio manifests. */
final class StremioManifestCache {
    static final long FRESH_AGE_MS = 60L * 60L * 1_000L;
    static final long MAX_STALE_AGE_MS = 24L * 60L * 60L * 1_000L;

    private static final String FILE_NAME = "stremio-manifest-routing-cache-v1";
    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_ENTRIES = 32;
    private static final int MAX_ROUTING_VALUES = 64;
    private static final int MAX_ROUTING_VALUE_LENGTH = 160;
    private static final int MAX_VALIDATOR_LENGTH = 512;

    private final AtomicFile file;

    StremioManifestCache(Context context) {
        file = new AtomicFile(new File(
                context.getApplicationContext().getNoBackupFilesDir(), FILE_NAME));
    }

    @Nullable
    synchronized Entry find(StremioStreamSourceStore.Source source) {
        List<Entry> entries = readEntries();
        String expectedFingerprint = fingerprint(source.manifestUrl);
        for (Entry entry : entries) {
            if (source.id.equals(entry.sourceId)
                    && expectedFingerprint.equals(entry.manifestFingerprint)) {
                return entry;
            }
        }
        return null;
    }

    synchronized boolean replace(
            StremioStreamSourceStore.Source source,
            JSONObject manifest,
            @Nullable String etag,
            @Nullable String lastModified,
            long validatedAtMs) {
        JSONObject routingManifest = sanitizeManifest(manifest);
        if (!StremioAddonClient.hasStreamResource(routingManifest)
                || validatedAtMs < 0L) {
            return false;
        }
        List<Entry> entries = readEntries();
        removeSource(entries, source.id, fingerprint(source.manifestUrl));
        while (entries.size() >= MAX_ENTRIES) {
            int oldestIndex = 0;
            for (int index = 1; index < entries.size(); index++) {
                if (entries.get(index).validatedAtMs
                        < entries.get(oldestIndex).validatedAtMs) {
                    oldestIndex = index;
                }
            }
            entries.remove(oldestIndex);
        }
        entries.add(new Entry(
                source.id,
                fingerprint(source.manifestUrl),
                routingManifest,
                cleanValidator(etag),
                cleanValidator(lastModified),
                validatedAtMs));
        return writeEntries(entries);
    }

    synchronized boolean touch(
            StremioStreamSourceStore.Source source,
            Entry existing,
            @Nullable String etag,
            @Nullable String lastModified,
            long validatedAtMs) {
        if (!source.id.equals(existing.sourceId)
                || !fingerprint(source.manifestUrl).equals(existing.manifestFingerprint)) {
            return false;
        }
        return replace(
                source,
                existing.manifest,
                etag == null ? existing.etag : etag,
                lastModified == null ? existing.lastModified : lastModified,
                validatedAtMs);
    }

    synchronized void invalidate(StremioStreamSourceStore.Source source) {
        List<Entry> entries = readEntries();
        if (removeSource(entries, source.id, fingerprint(source.manifestUrl))) {
            writeEntries(entries);
        }
    }

    static boolean isFresh(long validatedAtMs, long nowMs) {
        return hasAgeAtMost(validatedAtMs, nowMs, FRESH_AGE_MS);
    }

    static boolean isUsableStale(long validatedAtMs, long nowMs) {
        return hasAgeAtMost(validatedAtMs, nowMs, MAX_STALE_AGE_MS);
    }

    @NonNull
    static String fingerprint(@Nullable String value) {
        String normalized = value == null ? "" : value.trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder();
            for (byte item : digest) {
                encoded.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    /** Removes names, URLs and arbitrary manifest fields before anything reaches disk. */
    @NonNull
    static JSONObject sanitizeManifest(@Nullable JSONObject manifest) {
        JSONObject sanitized = new JSONObject();
        JSONArray resources = new JSONArray();
        if (manifest == null) {
            return sanitized;
        }
        JSONArray rawResources = manifest.optJSONArray("resources");
        if (rawResources != null) {
            int limit = Math.min(rawResources.length(), MAX_ROUTING_VALUES);
            for (int index = 0; index < limit; index++) {
                Object raw = rawResources.opt(index);
                if (raw instanceof String && "stream".equals(raw)) {
                    resources.put("stream");
                } else if (raw instanceof JSONObject
                        && "stream".equals(((JSONObject) raw).optString("name", ""))) {
                    JSONObject resource = (JSONObject) raw;
                    JSONObject item = new JSONObject();
                    putQuietly(item, "name", "stream");
                    if (resource.has("types")) {
                        putQuietly(item, "types", copyStrings(
                                resource.optJSONArray("types")));
                    }
                    if (resource.has("idPrefixes")) {
                        putQuietly(item, "idPrefixes", copyStrings(
                                resource.optJSONArray("idPrefixes")));
                    }
                    resources.put(item);
                }
            }
        }
        putQuietly(sanitized, "resources", resources);
        if (manifest.has("types")) {
            putQuietly(sanitized, "types", copyStrings(manifest.optJSONArray("types")));
        }
        if (manifest.has("idPrefixes")) {
            putQuietly(sanitized, "idPrefixes", copyStrings(
                    manifest.optJSONArray("idPrefixes")));
        }
        return sanitized;
    }

    private static JSONArray copyStrings(@Nullable JSONArray values) {
        JSONArray copied = new JSONArray();
        if (values == null) {
            return copied;
        }
        int limit = Math.min(values.length(), MAX_ROUTING_VALUES);
        for (int index = 0; index < limit; index++) {
            String value = values.optString(index, null);
            if (value != null && !value.isEmpty()) {
                copied.put(value.substring(0, Math.min(
                        value.length(), MAX_ROUTING_VALUE_LENGTH)));
            }
        }
        return copied;
    }

    private static void putQuietly(JSONObject target, String key, Object value) {
        try {
            target.put(key, value);
        } catch (JSONException ignored) {
            // All keys and values above are bounded JSON primitives/containers.
        }
    }

    private static boolean hasAgeAtMost(long createdAtMs, long nowMs, long maxAgeMs) {
        return createdAtMs >= 0L
                && nowMs >= createdAtMs
                && nowMs - createdAtMs <= maxAgeMs;
    }

    private static boolean removeSource(
            List<Entry> entries,
            String sourceId,
            String manifestFingerprint) {
        boolean removed = false;
        for (int index = entries.size() - 1; index >= 0; index--) {
            Entry entry = entries.get(index);
            if (sourceId.equals(entry.sourceId)
                    && manifestFingerprint.equals(entry.manifestFingerprint)) {
                entries.remove(index);
                removed = true;
            }
        }
        return removed;
    }

    private List<Entry> readEntries() {
        if (!file.getBaseFile().isFile()
                || file.getBaseFile().length() <= 0L
                || file.getBaseFile().length() > MAX_FILE_BYTES) {
            return new ArrayList<>();
        }
        try (FileInputStream input = file.openRead()) {
            byte[] encoded = new byte[(int) file.getBaseFile().length()];
            int offset = 0;
            while (offset < encoded.length) {
                int count = input.read(encoded, offset, encoded.length - offset);
                if (count < 0) {
                    return new ArrayList<>();
                }
                offset += count;
            }
            JSONObject root = new JSONObject(new String(encoded, StandardCharsets.UTF_8));
            JSONArray array = root.optJSONArray("entries");
            if (root.optInt("version", 0) != 1
                    || array == null || array.length() > MAX_ENTRIES) {
                return new ArrayList<>();
            }
            List<Entry> entries = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                JSONObject manifest = item.optJSONObject("routing");
                Entry entry = new Entry(
                        item.optString("sourceId", ""),
                        item.optString("manifestFingerprint", ""),
                        sanitizeManifest(manifest),
                        cleanValidator(item.optString("etag", null)),
                        cleanValidator(item.optString("lastModified", null)),
                        item.optLong("validatedAtMs", -1L));
                if (!entry.sourceId.isEmpty()
                        && !entry.manifestFingerprint.isEmpty()
                        && entry.validatedAtMs >= 0L
                        && StremioAddonClient.hasStreamResource(entry.manifest)) {
                    entries.add(entry);
                }
            }
            return entries;
        } catch (IOException | JSONException | RuntimeException error) {
            return new ArrayList<>();
        }
    }

    private boolean writeEntries(List<Entry> entries) {
        FileOutputStream output = null;
        try {
            JSONArray array = new JSONArray();
            for (Entry entry : entries) {
                array.put(new JSONObject()
                        .put("sourceId", entry.sourceId)
                        .put("manifestFingerprint", entry.manifestFingerprint)
                        .put("routing", entry.manifest)
                        .put("etag", entry.etag)
                        .put("lastModified", entry.lastModified)
                        .put("validatedAtMs", entry.validatedAtMs));
            }
            byte[] encoded = new JSONObject()
                    .put("version", 1)
                    .put("entries", array)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_FILE_BYTES) {
                return false;
            }
            output = file.startWrite();
            output.write(encoded);
            file.finishWrite(output);
            return true;
        } catch (IOException | JSONException | RuntimeException error) {
            if (output != null) {
                file.failWrite(output);
            }
            return false;
        }
    }

    @Nullable
    private static String cleanValidator(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.substring(0, Math.min(cleaned.length(), MAX_VALIDATOR_LENGTH));
    }

    static final class Entry {
        @NonNull final String sourceId;
        @NonNull final String manifestFingerprint;
        @NonNull final JSONObject manifest;
        @Nullable final String etag;
        @Nullable final String lastModified;
        final long validatedAtMs;

        Entry(String sourceId,
              String manifestFingerprint,
              JSONObject manifest,
              @Nullable String etag,
              @Nullable String lastModified,
              long validatedAtMs) {
            this.sourceId = sourceId;
            this.manifestFingerprint = manifestFingerprint;
            this.manifest = sanitizeManifest(manifest);
            this.etag = cleanValidator(etag);
            this.lastModified = cleanValidator(lastModified);
            this.validatedAtMs = validatedAtMs;
        }
    }
}
