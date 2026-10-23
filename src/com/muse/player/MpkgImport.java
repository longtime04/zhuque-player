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
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析 Wallpaper Engine 导出的 .mpkg 包，把里面的视频/图片摘出来存进系统媒体库。
 *
 * 格式（已按 AvelcFox/unmpkg 开源解析器逐行核对，非 zip、无压缩无加密）：
 *   uint32LE version_len, version(version_len 字节 UTF-8, 如 "PKGM0019"),
 *   uint32LE file_count,
 *   每个文件：uint32LE name_len, name(UTF-8), uint32LE location(保留,忽略),
 *             uint32LE data_len
 *   所有记录之后，文件数据按记录顺序紧密拼接。
 *
 * 输出走 MediaStore（分区存储下不能直写公共目录）：
 *   视频/图片都进 Pictures/朱雀导入，相册与文件管理都能看到。
 */
public class MpkgImport {

    static final String REL_PATH = "Pictures/朱雀导入";

    private static final String[] VIDEO_EXT = {
            ".mp4", ".webm", ".mov", ".mkv", ".avi", ".m4v", ".ts"};
    private static final String[] IMAGE_EXT = {
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"};

    /** 解出的媒体文件（已落盘，File 指向媒体库中的真实路径） */
    public static class Result {
        public final List<File> media = new ArrayList<>();
        public int totalEntries;
        public String version = "";
    }

    /** mpkg 索引（纯解析，可在电脑上单测） */
    static class Entry {
        String name;
        long len;
    }

    static class Index {
        String version = "";
        final List<Entry> entries = new ArrayList<>();
        long dataStart;
    }

    private static int readIntLE(RandomAccessFile raf) throws Exception {
        int b0 = raf.read();
        int b1 = raf.read();
        int b2 = raf.read();
        int b3 = raf.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new Exception("mpkg 记录头截断");
        return (b0 & 0xff) | ((b1 & 0xff) << 8) | ((b2 & 0xff) << 16) | ((b3 & 0xff) << 24);
    }

    private static String readUtf8(RandomAccessFile raf, int len) throws Exception {
        if (len < 0 || len > 4096) throw new Exception("mpkg 字段长度异常");
        byte[] buf = new byte[len];
        raf.readFully(buf);
        return new String(buf, "UTF-8");
    }

    /** 只读索引头：版本、文件清单、数据区起点 */
    static Index readIndex(RandomAccessFile raf) throws Exception {
        if (raf.length() < 8) throw new Exception("不是合法的壁纸包");
        int versionLen = readIntLE(raf);
        if (versionLen < 0 || versionLen > 64) throw new Exception("不是合法的壁纸包");
        Index idx = new Index();
        idx.version = readUtf8(raf, versionLen);
        if (!idx.version.startsWith("PKGM")) throw new Exception("不是合法的壁纸包");
        int count = readIntLE(raf);
        if (count < 0 || count > 100000) throw new Exception("不是合法的壁纸包");
        for (int i = 0; i < count; i++) {
            Entry e = new Entry();
            e.name = readUtf8(raf, readIntLE(raf));
            readIntLE(raf); // location 保留字段，解包忽略
            int len = readIntLE(raf);
            if (len < 0) throw new Exception("壁纸包数据损坏");
            e.len = len & 0xffffffffL;
            idx.entries.add(e);
        }
        idx.dataStart = raf.getFilePointer();
        return idx;
    }

    static boolean isVideo(String name) {
        String lower = name.toLowerCase();
        for (String e : VIDEO_EXT) if (lower.endsWith(e)) return true;
        return false;
    }

    static boolean isMedia(String name) {
        if (isVideo(name)) return true;
        String lower = name.toLowerCase();
        for (String e : IMAGE_EXT) if (lower.endsWith(e)) return true;
        return false;
    }

    private static String mimeOf(String name) {
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

    private static String safeName(String name) {
        // 只取最后一段，防 ../ 穿透；mpkg 里一般本就是纯文件名
        String base = name.replace('\\', '/');
        int i = base.lastIndexOf('/');
        if (i >= 0) base = base.substring(i + 1);
        base = base.trim();
        return base.isEmpty() ? "wallpaper" : base;
    }

    /** 从 Uri 导入（先拷 cache 临时文件） */
    public static Result importFrom(Context ctx, Uri uri) throws Exception {
        File tmp = new File(ctx.getCacheDir(), "import_" + System.currentTimeMillis() + ".mpkg");
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("打不开这个文件");
            fos = new FileOutputStream(tmp);
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } finally {
            if (fos != null) try { fos.close(); } catch (Exception ignored) { }
            if (in != null) try { in.close(); } catch (Exception ignored) { }
        }
        try {
            return importFromFile(ctx, tmp);
        } finally {
            tmp.delete();
        }
    }

    /** 从已落盘的 .mpkg 文件解出媒体存入媒体库 */
    public static Result importFromFile(Context ctx, File pkg) throws Exception {
        Result r = new Result();
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(pkg, "r");
            Index idx = readIndex(raf);
            r.version = idx.version;
            r.totalEntries = idx.entries.size();

            long dataOff = idx.dataStart;
            for (Entry e : idx.entries) {
                if (!isMedia(e.name)) {
                    dataOff += e.len;
                    continue;
                }
                String display = safeName(e.name);
                File saved = MediaSink.save(ctx, new RangeInputStream(raf, dataOff, e.len), display);
                if (saved != null) r.media.add(saved);
                dataOff += e.len;
            }
            return r;
        } finally {
            if (raf != null) try { raf.close(); } catch (Exception ignored) { }
        }
    }

    /** RandomAccessFile 的一段区间当作 InputStream（与 raf 共用文件指针，仅单线程顺序读） */
    static class RangeInputStream extends InputStream {
        private final RandomAccessFile raf;
        private long left;

        RangeInputStream(RandomAccessFile raf, long off, long len) throws Exception {
            this.raf = raf;
            this.left = len;
            raf.seek(off);
        }

        @Override public int read() throws java.io.IOException {
            if (left <= 0) return -1;
            int b = raf.read();
            if (b >= 0) left--;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (left <= 0) return -1;
            int want = (int) Math.min(len, left);
            int got = raf.read(b, off, want);
            if (got > 0) left -= got;
            return got;
        }
    }
}
