package com.muse.player;

import android.content.Context;
import android.net.Uri;
import android.os.Build;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 万能导入调度：认出文件类型，分发给对应的解包器，最后兜底深度扫描。
 *   PKGM          -> Wallpaper Engine .mpkg 专用解析
 *   zip/tar/ar/cpio/jar、gz/bz2/xz 及其包裹的 tar、单文件压缩 -> Commons Compress
 *   7z            -> SevenZFile（隔离捕获，个别系统类库缺失时自动跳过）
 *   本身就是视频/图片 -> 直接存
 *   都不认识      -> DeepScan 深度扫描雕刻
 */
public class PkgImport {

    public static class Result {
        public final List<File> media = new ArrayList<>();
        public int totalEntries;
        public String how = "";
    }

    public static Result importAny(Context ctx, Uri uri, String sourceName) throws Exception {
        return importAny(ctx, uri, sourceName, false);
    }

    /** forceScan=true 时跳过一切包解析，直接深度扫描（主页「深度扫描」入口） */
    public static Result importAny(Context ctx, Uri uri, String sourceName, boolean forceScan)
            throws Exception {
        File tmp = new File(ctx.getCacheDir(), "any_" + System.currentTimeMillis() + ".bin");
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
            // 1) Wallpaper .mpkg
            if (!forceScan && isMpkg(tmp)) {
                MpkgImport.Result r = MpkgImport.importFromFile(ctx, tmp);
                Result out = new Result();
                out.media.addAll(r.media);
                out.totalEntries = r.totalEntries;
                out.how = "Wallpaper 壁纸包";
                return out;
            }

            Result out = new Result();

            // 2) 压缩流（gz/bz2/xz…）：里面可能裹着 tar，也可能是单个文件
            if (!forceScan && out.media.isEmpty()) {
                try {
                    extractCompressed(ctx, tmp, sourceName, out);
                    if (!out.media.isEmpty() && out.how.isEmpty()) out.how = "压缩包";
                } catch (Throwable ignored) { }
            }

            // 3) 未压缩归档（zip/tar/apk/jar…）
            if (!forceScan && out.media.isEmpty()) {
                try {
                    ArchiveInputStream ais = new ArchiveStreamFactory()
                            .createArchiveInputStream(new BufferedInputStream(new FileInputStream(tmp)));
                    extractEntries(ctx, ais, out);
                    if (!out.media.isEmpty()) out.how = "压缩包";
                } catch (Throwable ignored) { }
            }

            // 4) 7z（隔离尝试）
            if (!forceScan && out.media.isEmpty()) {
                try {
                    if (extractSevenZ(ctx, tmp, out) && !out.media.isEmpty()) out.how = "7z 压缩包";
                } catch (Throwable ignored) { }
            }

            // 5) 本身就是媒体文件
            if (!forceScan && out.media.isEmpty() && sourceName != null && MpkgImport.isMedia(sourceName)) {
                File saved = MediaSink.save(ctx, new FileInputStream(tmp), safeName(sourceName));
                if (saved != null) {
                    out.media.add(saved);
                    out.how = "媒体文件";
                }
            }

            // 6) 深度扫描兜底
            if (out.media.isEmpty()) {
                RandomAccessFile raf = new RandomAccessFile(tmp, "r");
                try {
                    List<DeepScan.Carved> carved = DeepScan.scan(raf);
                    int n = 0;
                    for (DeepScan.Carved c : carved) {
                        if (n >= 5) break;
                        String base = sourceName == null ? "扫描结果" : stripExt(safeName(sourceName));
                        String name = base + "_" + (n + 1) + c.ext;
                        File saved = MediaSink.save(ctx,
                                new MpkgImport.RangeInputStream(raf, c.off, c.len), name);
                        if (saved != null) {
                            out.media.add(saved);
                            n++;
                        }
                    }
                } finally {
                    try { raf.close(); } catch (Exception ignored) { }
                }
                if (!out.media.isEmpty()) out.how = "深度扫描";
            }
            return out;
        } finally {
            tmp.delete();
        }
    }

    private static boolean isMpkg(File f) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            if (raf.length() < 12) return false;
            raf.seek(4);
            byte[] b = new byte[4];
            raf.readFully(b);
            return b[0] == 'P' && b[1] == 'K' && b[2] == 'G' && b[3] == 'M';
        } finally {
            raf.close();
        }
    }

    /** gz/bz2/xz 等压缩流：先按归档解；不是归档就当单个被压缩的文件 */
    private static void extractCompressed(Context ctx, File f, String sourceName, Result out)
            throws Exception {
        // 先验证确实是压缩流（工厂认不出会抛异常，交给上层换路）
        InputStream cin = new CompressorStreamFactory()
                .createCompressorInputStream(new BufferedInputStream(new FileInputStream(f)));
        boolean isArchive = false;
        ArchiveInputStream ais = null;
        try {
            ais = new ArchiveStreamFactory().createArchiveInputStream(new BufferedInputStream(cin));
            isArchive = true;
        } catch (Throwable ignored) { }
        if (isArchive) {
            extractEntries(ctx, ais, out);
            return;
        }
        // 单文件：重新开流解压
        InputStream single = new CompressorStreamFactory()
                .createCompressorInputStream(new BufferedInputStream(new FileInputStream(f)));
        String name = stripCompressionSuffix(sourceName);
        if (name == null || !MpkgImport.isMedia(name)) {
            // 名字猜不出：先落到缓存文件，按文件头再判断
            File stage = new File(ctx.getCacheDir(), "single_" + System.currentTimeMillis());
            FileOutputStream so = new FileOutputStream(stage);
            try {
                MediaSink.pump(single, so);
            } finally {
                try { so.close(); } catch (Exception ignored) { }
            }
            try {
                String ext = sniffMediaExt(stage);
                if (ext != null) {
                    File saved = MediaSink.save(ctx, new FileInputStream(stage),
                            "解压文件" + ext);
                    if (saved != null) out.media.add(saved);
                }
            } finally {
                stage.delete();
            }
        } else {
            File saved = MediaSink.save(ctx, single, safeName(name));
            if (saved != null) out.media.add(saved);
        }
    }

    private static void extractEntries(Context ctx, ArchiveInputStream ais, Result out)
            throws Exception {
        try {
            ArchiveEntry e;
            while ((e = ais.getNextEntry()) != null) {
                out.totalEntries++;
                if (e.isDirectory()) continue;
                String name = safeName(e.getName());
                if (!MpkgImport.isMedia(name)) continue;
                File saved = MediaSink.save(ctx, ais, name);
                if (saved != null) out.media.add(saved);
            }
        } finally {
            try { ais.close(); } catch (Exception ignored) { }
        }
    }

    private static boolean extractSevenZ(Context ctx, File f, Result out) throws Exception {
        if (Build.VERSION.SDK_INT < 26) return false;
        FileInputStream fis = new FileInputStream(f);
        SevenZFile z = null;
        try {
            z = new SevenZFile(fis.getChannel());
            SevenZArchiveEntry e;
            byte[] buf = new byte[256 * 1024];
            while ((e = z.getNextEntry()) != null) {
                out.totalEntries++;
                if (e.isDirectory()) continue;
                String name = safeName(e.getName());
                long left = e.getSize();
                if (!MpkgImport.isMedia(name)) {
                    while (left > 0) {
                        int got = z.read(buf, 0, (int) Math.min(buf.length, left));
                        if (got < 0) break;
                        left -= got;
                    }
                    continue;
                }
                // 先落缓存再存媒体库（7z 读不是标准 InputStream）
                File stage = new File(ctx.getCacheDir(), "7z_" + System.currentTimeMillis());
                FileOutputStream so = new FileOutputStream(stage);
                try {
                    while (left > 0) {
                        int got = z.read(buf, 0, (int) Math.min(buf.length, left));
                        if (got < 0) throw new Exception("7z 数据损坏");
                        so.write(buf, 0, got);
                        left -= got;
                    }
                } finally {
                    try { so.close(); } catch (Exception ignored) { }
                }
                try {
                    File saved = MediaSink.save(ctx, new FileInputStream(stage), name);
                    if (saved != null) out.media.add(saved);
                } finally {
                    stage.delete();
                }
            }
            return true;
        } finally {
            if (z != null) try { z.close(); } catch (Exception ignored) { }
            try { fis.close(); } catch (Exception ignored) { }
        }
    }

    private static String stripCompressionSuffix(String name) {
        if (name == null) return null;
        String n = name;
        String lower = n.toLowerCase();
        for (String suf : new String[]{".gz", ".bz2", ".xz", ".lzma", ".zst"}) {
            if (lower.endsWith(suf)) {
                n = n.substring(0, n.length() - suf.length());
                break;
            }
        }
        if (n.toLowerCase().endsWith(".tar")) n = n.substring(0, n.length() - 4);
        return n;
    }

    private static String sniffMediaExt(File f) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            byte[] h = new byte[12];
            if (raf.length() < 12) return null;
            raf.readFully(h);
            if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return ".mp4";
            if ((h[0] & 0xff) == 0x1A && (h[1] & 0xff) == 0x45
                    && (h[2] & 0xff) == 0xDF && (h[3] & 0xff) == 0xA3) return ".mkv";
            if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F') return ".avi";
            if ((h[0] & 0xff) == 0xFF && (h[1] & 0xff) == 0xD8) return ".jpg";
            if ((h[0] & 0xff) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return ".png";
            return null;
        } finally {
            raf.close();
        }
    }

    private static String safeName(String name) {
        String base = name.replace('\\', '/');
        int i = base.lastIndexOf('/');
        if (i >= 0) base = base.substring(i + 1);
        base = base.trim();
        return base.isEmpty() ? "file" : base;
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }
}
