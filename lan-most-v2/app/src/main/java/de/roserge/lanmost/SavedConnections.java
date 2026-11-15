package de.roserge.lanmost;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Named SMB profiles with password encryption in Android Keystore, never in Git. */
final class SavedConnections {
    private static final String KEY_ALIAS = "LAN_MOST_CONNECTIONS_AES_V1";
    private static final String PREF_NAME = "lan_most_named_connections";
    private final SharedPreferences prefs;

    static final class Profile {
        final String name, host, share, user, domain, password;
        Profile(String name, String host, String share, String user, String domain, String password) {
            this.name = name; this.host = host; this.share = share;
            this.user = user; this.domain = domain; this.password = password;
        }
    }

    SavedConnections(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator keygen = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            keygen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            keygen.generateKey();
        }
        return (SecretKey) store.getKey(KEY_ALIAS, null);
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
        return Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    private String decrypt(String encoded) throws Exception {
        byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
        if (packed.length < 29) throw new IllegalArgumentException("Повреждён пароль профиля");
        byte[] iv = new byte[12];
        System.arraycopy(packed, 0, iv, 0, 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(packed, 12, packed.length - 12), StandardCharsets.UTF_8);
    }

    private JSONArray records() throws Exception {
        return new JSONArray(prefs.getString("profiles", "[]"));
    }

    String[] names() throws Exception {
        JSONArray all = records();
        String[] result = new String[all.length()];
        for (int i = 0; i < all.length(); i++) result[i] = all.getJSONObject(i).getString("name");
        return result;
    }

    Profile load(int index) throws Exception {
        JSONObject item = records().getJSONObject(index);
        return new Profile(item.getString("name"), item.getString("host"),
                item.getString("share"), item.getString("user"),
                item.optString("domain", ""), decrypt(item.getString("password")));
    }

    void save(Profile profile) throws Exception {
        if (profile.name.trim().isEmpty()) throw new IllegalArgumentException("Укажи имя подключения");
        JSONArray source = records();
        JSONArray target = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.getJSONObject(i);
            if (!profile.name.equals(item.getString("name"))) target.put(item);
        }
        JSONObject item = new JSONObject();
        item.put("name", profile.name);
        item.put("host", profile.host);
        item.put("share", profile.share);
        item.put("user", profile.user);
        item.put("domain", profile.domain);
        item.put("password", encrypt(profile.password));
        target.put(item);
        if (!prefs.edit().putString("profiles", target.toString()).commit())
            throw new IllegalStateException("Не удалось сохранить профиль");
    }

    void delete(String name) throws Exception {
        JSONArray source = records(), target = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.getJSONObject(i);
            if (!name.equals(item.getString("name"))) target.put(item);
        }
        if (!prefs.edit().putString("profiles", target.toString()).commit())
            throw new IllegalStateException("Не удалось удалить профиль");
    }
}
