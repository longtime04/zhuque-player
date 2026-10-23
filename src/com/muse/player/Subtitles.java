package com.muse.player;

import android.content.Context;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 字幕引擎：解析 SRT / VTT / ASS，同名字幕自动查找，
 * 在线翻译（Google 免费翻译通道，无需 key）+ 本地缓存（翻过的字幕秒开）。
 */
public class Subtitles {

    public static class Cue {
        public long start, end;
        public String text;
        public String zh;
    }

    public interface Progress { void onProgress(int done, int total); }

    // ---------------- 解析 ----------------

    public static List<Cue> parse(File f) throws Exception {
        byte[] data = readAll(f);
        String content = decode(data);
        String lower = f.getName().toLowerCase();
        List<Cue> out;
        if (lower.endsWith(".ass") || lower.endsWith(".ssa") || content.contains("[Events]")) {
            out = parseAss(content);
        } else {
            out = parseSrtLike(content);
        }
        out.sort((a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    static byte[] readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    /** UTF-8 严格解码，失败回退 GBK（国内字幕常见） */
    static String decode(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            return new String(data, Charset.forName("GBK"));
        }
    }

    private static List<Cue> parseSrtLike(String content) {
        List<Cue> out = new ArrayList<>();
        String[] blocks = content.replace("\r\n", "\n").replace("\r", "\n").split("\n\n+");
        for (String block : blocks) {
            String[] lines = block.split("\n");
            int tsIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("-->")) { tsIdx = i; break; }
            }
            if (tsIdx < 0) continue;
            String[] parts = lines[tsIdx].split("-->");
            if (parts.length < 2) continue;
            long start = parseTs(parts[0].trim());
            long end = parseTs(parts[1].trim().split("\\s+")[0]);
            if (end < start) continue;
            StringBuilder sb = new StringBuilder();
            for (int i = tsIdx + 1; i < lines.length; i++) {
                if (lines[i].trim().startsWith("NOTE")) { sb.setLength(0); break; }
                if (sb.length() > 0) sb.append("\n");
                sb.append(lines[i].trim());
            }
            String text = clean(sb.toString());
            if (text.isEmpty()) continue;
            Cue c = new Cue();
            c.start = start; c.end = end; c.text = text;
            out.add(c);
        }
        return out;
    }

    private static List<Cue> parseAss(String content) {
        List<Cue> out = new ArrayList<>();
        String[] lines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        int iStart = -1, iEnd = -1, iText = -1, nFields = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("Format:")) {
                String[] f = t.substring(7).split(",");
                nFields = f.length;
                for (int i = 0; i < f.length; i++) {
                    String name = f[i].trim();
                    if (name.equalsIgnoreCase("Start")) iStart = i;
                    else if (name.equalsIgnoreCase("End")) iEnd = i;
                    else if (name.equalsIgnoreCase("Text")) iText = i;
                }
            } else if (t.startsWith("Dialogue:") && iStart >= 0 && iText >= 0) {
                String body = t.substring(9).trim();
                String[] parts = body.split(",", nFields);
                if (parts.length < nFields) continue;
                Cue c = new Cue();
                c.start = parseTs(parts[iStart].trim());
                c.end = parseTs(parts[iEnd].trim());
                c.text = clean(parts[iText]);
                if (!c.text.isEmpty() && c.end >= c.start) out.add(c);
            }
        }
        return out;
    }

    /** 00:00:01,000 / 00:00:01.000 / 0:00:01.00 → 毫秒 */
    static long parseTs(String s) {
        try {
            String[] p = s.replace(',', '.').split(":");
            double sec = Double.parseDouble(p[p.length - 1]);
            long ms = Math.round(sec * 1000);
            if (p.length >= 2) ms += Long.parseLong(p[p.length - 2].trim()) * 60_000L;
            if (p.length >= 3) ms += Long.parseLong(p[p.length - 3].trim()) * 3_600_000L;
            return ms;
        } catch (Exception e) {
            return 0;
        }
    }

    static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\{[^}]*\\}", "");
        s = s.replaceAll("<[^>]+>", "");
        s = s.replace("\\N", "\n").replace("\\n", "\n");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&");
        return s.trim();
    }

    // ---------------- 同名字幕查找 ----------------

    private static final String[] SUB_EXTS = {".srt", ".vtt", ".ass", ".ssa"};

    public static boolean isSubtitleFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        for (String e : SUB_EXTS) if (n.endsWith(e)) return true;
        return false;
    }

    public static File findCompanion(String videoPath) {
        try {
            File v = new File(videoPath);
            File dir = v.getParentFile();
            if (dir == null || !dir.isDirectory()) return null;
            String base = v.getName();
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            File[] files = dir.listFiles();
            if (files == null) return null;
            File loose = null;
            for (File f : files) {
                String n = f.getName();
                if (!isSubtitleFile(n)) continue;
                String nb = n.substring(0, n.lastIndexOf('.'));
                if (nb.equalsIgnoreCase(base)) return f;
                if (nb.toLowerCase().startsWith(base.toLowerCase())) return f;
                if (loose == null) loose = f; // 同目录只有一个字幕时也用它
            }
            // 同目录有多个不相关字幕时不乱配：只有一个候选才兜底
            int count = 0;
            for (File f : files) if (isSubtitleFile(f.getName())) count++;
            return count == 1 ? loose : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isMostlyChinese(List<Cue> cues) {
        int han = 0, total = 0;
        for (int i = 0; i < cues.size() && i < 40; i++) {
            String t = cues.get(i).text;
            for (int j = 0; j < t.length(); j++) {
                char ch = t.charAt(j);
                total++;
                if (ch >= 0x4E00 && ch <= 0x9FFF) han++;
            }
        }
        return total > 0 && han * 2 > total;
    }

    // ---------------- 翻译缓存 ----------------

    private static File cacheFile(Context ctx, File subFile) {
        try {
            String key = subFile.getAbsolutePath() + "|" + subFile.length() + "|" + subFile.lastModified();
            byte[] d = MessageDigest.getInstance("MD5").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            File dir = new File(ctx.getCacheDir(), "zhsub");
            dir.mkdirs();
            return new File(dir, sb + ".txt");
        } catch (Exception e) {
            return null;
        }
    }

    /** 缓存命中则把 zh 填好，返回 true */
    public static boolean loadZh(Context ctx, File subFile, List<Cue> cues) {
        File cf = cacheFile(ctx, subFile);
        if (cf == null || !cf.exists()) return false;
        try {
            String content = decode(readAll(cf)).replace("\r\n", "\n");
            String[] lines = content.split("\n", -1);
            if (lines.length < cues.size()) return false;
            for (int i = 0; i < cues.size(); i++) cues.get(i).zh = lines[i];
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void saveZh(Context ctx, File subFile, List<Cue> cues) {
        File cf = cacheFile(ctx, subFile);
        if (cf == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (Cue c : cues) sb.append(c.zh == null ? "" : c.zh.replace("\n", " ")).append("\n");
            FileOutputStream fos = new FileOutputStream(cf);
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignored) { }
    }

    // ---------------- 在线翻译 ----------------

    /**
     * 在线批量翻译：多条拼一批请求，条数对不上就逐条回退。
     * 全部翻完写缓存。任一批彻底失败会抛异常（已翻好的 zh 保留在 cues 中）。
     */
    public static void translateAll(Context ctx, File subFile, List<Cue> cues, Progress cb)
            throws Exception {
        final int batchSize = 12;
        final String sep = "\n@@@\n";
        int done = 0;
        Exception lastError = null;
        for (int i = 0; i < cues.size(); i += batchSize) {
            int end = Math.min(cues.size(), i + batchSize);
            List<Cue> batch = cues.subList(i, end);
            boolean need = false;
            for (Cue c : batch) if (c.zh == null || c.zh.isEmpty()) { need = true; break; }
            if (need) {
                try {
                    StringBuilder joined = new StringBuilder();
                    for (int j = 0; j < batch.size(); j++) {
                        if (j > 0) joined.append(sep);
                        joined.append(batch.get(j).text.replace("\n", " "));
                    }
                    String result = gtx(joined.toString());
                    String[] parts = result.split("@@@", -1);
                    if (parts.length == batch.size()) {
                        for (int j = 0; j < batch.size(); j++) {
                            batch.get(j).zh = parts[j].trim();
                        }
                    } else {
                        for (Cue c : batch) {
                            if (c.zh == null || c.zh.isEmpty()) {
                                c.zh = gtx(c.text.replace("\n", " ")).trim();
                            }
                        }
                    }
                } catch (Exception e) {
                    lastError = e;
                    // 整批失败再逐条挣扎一次
                    for (Cue c : batch) {
                        if (c.zh == null || c.zh.isEmpty()) {
                            try { c.zh = gtx(c.text.replace("\n", " ")).trim(); }
                            catch (Exception e2) { lastError = e2; }
                        }
                    }
                }
            }
            done = end;
            if (cb != null) cb.onProgress(done, cues.size());
        }
        int filled = 0;
        for (Cue c : cues) if (c.zh != null && !c.zh.isEmpty()) filled++;
        if (filled == 0 && lastError != null) throw lastError;
        if (filled > 0) saveZh(ctx, subFile, cues);
        if (filled < cues.size() && lastError != null) throw lastError;
    }

    /** Google 免费翻译通道（client=gtx，无需 key） */
    static String gtx(String text) throws Exception {
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx"
                + "&sl=auto&tl=zh-CN&dt=t&q=" + URLEncoder.encode(text, "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
        try {
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            JSONArray root = new JSONArray(body);
            JSONArray segs = root.getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < segs.length(); i++) {
                JSONArray seg = segs.getJSONArray(i);
                if (seg.length() > 0 && !seg.isNull(0)) sb.append(seg.getString(0));
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
