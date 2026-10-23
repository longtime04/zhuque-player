package com.muse.player;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import com.muse.player.asr.AsrBridge;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;

/**
 * 语音生成字幕：抽音轨 → 本地 Whisper 识别（sherpa-onnx）→ 字幕条。
 * 模型约 160MB，第一次用时下载到 filesDir/asr-model，之后全程离线。
 */
public class AsrSubtitle {

    public interface Progress { void onProgress(String stage, int percent); }

    public static class Cancelled extends Exception { }

    private static final String[][] MODEL_FILES = {
        // 文件名, 期望最小字节数
        {"base-encoder.int8.onnx", "20000000"},
        {"base-decoder.int8.onnx", "100000000"},
        {"base-tokens.txt", "100000"},
    };
    private static final String[] MODEL_BASES = {
        "https://github.com/longtime04/filebox/releases/download/files/",
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-base/resolve/main/",
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main/",
    };
    private static final long MODEL_TOTAL = 29120534L + 130672026L + 816730L;
    private static final int TARGET_RATE = 16000;
    private static final int WINDOW_SEC = 600;

    // ---------------- 模型 ----------------

    public static File modelDir(Context ctx) {
        return new File(ctx.getFilesDir(), "asr-model");
    }

    public static boolean modelReady(Context ctx) {
        File dir = modelDir(ctx);
        for (String[] mf : MODEL_FILES) {
            File f = new File(dir, mf[0]);
            if (!f.isFile() || f.length() < Long.parseLong(mf[1])) return false;
        }
        return true;
    }

    public static void downloadModel(Context ctx, Progress cb, AtomicBoolean cancel) throws Exception {
        File dir = modelDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("建不了模型目录");
        long doneBytes = 0;
        for (String base : MODEL_BASES) {
            try {
                for (String[] mf : MODEL_FILES) {
                    File target = new File(dir, mf[0]);
                    if (target.isFile() && target.length() >= Long.parseLong(mf[1])) {
                        doneBytes += target.length();
                        continue;
                    }
                    File part = new File(dir, mf[0] + ".part");
                    downloadOne(base + mf[0], part, cb, cancel, doneBytes);
                    if (!part.renameTo(target)) throw new Exception("模型文件保存失败");
                    doneBytes += target.length();
                }
                return; // 全部完成
            } catch (Cancelled c) {
                throw c;
            } catch (Exception e) {
                if (base == MODEL_BASES[MODEL_BASES.length - 1]) throw e;
                // 换镜像重试
            }
        }
    }

    private static void downloadOne(String urlStr, File part, Progress cb,
                                    AtomicBoolean cancel, long doneBefore) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            String cur = urlStr;
            for (int hop = 0; hop < 6; hop++) {
                URL url = new URL(cur);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("User-Agent", "ZhuquePlayer");
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    conn = null;
                    if (loc == null) throw new Exception("下载跳转异常");
                    cur = loc;
                    continue;
                }
                if (code != 200) throw new Exception("下载失败 HTTP " + code);
                break;
            }
            if (conn == null) throw new Exception("下载跳转过多");
            in = conn.getInputStream();
            out = new BufferedOutputStream(new FileOutputStream(part));
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (cancel.get()) throw new Cancelled();
                out.write(buf, 0, n);
                got += n;
                if (cb != null) {
                    int pct = (int) Math.min(99, (doneBefore + got) * 100 / MODEL_TOTAL);
                    cb.onProgress("下载语音模型", pct);
                }
            }
            out.flush();
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) { }
            try { if (in != null) in.close(); } catch (Exception ignored) { }
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------- 生成 ----------------

    public static List<Subtitles.Cue> generate(Context ctx, String videoPath,
                                               Progress cb, AtomicBoolean cancel) throws Exception {
        if (!modelReady(ctx)) downloadModel(ctx, cb, cancel);
        File pcm = new File(ctx.getCacheDir(), "asr_audio.pcm");
        try {
            extractAudio(videoPath, pcm, cb, cancel);
            if (cancel.get()) throw new Cancelled();
            if (!pcm.isFile() || pcm.length() < TARGET_RATE) throw new Exception("没听到声音，这个视频可能没有音轨");
            return recognize(ctx, pcm, cb, cancel);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            pcm.delete();
        }
    }

    /** 抽音轨 → 16k 单声道 int16 PCM 文件。进度 0–30。 */
    private static void extractAudio(String videoPath, File outFile,
                                     Progress cb, AtomicBoolean cancel) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(videoPath);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new Exception("这个视频没有音轨");
            ex.selectTrack(track);
            long durationUs = fmt.containsKey(MediaFormat.KEY_DURATION)
                    ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            Resampler resampler = new Resampler();
            OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), 256 * 1024);
            try {
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputDone = false, outputDone = false;
                while (!outputDone) {
                    if (cancel.get()) throw new Cancelled();
                    if (!inputDone) {
                        int inIdx = codec.dequeueInputBuffer(10000);
                        if (inIdx >= 0) {
                            ByteBuffer ib = codec.getInputBuffer(inIdx);
                            int sz = ex.readSampleData(ib, 0);
                            if (sz < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sz, ex.getSampleTime(), 0);
                                ex.advance();
                            }
                        }
                    }
                    int outIdx = codec.dequeueOutputBuffer(info, 10000);
                    if (outIdx >= 0) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                        if (info.size > 0) {
                            ByteBuffer ob = codec.getOutputBuffer(outIdx);
                            MediaFormat of = codec.getOutputFormat();
                            int rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                            int ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                            int enc = of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                    ? of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                                    : android.media.AudioFormat.ENCODING_PCM_16BIT;
                            resampler.feed(ob, info.offset, info.size, rate, ch, enc, out);
                        }
                        codec.releaseOutputBuffer(outIdx, false);
                        if (cb != null && durationUs > 0) {
                            int pct = (int) Math.min(30, Math.max(0, info.presentationTimeUs * 30 / durationUs));
                            cb.onProgress("提取声音", pct);
                        }
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // 下一轮用新格式
                    }
                }
                resampler.flush(out);
            } finally {
                out.close();
            }
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) { }
            ex.release();
        }
    }

    /** 流式重采样：任意采样率/声道 PCM → 16k 单声道，线性插值。 */
    private static class Resampler {
        private float[] pending = new float[0]; // 已解码未消费完的单声道样本（源采样率）
        private int inRate = -1;
        private double nextPos = 0; // 下一个输出样本在 pending 流中的绝对位置（以源样本计）
        private long fedTotal = 0;  // 累计喂入的源样本数
        private long consumedBase = 0; // pending[0] 对应的绝对位置

        void feed(ByteBuffer ob, int offset, int size, int rate, int ch, int enc, OutputStream out)
                throws Exception {
            if (rate != inRate) { inRate = rate; }
            ob.position(offset);
            ob.limit(offset + size);
            int frames;
            float[] mono;
            if (enc == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                frames = size / 4 / ch;
                mono = new float[frames];
                for (int i = 0; i < frames; i++) {
                    float s = 0;
                    for (int c = 0; c < ch; c++) s += ob.getFloat();
                    mono[i] = s / ch;
                }
            } else {
                ob.order(ByteOrder.nativeOrder());
                frames = size / 2 / ch;
                mono = new float[frames];
                for (int i = 0; i < frames; i++) {
                    int s = 0;
                    for (int c = 0; c < ch; c++) s += ob.getShort();
                    mono[i] = (s / (float) ch) / 32768f;
                }
            }
            float[] np = new float[pending.length + mono.length];
            System.arraycopy(pending, 0, np, 0, pending.length);
            System.arraycopy(mono, 0, np, pending.length, mono.length);
            pending = np;
            fedTotal += mono.length;
            emit(out, false);
        }

        void flush(OutputStream out) throws Exception { emit(out, true); }

        private void emit(OutputStream out, boolean end) throws Exception {
            double ratio = (double) inRate / TARGET_RATE;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            while (true) {
                long idx = (long) Math.floor(nextPos);
                double frac = nextPos - idx;
                int i0 = (int) (idx - consumedBase);
                if (i0 < 0) { nextPos = consumedBase; continue; }
                if (i0 + 1 >= pending.length) {
                    if (!end) break;
                    if (i0 >= pending.length) break;
                    // 末尾：只剩一个样本，直接用它收尾一次
                    writeShort(bos, pending[pending.length - 1]);
                    nextPos = fedTotal;
                    break;
                }
                float v = (float) (pending[i0] * (1 - frac) + pending[i0 + 1] * frac);
                writeShort(bos, v);
                nextPos += ratio;
            }
            out.write(bos.toByteArray());
            // 丢弃已消费完的源样本
            long keepFrom = (long) Math.floor(nextPos);
            int drop = (int) Math.min(pending.length, Math.max(0, keepFrom - consumedBase));
            if (drop > 0) {
                float[] np = new float[pending.length - drop];
                System.arraycopy(pending, drop, np, 0, np.length);
                pending = np;
                consumedBase += drop;
            }
        }

        private void writeShort(java.io.ByteArrayOutputStream bos, float v) {
            int s = Math.max(-32768, Math.min(32767, Math.round(v * 32767f)));
            bos.write(s & 0xFF);
            bos.write((s >> 8) & 0xFF);
        }
    }

    /** 分窗口识别。进度 30–100。 */
    private static List<Subtitles.Cue> recognize(Context ctx, File pcm,
                                                 Progress cb, AtomicBoolean cancel) throws Exception {
        long totalSamples = pcm.length() / 2;
        int windowSamples = WINDOW_SEC * TARGET_RATE;
        int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
        com.k2fsa.sherpa.onnx.OfflineRecognizer rec =
                AsrBridge.INSTANCE.createRecognizer(modelDir(ctx).getAbsolutePath(), threads);
        List<Subtitles.Cue> out = new ArrayList<>();
        RandomAccessFile raf = new RandomAccessFile(pcm, "r");
        try {
            byte[] buf = new byte[windowSamples * 2];
            long doneSamples = 0;
            while (doneSamples < totalSamples) {
                if (cancel.get()) throw new Cancelled();
                int want = (int) Math.min(windowSamples, totalSamples - doneSamples);
                raf.seek(doneSamples * 2);
                raf.readFully(buf, 0, want * 2);
                float[] samples = new float[want];
                for (int i = 0; i < want; i++) {
                    int lo = buf[i * 2] & 0xFF, hi = buf[i * 2 + 1];
                    samples[i] = ((short) (lo | (hi << 8))) / 32768f;
                }
                final long baseSample = doneSamples;
                List<AsrBridge.Segment> segs = AsrBridge.INSTANCE.recognizeAll(
                        rec, samples, TARGET_RATE,
                        new Function0<Boolean>() {
                            @Override public Boolean invoke() { return cancel.get(); }
                        },
                        new Function2<Integer, Integer, kotlin.Unit>() {
                            @Override public kotlin.Unit invoke(Integer d, Integer t) { return kotlin.Unit.INSTANCE; }
                        });
                for (AsrBridge.Segment s : segs) {
                    Subtitles.Cue c = new Subtitles.Cue();
                    c.start = (long) ((baseSample / (double) TARGET_RATE + s.getStart()) * 1000);
                    c.end = (long) ((baseSample / (double) TARGET_RATE + s.getEnd()) * 1000);
                    c.text = s.getText();
                    out.add(c);
                }
                doneSamples += want;
                if (cb != null) {
                    int pct = 30 + (int) (doneSamples * 70 / Math.max(1, totalSamples));
                    cb.onProgress("识别字幕中", Math.min(100, pct));
                }
            }
        } finally {
            try { raf.close(); } catch (Exception ignored) { }
            try { rec.release(); } catch (Exception ignored) { }
        }
        if (out.isEmpty()) throw new Exception("没识别到说话声（可能只有音乐或声音太小）");
        return out;
    }

    // ---------------- 保存 ----------------

    /** 生成的字幕缓存文件（按视频路径定名）。 */
    public static File generatedFile(Context ctx, String videoPath) {
        File dir = new File(ctx.getCacheDir(), "gensub");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String key;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(videoPath.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            key = sb.toString();
        } catch (Exception e) {
            key = String.valueOf(videoPath.hashCode());
        }
        return new File(dir, key + ".srt");
    }

    /** 写成 .srt：优先视频同目录（下次自动加载），写不了则用缓存文件。返回最终文件。 */
    public static File saveSrt(Context ctx, String videoPath, List<Subtitles.Cue> cues) {
        String srt = toSrt(cues);
        File video = new File(videoPath);
        File parent = video.getParentFile();
        if (parent != null) {
            String name = video.getName();
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            File beside = new File(parent, name + ".srt");
            if (writeText(beside, srt)) return beside;
        }
        File cache = generatedFile(ctx, videoPath);
        writeText(cache, srt);
        return cache;
    }

    private static boolean writeText(File f, String content) {
        try {
            OutputStream out = new BufferedOutputStream(new FileOutputStream(f));
            out.write(content.getBytes("UTF-8"));
            out.close();
            return f.isFile() && f.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static String toSrt(List<Subtitles.Cue> cues) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cues.size(); i++) {
            Subtitles.Cue c = cues.get(i);
            sb.append(i + 1).append('\n');
            sb.append(fmtTs(c.start)).append(" --> ").append(fmtTs(c.end)).append('\n');
            sb.append(c.text.replace("\n", " ")).append("\n\n");
        }
        return sb.toString();
    }

    private static String fmtTs(long ms) {
        long h = ms / 3600000, m = (ms % 3600000) / 60000, s = (ms % 60000) / 1000, x = ms % 1000;
        return String.format("%02d:%02d:%02d,%03d", h, m, s, x);
    }
}
