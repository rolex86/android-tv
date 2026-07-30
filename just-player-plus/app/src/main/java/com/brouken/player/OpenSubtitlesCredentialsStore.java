package com.brouken.player;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores user-supplied OpenSubtitles credentials outside preferences and encrypts them with a
 * non-exportable Android Keystore key. The encrypted file lives in the no-backup directory.
 */
final class OpenSubtitlesCredentialsStore {
    private static final String KEY_ALIAS =
            "com.rolex86.justplayerplus.opensubtitles.credentials.v1";
    private static final String FILE_NAME = "opensubtitles-credentials-v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int MAX_ENCRYPTED_BYTES = 16 * 1024;

    static final class Credentials {
        final String apiKey;
        final String username;
        final String password;

        Credentials(String apiKey, @Nullable String username, @Nullable String password) {
            this.apiKey = clean(apiKey);
            this.username = clean(username);
            this.password = password == null ? "" : password;
        }

        boolean hasApiKey() {
            return !apiKey.isEmpty();
        }

        boolean hasAccount() {
            return !username.isEmpty() && !password.isEmpty();
        }

        boolean isValid() {
            return hasApiKey()
                    && (username.isEmpty() == password.isEmpty())
                    && apiKey.length() <= 512
                    && username.length() <= 320
                    && password.length() <= 1024;
        }

        private static String clean(@Nullable String value) {
            return value == null ? "" : value.trim();
        }
    }

    private final AtomicFile file;

    OpenSubtitlesCredentialsStore(Context context) {
        file = new AtomicFile(new File(
                context.getApplicationContext().getNoBackupFilesDir(), FILE_NAME));
    }

    synchronized boolean save(Credentials credentials) {
        if (credentials == null || !credentials.isValid()) {
            return false;
        }
        FileOutputStream output = null;
        try {
            JSONObject json = new JSONObject()
                    .put("apiKey", credentials.apiKey)
                    .put("username", credentials.username)
                    .put("password", credentials.password);
            byte[] plaintext = json.toString().getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] ciphertext = cipher.doFinal(plaintext);
            JSONObject envelope = new JSONObject()
                    .put("iv", Base64.encodeToString(
                            cipher.getIV(), Base64.NO_WRAP))
                    .put("data", Base64.encodeToString(
                            ciphertext, Base64.NO_WRAP));
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

    @Nullable
    synchronized Credentials load() {
        if (!file.getBaseFile().isFile()
                || file.getBaseFile().length() <= 0
                || file.getBaseFile().length() > MAX_ENCRYPTED_BYTES) {
            return null;
        }
        try (FileInputStream input = file.openRead()) {
            byte[] encoded = new byte[(int) file.getBaseFile().length()];
            int offset = 0;
            while (offset < encoded.length) {
                int count = input.read(encoded, offset, encoded.length - offset);
                if (count < 0) {
                    return null;
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
                return null;
            }
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(),
                    new GCMParameterSpec(128, iv));
            JSONObject json = new JSONObject(new String(
                    cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
            Credentials credentials = new Credentials(
                    json.optString("apiKey", ""),
                    json.optString("username", ""),
                    json.optString("password", ""));
            return credentials.isValid() ? credentials : null;
        } catch (GeneralSecurityException | IOException | JSONException
                 | IllegalArgumentException error) {
            return null;
        }
    }

    synchronized void clear() {
        file.delete();
    }

    boolean isConfigured() {
        Credentials credentials = load();
        return credentials != null && credentials.hasApiKey();
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        try {
            keyStore.load(null);
        } catch (IOException error) {
            throw new GeneralSecurityException(error);
        }
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
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        try {
            keyStore.load(null);
        } catch (IOException error) {
            throw new GeneralSecurityException(error);
        }
        java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
        if (!(key instanceof SecretKey)) {
            throw new GeneralSecurityException("Missing credentials key");
        }
        return (SecretKey) key;
    }
}
