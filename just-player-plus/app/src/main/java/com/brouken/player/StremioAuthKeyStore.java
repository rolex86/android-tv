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

/** Encrypted, no-backup storage for the optional Stremio account auth key. */
final class StremioAuthKeyStore {
    private static final String KEY_ALIAS =
            "com.rolex86.justplayerplus.stremio.account.v1";
    private static final String FILE_NAME = "stremio-account-auth-v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int MAX_FILE_BYTES = 8 * 1024;
    private static final Object LOCK = new Object();

    private final AtomicFile file;

    StremioAuthKeyStore(Context context) {
        file = new AtomicFile(new File(
                context.getApplicationContext().getNoBackupFilesDir(), FILE_NAME));
    }

    boolean save(String authKey) {
        String value = authKey == null ? "" : authKey.trim();
        if (value.isEmpty() || value.length() > 2048) {
            return false;
        }
        synchronized (LOCK) {
            FileOutputStream output = null;
            try {
                Cipher cipher = Cipher.getInstance(CIPHER);
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
                byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
                JSONObject envelope = new JSONObject()
                        .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                        .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
                output = file.startWrite();
                output.write(envelope.toString().getBytes(StandardCharsets.UTF_8));
                file.finishWrite(output);
                return true;
            } catch (GeneralSecurityException | IOException | JSONException error) {
                if (output != null) file.failWrite(output);
                return false;
            }
        }
    }

    @Nullable
    String load() {
        synchronized (LOCK) {
            if (!file.getBaseFile().isFile()
                    || file.getBaseFile().length() <= 0L
                    || file.getBaseFile().length() > MAX_FILE_BYTES) {
                return null;
            }
            try (FileInputStream input = file.openRead()) {
                byte[] encoded = new byte[(int) file.getBaseFile().length()];
                int offset = 0;
                while (offset < encoded.length) {
                    int count = input.read(encoded, offset, encoded.length - offset);
                    if (count < 0) return null;
                    offset += count;
                }
                JSONObject envelope = new JSONObject(
                        new String(encoded, StandardCharsets.UTF_8));
                byte[] iv = Base64.decode(envelope.getString("iv"), Base64.DEFAULT);
                byte[] data = Base64.decode(envelope.getString("data"), Base64.DEFAULT);
                if (iv.length != 12 || data.length == 0 || data.length > MAX_FILE_BYTES) {
                    return null;
                }
                Cipher cipher = Cipher.getInstance(CIPHER);
                cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(128, iv));
                String value = new String(cipher.doFinal(data), StandardCharsets.UTF_8).trim();
                return value.isEmpty() || value.length() > 2048 ? null : value;
            } catch (GeneralSecurityException | IOException | JSONException
                     | IllegalArgumentException error) {
                return null;
            }
        }
    }

    void clear() {
        synchronized (LOCK) {
            file.delete();
            try {
                loadKeyStore().deleteEntry(KEY_ALIAS);
            } catch (GeneralSecurityException ignored) {
                // The encrypted file is already gone, so the credential is no longer recoverable.
            }
        }
    }

    boolean isConfigured() {
        return load() != null;
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = loadKeyStore();
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
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
            throw new GeneralSecurityException("Missing Stremio account key");
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
