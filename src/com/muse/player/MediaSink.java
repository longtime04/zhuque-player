package com.muse.player;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把一段媒体流存进系统媒体库的 Pictures/朱雀导入，
 * 相册和文件管理都能看到（分区存储下必须走 MediaStore）。
 * API 29 以下兜底写应用专属目录。
 */
public class MediaSink {

    static final String REL_PATH = "Pictures/朱雀导入";

    public static File save(Context ctx, InputStream in, String displayName) throws Exception {
        boolean video = MpkgImport.isVideo(displayName);
        ContentResolver cr = ctx.getContentResolver();
        if (Build.VERSION.SDK_INT < 29) {
            File dir = new File(ctx.getExternalFilesDir(null), "朱雀导入");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, displayName);
            FileOutputStream out = new FileOutputStream(f);
            try {
                pump(in, out);
            } finally {
                try { out.close(); } catch (Exception ignored) { }
            }
            return f;
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(displayName));
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, REL_PATH);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri coll = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri u = cr.insert(coll, v);
        if (u == null) throw new Exception("存不进媒体库（系统拒绝写入）");
        OutputStream os = null;
        try {
            os = cr.openOutputStream(u);
            if (os == null) throw new Exception("存不进媒体库（打不开输出流）");
            pump(in, os);
        } catch (Exception e) {
            try { cr.delete(u, null, null); } catch (Exception ignored) { }
            throw e;
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) { }
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(u, done, null, null);
        return resolveFile(cr, u, displayName);
    }

    static void pump(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[256 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    static String mimeOf(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".mp4") || n.endsWith(".m4v")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mov")) return "video/quicktime";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".avi")) return "video/x-msvideo";
        if (n.endsWith(".ts")) return "video/mp2ts";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp")) return "image/bmp";
        return "application/octet-stream";
    }

    /** 由媒体库 Uri 反查真实路径（自家插入的条目可查 DATA 列）；查不到按标准位置拼 */
    private static File resolveFile(ContentResolver cr, Uri u, String display) {
        Cursor c = null;
        try {
            c = cr.query(u, new String[]{MediaStore.MediaColumns.DATA}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String p = c.getString(0);
                if (p != null && !p.isEmpty()) return new File(p);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return new File(Environment.getExternalStorageDirectory(), REL_PATH + "/" + display);
    }
}
