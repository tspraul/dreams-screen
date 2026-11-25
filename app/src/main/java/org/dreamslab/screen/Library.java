package org.dreamslab.screen;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The app's own media folders:
 *   photos/  pictures kids snap (and pictures copied from a USB stick)
 *   clips/   short how-to videos
 *   music/   songs for the music player
 * Photos older than 4 weeks are removed automatically.
 */
final class Library {
    static final String[] KINDS = {"photos", "clips", "music"};
    static final long PHOTO_DAYS = 28;

    private Library() { }

    static File dir(Context ctx, String kind) {
        File d = new File(ctx.getFilesDir(), "media/" + kind);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static boolean validKind(String kind) {
        return Arrays.asList(KINDS).contains(kind);
    }

    /** A file name with no folders or odd characters. */
    static String cleanName(String name) {
        if (name == null) return "";
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.equals(".") || name.equals("..")) return "";
        return name;
    }

    static File file(Context ctx, String kind, String name) {
        if (!validKind(kind)) return null;
        String n = cleanName(name);
        if (n.isEmpty()) return null;
        File f = new File(dir(ctx, kind), n);
        return f.exists() ? f : null;
    }

    static void prunePhotos(Context ctx) {
        long cutoff = System.currentTimeMillis() - PHOTO_DAYS * 24L * 3600L * 1000L;
        File[] fs = dir(ctx, "photos").listFiles();
        if (fs == null) return;
        for (File f : fs) if (f.lastModified() < cutoff) f.delete();
    }

    /** {photos:[{name,time,size}], clips:[...], music:[...]} newest first. */
    static String listJson(Context ctx) {
        prunePhotos(ctx);
        try {
            JSONObject o = new JSONObject();
            for (String kind : KINDS) {
                File[] fs = dir(ctx, kind).listFiles();
                if (fs == null) fs = new File[0];
                Arrays.sort(fs, new Comparator<File>() {
                    @Override public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
                });
                JSONArray arr = new JSONArray();
                for (File f : fs) {
                    if (!f.isFile() || f.getName().startsWith(".")) continue;
                    JSONObject e = new JSONObject();
                    e.put("name", f.getName());
                    e.put("time", f.lastModified());
                    e.put("size", f.length());
                    arr.put(e);
                }
                o.put(kind, arr);
            }
            return o.toString();
        } catch (Exception e) {
            return "{\"photos\":[],\"clips\":[],\"music\":[]}";
        }
    }

    static boolean delete(Context ctx, String kind, String name) {
        File f = file(ctx, kind, name);
        return f != null && f.delete();
    }

    static String savePhoto(Context ctx, byte[] jpeg) {
        String name = "snap-" + System.currentTimeMillis() + ".jpg";
        File f = new File(dir(ctx, "photos"), name);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(jpeg);
            return name;
        } catch (Exception e) {
            f.delete();
            return "";
        }
    }

    static String kindFor(String name, String mime) {
        String n = name.toLowerCase(Locale.ROOT);
        if (mime == null) mime = "";
        if (mime.startsWith("image/") || n.matches(".*\\.(jpe?g|png|webp|gif)$")) return "photos";
        if (mime.startsWith("video/") || n.matches(".*\\.(mp4|m4v|webm|mov|3gp)$")) return "clips";
        if (mime.startsWith("audio/") || n.matches(".*\\.(mp3|m4a|aac|ogg|wav|flac)$")) return "music";
        // a .txt next to a video holds that clip's steps
        if (n.endsWith(".txt")) return "clips";
        return null;
    }

    static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".3gp")) return "video/3gpp";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a") || n.endsWith(".aac")) return "audio/mp4";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "text/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    /** Result of copying from a USB folder. */
    static final class CopyResult {
        int photos, clips, music, skipped;
        String toJson() {
            return "{\"photos\":" + photos + ",\"clips\":" + clips + ",\"music\":" + music + ",\"skipped\":" + skipped + "}";
        }
    }

    /** Copies every photo, video and song in the chosen folder (and one level of folders inside it). */
    static CopyResult copyFromTree(Context ctx, Uri tree) {
        CopyResult r = new CopyResult();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        walk(ctx, tree, rootId, 0, r);
        return r;
    }

    private static void walk(Context ctx, Uri tree, String docId, int depth, CopyResult r) {
        ContentResolver cr = ctx.getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        List<String[]> rows = new ArrayList<>();
        try (Cursor c = cr.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                rows.add(new String[]{c.getString(0), c.getString(1), c.getString(2), c.isNull(3) ? "-1" : String.valueOf(c.getLong(3))});
            }
        } catch (Exception e) {
            return;
        }
        for (String[] row : rows) {
            String id = row[0], name = cleanName(row[1]), mime = row[2];
            long size = Long.parseLong(row[3]);
            if (name.isEmpty() || name.startsWith(".")) continue;
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                if (depth < 2) walk(ctx, tree, id, depth + 1, r);
                continue;
            }
            String kind = kindFor(name, mime);
            if (kind == null) { r.skipped++; continue; }
            File dest = new File(dir(ctx, kind), name);
            if (dest.exists() && (size < 0 || dest.length() == size)) { r.skipped++; continue; }
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            File tmp = new File(dir(ctx, kind), "." + name + ".part");
            try (InputStream in = cr.openInputStream(doc); OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) { r.skipped++; continue; }
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception e) {
                tmp.delete();
                r.skipped++;
                continue;
            }
            if (dest.exists()) dest.delete();
            if (tmp.renameTo(dest)) {
                if (kind.equals("photos")) r.photos++;
                else if (kind.equals("clips")) { if (!name.toLowerCase(Locale.ROOT).endsWith(".txt")) r.clips++; }
                else r.music++;
            } else {
                tmp.delete();
                r.skipped++;
            }
        }
    }
}
