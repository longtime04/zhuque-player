package com.muse.player;

import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 深度扫描（文件雕刻）：在一个不认识的包/文件里搜媒体文件的特征头，
 * 把藏在里面的视频/图片整段切出来。
 *
 * 支持：
 *   MP4/MOV —— ftyp 起头，按 box 链精确算长度；
 *   AVI     —— RIFF 头，长度字段精确；
 *   WebM/MKV— EBML 头，长度未知，雕到文件尾（播放器容忍尾部多余数据）；
 *   FLV     —— FLV 头，雕到文件尾；
 *   PNG/JPEG/GIF —— 按结束标记切。
 * 纯 java.io，可在电脑上单测。
 */
public class DeepScan {

    public static class Carved {
        public final long off;
        public final long len;
        public final String ext;   // .mp4 / .avi / .mkv / .flv / .png / .jpg / .gif
        public final boolean video;

        Carved(long off, long len, String ext, boolean video) {
            this.off = off;
            this.len = len;
            this.ext = ext;
            this.video = video;
        }
    }

    private static final long MIN_VIDEO = 32 * 1024;
    private static final long MIN_IMAGE = 4 * 1024;

    public static List<Carved> scan(RandomAccessFile raf) throws Exception {
        long fileLen = raf.length();
        List<Carved> found = new ArrayList<>();
        if (fileLen < MIN_IMAGE) return found;

        // 一次性流式扫特征（分块 + 重叠 16 字节，避免特征跨块丢失）
        byte[] chunk = new byte[1024 * 1024 + 16];
        long base = 0;
        int carry = 0;
        List<Long> ftypHits = new ArrayList<>();
        List<Long> riffHits = new ArrayList<>();
        List<Long> ebmlHits = new ArrayList<>();
        List<Long> flvHits = new ArrayList<>();
        List<Long> pngHits = new ArrayList<>();
        List<Long> jpgHits = new ArrayList<>();
        List<Long> gifHits = new ArrayList<>();
        while (base < fileLen) {
            int want = (int) Math.min(1024 * 1024, fileLen - base);
            raf.seek(base);
            raf.readFully(chunk, carry, want);
            int total = carry + want;
            scanChunk(chunk, total, base - carry, ftypHits, riffHits, ebmlHits,
                    flvHits, pngHits, jpgHits, gifHits);
            carry = Math.min(16, total);
            System.arraycopy(chunk, total - carry, chunk, 0, carry);
            base += want;
        }

        // MP4：ftyp 命中点往前 4 字节是第一个 box 的起点
        for (long h : ftypHits) {
            long start = h - 4;
            if (start < 0) continue;
            long len = mp4Length(raf, start, fileLen);
            if (len >= MIN_VIDEO) found.add(new Carved(start, len, ".mp4", true));
        }
        // AVI
        for (long h : riffHits) {
            raf.seek(h + 4);
            long riffSize = readU32le(raf);
            byte[] t = new byte[4];
            raf.seek(h + 8);
            raf.readFully(t);
            if (t[0] == 'A' && t[1] == 'V' && t[2] == 'I' && t[3] == ' ') {
                long len = riffSize + 8;
                if (len >= MIN_VIDEO && h + len <= fileLen) {
                    found.add(new Carved(h, len, ".avi", true));
                }
            }
        }
        // WebM/MKV
        for (long h : ebmlHits) {
            long len = fileLen - h;
            if (len >= MIN_VIDEO) found.add(new Carved(h, len, ".mkv", true));
        }
        // FLV
        for (long h : flvHits) {
            long len = fileLen - h;
            if (len >= MIN_VIDEO) found.add(new Carved(h, len, ".flv", true));
        }
        // PNG
        for (long h : pngHits) {
            long end = findAfter(raf, h, new byte[]{'I', 'E', 'N', 'D',
                    (byte) 0xAE, 0x42, 0x60, (byte) 0x82}, fileLen);
            if (end > 0 && end - h >= MIN_IMAGE) found.add(new Carved(h, end - h, ".png", false));
        }
        // JPEG
        for (long h : jpgHits) {
            long end = findAfter(raf, h + 3, new byte[]{(byte) 0xFF, (byte) 0xD9}, fileLen);
            if (end > 0 && end - h >= MIN_IMAGE) found.add(new Carved(h, end - h, ".jpg", false));
        }
        // GIF
        for (long h : gifHits) {
            long end = findAfter(raf, h + 6, new byte[]{0x3B}, fileLen);
            if (end > 0 && end - h >= MIN_IMAGE) found.add(new Carved(h, end - h, ".gif", false));
        }

        // 去重叠：按长度降序，保留大的，丢与已保留者重叠的
        found.sort((a, b) -> Long.compare(b.len, a.len));
        List<Carved> out = new ArrayList<>();
        for (Carved c : found) {
            boolean overlap = false;
            for (Carved k : out) {
                if (c.off < k.off + k.len && k.off < c.off + c.len) {
                    overlap = true;
                    break;
                }
            }
            if (!overlap) out.add(c);
        }
        // 视频优先、大的在前
        out.sort((a, b) -> a.video != b.video ? (a.video ? -1 : 1) : Long.compare(b.len, a.len));
        return out;
    }

    private static void scanChunk(byte[] buf, int total, long baseOff,
                                  List<Long> ftyp, List<Long> riff, List<Long> ebml,
                                  List<Long> flv, List<Long> png, List<Long> jpg,
                                  List<Long> gif) {
        for (int i = 0; i + 4 <= total; i++) {
            int b0 = buf[i] & 0xff, b1 = buf[i + 1] & 0xff, b2 = buf[i + 2] & 0xff, b3 = buf[i + 3] & 0xff;
            if (b0 == 'f' && b1 == 't' && b2 == 'y' && b3 == 'p') ftyp.add(baseOff + i);
            else if (b0 == 'R' && b1 == 'I' && b2 == 'F' && b3 == 'F') riff.add(baseOff + i);
            else if (b0 == 0x1A && b1 == 0x45 && b2 == 0xDF && b3 == 0xA3) ebml.add(baseOff + i);
            else if (b0 == 'F' && b1 == 'L' && b2 == 'V' && b3 == 0x01) flv.add(baseOff + i);
            else if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) jpg.add(baseOff + i);
            else if (b0 == 'G' && b1 == 'I' && b2 == 'F' && b3 == '8') gif.add(baseOff + i);
            else if (b0 == 0x89 && b1 == 'P' && b2 == 'N' && b3 == 'G') png.add(baseOff + i);
        }
    }

    /**
     * 从 start 沿 MP4 box 链走到底（moov 可能在 mdat 前，也可能在后，
     * 不能提前收工），返回最后一个合法 box 的结束位置减去 start；
     * 第一个 box 都不合法返回 -1。
     */
    private static long mp4Length(RandomAccessFile raf, long start, long fileLen) throws Exception {
        long pos = start;
        long lastGood = -1;
        byte[] hdr = new byte[16];
        int boxes = 0;
        while (pos + 8 <= fileLen && boxes < 10000) {
            raf.seek(pos);
            raf.readFully(hdr, 0, 8);
            long size = ((hdr[0] & 0xffL) << 24) | ((hdr[1] & 0xffL) << 16)
                    | ((hdr[2] & 0xffL) << 8) | (hdr[3] & 0xffL);
            String type = new String(hdr, 4, 4, "ISO-8859-1");
            long headerLen = 8;
            if (size == 1) {
                raf.readFully(hdr, 8, 8);
                size = 0;
                for (int i = 8; i < 16; i++) size = (size << 8) | (hdr[i] & 0xffL);
                headerLen = 16;
            } else if (size == 0) {
                // 该 box 延伸到文件尾
                return fileLen - start;
            }
            if (size < headerLen || pos + size > fileLen) break;
            if (!type.matches("[a-zA-Z0-9 ]{4}")) break;
            pos += size;
            lastGood = pos;
            boxes++;
        }
        return lastGood > 0 ? lastGood - start : -1;
    }

    private static long readU32le(RandomAccessFile raf) throws Exception {
        int b0 = raf.read(), b1 = raf.read(), b2 = raf.read(), b3 = raf.read();
        if ((b0 | b1 | b2 | b3) < 0) return -1;
        return (b0 & 0xffL) | ((b1 & 0xffL) << 8) | ((b2 & 0xffL) << 16) | ((b3 & 0xffL) << 24);
    }

    /** 在 [from, fileLen) 里找 pattern，返回 pattern 结束位置；找不到 -1。朴素搜索，够用于图片尾标 */
    private static long findAfter(RandomAccessFile raf, long from, byte[] pat, long fileLen)
            throws Exception {
        byte[] buf = new byte[64 * 1024 + pat.length];
        long base = from;
        int carry = 0;
        while (base < fileLen) {
            int want = (int) Math.min(64 * 1024, fileLen - base);
            raf.seek(base);
            raf.readFully(buf, carry, want);
            int total = carry + want;
            for (int i = 0; i + pat.length <= total; i++) {
                boolean ok = true;
                for (int j = 0; j < pat.length; j++) {
                    if (buf[i + j] != pat[j]) {
                        ok = false;
                        break;
                    }
                }
                if (ok) return base - carry + i + pat.length;
            }
            carry = pat.length - 1;
            System.arraycopy(buf, total - carry, buf, 0, carry);
            base += want;
        }
        return -1;
    }
}
