package com.brouken.player;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;

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
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Persists configured upstream Stremio add-ons in one encrypted, no-backup file.
 *
 * Manifest URLs may contain account tokens, so neither URLs nor the decrypted JSON are ever
 * stored in SharedPreferences. The AES key is non-exportable and owned by Android Keystore.
 */
final class StremioStreamSourceStore {
    private static final String KEY_ALIAS =
            "com.rolex86.justplayerplus.stremio.stream.sources.v1";
    private static final String FILE_NAME = "stremio-stream-sources-v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int MAX_ENCRYPTED_BYTES = 256 * 1024;
    private static final int MAX_SOURCES = 32;

    static final class Source {
        @NonNull final String id;
        @NonNull final String manifestUrl;
        @NonNull final String name;
        final boolean enabled;

        Source(@NonNull String id,
               @NonNull String manifestUrl,
               @NonNull String name,
               boolean enabled) {
            this.id = clean(id);
            this.manifestUrl = clean(manifestUrl);
            this.name = clean(name);
            this.enabled = enabled;
        }

        static Source create(String manifestUrl, @Nullable String name) {
            return new Source(
                    UUID.randomUUID().toString(),
                    manifestUrl,
                    name == null ? "" : name,
                    true);
        }

        Source withValues(String manifestUrl, String name, boolean enabled) {
            return new Source(id, manifestUrl, name, enabled);
        }

        boolean isValid() {
            return id.matches("[a-fA-F0-9-]{16,64}")
                    && !manifestUrl.isEmpty()
                    && manifestUrl.length() <= 8_192
                    && name.length() <= 120;
        }

        private static String clean(@Nullable String value) {
            return value == null ? "" : value.trim();
        }
    }

    private final AtomicFile file;

    StremioStreamSourceStore(Context context) {
        file = new AtomicFile(new File(
                context.getApplicationContext().getNoBackupFilesDir(), FILE_NAME));
    }

    synchronized List<Source> load() {
        if (!file.getBaseFile().isFile()
                || file.getBaseFile().length() <= 0
                || file.getBaseFile().length() > MAX_ENCRYPTED_BYTES) {
            return Collections.emptyList();
        }
        try (FileInputStream input = file.openRead()) {
            byte[] encoded = new byte[(int) file.getBaseFile().length()];
            int offset = 0;
            while (offset < encoded.length) {
                int count = input.read(encoded, offset, encoded.length - offset);
                if (count < 0) {
                    return Collections.emptyList();
                }
                offset += count;
            }
            JSONObject envelope = new JSONObject(
                    new String(encoded, StandardCharsets.UTF_8));
            byte[] iv = Base64.decode(envelope.getString("iv"), Base64.DEFAULT);
            byte[] ciphertext = Base64.decode(
                    envelope.getString("data"), Base64.DEFAULT);
            if (iv.length != 12 || ciphertext.length == 0
                    || ciphertext.length > MAX_ENCRYPTED_BYTES) {
                return Collections.emptyList();
            }
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(),
                    new GCMParameterSpec(128, iv));
            JSONObject json = new JSONObject(new String(
                    cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
            JSONArray array = json.optJSONArray("sources");
            if (array == null || array.length() > MAX_SOURCES) {
                return Collections.emptyList();
            }
            List<Source> sources = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                Source source = new Source(
                        item.optString("id", ""),
                        item.optString("manifestUrl", ""),
                        item.optString("name", ""),
                        item.optBoolean("enabled", true));
                if (source.isValid()) {
                    sources.add(source);
                }
            }
            return Collections.unmodifiableList(sources);
        } catch (GeneralSecurityException | IOException | JSONException
                 | IllegalArgumentException error) {
            return Collections.emptyList();
        }
    }

    synchronized boolean save(List<Source> sources) {
        if (sources == null || sources.size() > MAX_SOURCES) {
            return false;
        }
        FileOutputStream output = null;
        try {
            JSONArray array = new JSONArray();
            for (Source source : sources) {
                if (source == null || !source.isValid()) {
                    return false;
                }
                array.put(new JSONObject()
                        .put("id", source.id)
                        .put("manifestUrl", source.manifestUrl)
                        .put("name", source.name)
                        .put("enabled", source.enabled));
            }
            byte[] plaintext = new JSONObject()
                    .put("version", 1)
                    .put("sources", array)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            if (plaintext.length > MAX_ENCRYPTED_BYTES / 2) {
                return false;
            }
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(plaintext);
            JSONObject envelope = new JSONObject()
                    .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
            output = file.startWrite();
            output.write(envelope.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
            return true;
        } catch (GeneralSecurityException | IOException | JSONException error) {
            if (output != null) {
                file.failWrite(output);
            }
            return false;
        }
    }

    synchronized void clear() {
        file.delete();
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static SecretKey getExistingKey() throws GeneralSecurityException {
        java.security.Key key = loadKeyStore().getKey(KEY_ALIAS, null);
        if (!(key instanceof SecretKey)) {
            throw new GeneralSecurityException("Missing stream source key");
        }
        return (SecretKey) key;
    }

    private static KeyStore loadKeyStore() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        try {
            keyStore.load(null);
        } catch (IOException error) {
            throw new GeneralSecurityException(error);
        }
        return keyStore;
    }
}
