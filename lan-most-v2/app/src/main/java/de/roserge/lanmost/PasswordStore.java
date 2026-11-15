package de.roserge.lanmost;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES/GCM encryption; the key never leaves Android Keystore. */
final class PasswordStore {
    private static final String KEY_ALIAS = "LAN_MOST_SMB_PASSWORD_V1";
    private static final String PREFS = "lan_most_private";
    private PasswordStore() {}

    static void save(Context context, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
            kg.generateKey();
        }
        SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        SharedPreferences.Editor e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putString("pwd", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
        e.putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        if (!e.commit()) throw new IllegalStateException("Не удалось сохранить пароль");
    }

    static String load(Context context) {
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String enc = p.getString("pwd", null), iv = p.getString("iv", null);
            if (enc == null || iv == null) return "";
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(KEY_ALIAS, null);
            if (key == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(enc, Base64.NO_WRAP)),
                StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return ""; // corrupted state or key invalidated: ask again, never crash
        }
    }

    static boolean hasSavedPassword(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("pwd");
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("pwd").remove("iv").apply();
    }
}
