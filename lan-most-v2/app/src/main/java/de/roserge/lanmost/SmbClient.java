package de.roserge.lanmost;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import androidx.documentfile.provider.DocumentFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;
import jcifs.smb.SmbFileOutputStream;

/** Small SMB2/3 client: no cloud, no remote server, no destructive operations. */
final class SmbClient {
    static final class Entry {
        final String name;
        final boolean directory;
        final long length;
        Entry(String name, boolean directory, long length) {
            this.name = name; this.directory = directory; this.length = length;
        }
    }

    private final String root;
    private final CIFSContext context;

    SmbClient(String server, String share, String user, String domain, String password)
        throws Exception {
        if (!server.matches("[A-Za-z0-9.-]{1,253}") || server.contains("..") ||
                server.startsWith("-") || server.endsWith("-"))
            throw new IllegalArgumentException("Неверный IP-адрес или имя компьютера");
        if (share.trim().isEmpty() || share.contains("/") || share.contains("\\"))
            throw new IllegalArgumentException("Укажи имя общей папки Windows");
        if (user.trim().isEmpty())
            throw new IllegalArgumentException("Укажи пользователя Windows");
        Properties properties = new Properties();
        properties.setProperty("jcifs.smb.client.minVersion", "SMB202");
        properties.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        properties.setProperty("jcifs.smb.client.connTimeout", "10000");
        properties.setProperty("jcifs.smb.client.responseTimeout", "20000");
        properties.setProperty("jcifs.smb.client.disablePlainTextPasswords", "true");
        properties.setProperty("jcifs.smb.client.allowGuestFallback", "false");
        CIFSContext base = new BaseContext(new PropertyConfiguration(properties));
        context = base.withCredentials(new NtlmPasswordAuthenticator(domain, user, password));
        root = "smb://" + server + "/" + encode(share.trim()) + "/";
    }

    private static String encode(String segment) {
        try {
            return URLEncoder.encode(segment, "UTF-8").replace("+", "%20");
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String url(List<String> folders, String filename) {
        StringBuilder b = new StringBuilder(root);
        for (String dir : folders) b.append(encode(dir)).append('/');
        if (filename != null) b.append(encode(filename));
        return b.toString();
    }

    List<Entry> list(List<String> folders) throws Exception {
        List<Entry> output = new ArrayList<>();
        try (SmbFile dir = new SmbFile(url(folders, null), context)) {
            if (!dir.exists() || !dir.isDirectory())
                throw new IOException("Общая папка не существует или недоступна: " + root);
            for (SmbFile file : dir.listFiles()) {
                try (SmbFile ignored = file) {
                    String name = file.getName();
                    if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                    if (name.isEmpty() || name.equals(".") || name.equals("..")) continue;
                    boolean isDir = file.isDirectory();
                    output.add(new Entry(name, isDir, isDir ? -1 : file.length()));
                }
            }
        }
        Collections.sort(output, (a,b) -> {
            if (a.directory != b.directory) return a.directory ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return output;
    }

    /** Copy a SMB file to Downloads/LAN Most through MediaStore (Android 10+). */
    String download(Context appContext, List<String> folders, String name) throws Exception {
        String filename = cleanFilename(name);
        ContentResolver resolver = appContext.getContentResolver();
        Uri destination = null;
        try (SmbFile source = new SmbFile(url(folders, name), context);
             InputStream input = new SmbFileInputStream(source)) {
            if (source.isDirectory()) throw new IOException("Это папка, не файл");
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType(filename));
            values.put(MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/LAN Most");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (destination == null) throw new IOException("Не удалось создать файл на телефоне");
            try (OutputStream output = resolver.openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("Не удалось открыть файл на телефоне");
                transfer(input, output);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            resolver.update(destination, done, null, null);
            return filename;
        } catch (Exception failure) {
            if (destination != null) resolver.delete(destination, null, null);
            throw failure;
        }
    }

    /** Copy one PC file into the explicitly chosen Android folder via SAF.
     * This never overwrites or silently replaces an existing phone file. */
    String downloadIntoFolder(Context appContext, List<String> folders, String name,
                              DocumentFile destinationDirectory) throws Exception {
        String clean = cleanFilename(name);
        if (destinationDirectory == null || !destinationDirectory.isDirectory()
                || !destinationDirectory.canWrite())
            throw new IOException("Выбранная папка телефона недоступна для записи");
        if (destinationDirectory.findFile(clean) != null)
            throw new IOException("Файл «" + clean + "» уже есть на телефоне — не перезаписан");
        DocumentFile newlyCreated = null;
        try (SmbFile source = new SmbFile(url(folders, name), context)) {
            if (source.isDirectory()) throw new IOException("Это папка, а не файл");
            newlyCreated = destinationDirectory.createFile(mimeType(clean), clean);
            if (newlyCreated == null) throw new IOException("Не удалось создать файл на телефоне");
            try (InputStream input = new SmbFileInputStream(source);
                 OutputStream output = appContext.getContentResolver()
                     .openOutputStream(newlyCreated.getUri(), "w")) {
                if (output == null) throw new IOException("Не удалось записать файл на телефон");
                transfer(input, output);
            }
            return newlyCreated.getName();
        } catch (Exception failure) {
            if (newlyCreated != null) newlyCreated.delete(); // only the new partial file
            throw failure;
        }
    }

    /** Refuse to overwrite any existing Windows file. */
    String upload(Context appContext, List<String> folders, Uri source) throws Exception {
        ContentResolver resolver = appContext.getContentResolver();
        String name = getDisplayName(resolver, source);
        String clean = cleanFilename(name);
        try (SmbFile target = new SmbFile(url(folders, clean), context)) {
            if (target.exists()) throw new IOException("Файл «" + clean + "» уже есть на ПК — не перезаписан");
            // Atomic create-if-absent: prevents silent overwrite if another client
            // writes the same name between checking and opening this file.
            try (InputStream input = resolver.openInputStream(source)) {
                if (input == null) throw new IOException("Не удалось открыть файл на телефоне");
                target.createNewFile();
                try (OutputStream output = new SmbFileOutputStream(target)) {
                    transfer(input, output);
                }
            }
            // On a failed transfer leave the partial file untouched. Never delete
            // anything on the Windows computer without the user's explicit action.
        }
        return clean;
    }

    static String cleanFilename(String name) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (clean.isEmpty() || clean.equals(".") || clean.equals(".."))
            throw new IllegalArgumentException("Неверное имя файла");
        return clean;
    }

    private static String getDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        }
        String last = uri.getLastPathSegment();
        return last == null ? "file.bin" : last.substring(last.lastIndexOf('/') + 1);
    }

    private static String mimeType(String file) {
        int dot = file.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            file.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
        return type == null ? "application/octet-stream" : type;
    }

    private static void transfer(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) output.write(buffer, 0, read);
        }
        output.flush();
    }
}
