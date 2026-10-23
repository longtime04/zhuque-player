package com.muse.player.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 语音生成字幕引擎（sherpa-onnx + Whisper base，纯本地离线）。
 * 模型文件（放 modelDir）：base-encoder.int8.onnx / base-decoder.int8.onnx / base-tokens.txt。
 * 官方导出模型不带词级时间戳：先用能量 VAD 按停顿切块、逐块识别，
 * 块的起止时间即字幕时间；块内长文本再按时长比例拆成多条。
 */
object AsrBridge {

    class Segment(val start: Float, val end: Float, val text: String) {
        override fun toString(): String = "[%.2f -> %.2f] %s".format(start, end, text)
    }

    class Chunk(val startSec: Float, val endSec: Float, val samples: FloatArray)

    fun createRecognizer(modelDir: String, numThreads: Int): OfflineRecognizer {
        val whisper = OfflineWhisperModelConfig(
            encoder = "$modelDir/base-encoder.int8.onnx",
            decoder = "$modelDir/base-decoder.int8.onnx",
            language = "",
            task = "transcribe",
            tailPaddings = 1000,
            enableTokenTimestamps = false,
            enableSegmentTimestamps = true
        )
        val modelConfig = OfflineModelConfig(
            whisper = whisper,
            tokens = "$modelDir/base-tokens.txt",
            numThreads = numThreads,
            debug = false,
            provider = "cpu",
            modelType = "whisper"
        )
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80, dither = 0.0f),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )
        return OfflineRecognizer(null, config)
    }

    /**
     * 能量 VAD 切块：20ms 帧 RMS 超阈值算语音，停顿 ≥0.6s 切开，
     * 块最长 20s（在 15~20s 的低能量处强切），短于 1s 的碎块并入前一块。
     */
    fun splitChunks(samples: FloatArray, sampleRate: Int): List<Chunk> {
        val frameLen = sampleRate / 50 // 20ms
        val nFrames = samples.size / frameLen
        if (nFrames == 0) return emptyList()
        val rms = FloatArray(nFrames)
        var maxRms = 0f
        for (f in 0 until nFrames) {
            var sum = 0.0
            val base = f * frameLen
            for (i in 0 until frameLen) {
                val v = samples[base + i].toDouble()
                sum += v * v
            }
            val r = sqrt(sum / frameLen).toFloat()
            rms[f] = r
            if (r > maxRms) maxRms = r
        }
        val threshold = maxOf(0.006f, maxRms * 0.08f)
        val speech = BooleanArray(nFrames) { rms[it] > threshold }
        val hang = 15 // 0.3s hangover，防句中气口切碎
        var lastSpeech = -10000
        for (f in 0 until nFrames) {
            if (speech[f]) lastSpeech = f
            else if (f - lastSpeech <= hang) speech[f] = true
        }
        val ranges = ArrayList<Pair<Int, Int>>()
        var start = -1
        var silenceRun = 0
        val minPause = 30 // 0.6s
        for (f in 0 until nFrames) {
            if (speech[f]) {
                if (start < 0) start = f
                silenceRun = 0
            } else if (start >= 0) {
                silenceRun++
                if (silenceRun >= minPause) {
                    ranges.add(start to (f - silenceRun + 1))
                    start = -1
                    silenceRun = 0
                }
            }
        }
        if (start >= 0) ranges.add(start to nFrames)
        val secPerFrame = frameLen.toFloat() / sampleRate
        fun makeChunk(fs: Int, fe: Int): Chunk {
            val s0 = fs * frameLen
            val e0 = min(fe * frameLen, samples.size)
            return Chunk(fs * secPerFrame, fe * secPerFrame, samples.copyOfRange(s0, e0))
        }
        val chunks = ArrayList<Chunk>()
        val maxFrames = 20 * 50
        for ((fs, fe) in ranges) {
            var s = fs
            while (fe - s > maxFrames) {
                var cut = s + 15 * 50
                var best = Float.MAX_VALUE
                for (f in s + 15 * 50 until min(fe, s + maxFrames)) {
                    if (rms[f] < best) { best = rms[f]; cut = f }
                }
                chunks.add(makeChunk(s, cut))
                s = cut
            }
            if (fe > s) chunks.add(makeChunk(s, fe))
        }
        val merged = ArrayList<Chunk>()
        for (c in chunks) {
            if (c.endSec - c.startSec < 1.0f && merged.isNotEmpty()) {
                val prev = merged.removeAt(merged.size - 1)
                val cat = FloatArray(prev.samples.size + c.samples.size)
                System.arraycopy(prev.samples, 0, cat, 0, prev.samples.size)
                System.arraycopy(c.samples, 0, cat, prev.samples.size, c.samples.size)
                merged.add(Chunk(prev.startSec, c.endSec, cat))
            } else merged.add(c)
        }
        return merged
    }

    /** 识别一块，返回该块文本（trim 后）。 */
    fun recognizeChunk(rec: OfflineRecognizer, chunk: Chunk, sampleRate: Int): String {
        val stream = rec.createStream()
        stream.acceptWaveform(chunk.samples, sampleRate)
        rec.decode(stream)
        val text = rec.getResult(stream).text.trim()
        stream.release()
        return text
    }

    /**
     * 全量识别：切块 → 逐块识别 → 生成字幕条。
     * onProgress(doneChunks, totalChunks) 在每块完成后回调；返回 null 表示被取消（由回调抛异常约定改为标志位）。
     */
    fun recognizeAll(
        rec: OfflineRecognizer,
        samples: FloatArray,
        sampleRate: Int,
        cancelled: () -> Boolean,
        onProgress: (Int, Int) -> Unit
    ): List<Segment> {
        val chunks = splitChunks(samples, sampleRate)
        val out = ArrayList<Segment>()
        for (i in chunks.indices) {
            if (cancelled()) break
            val c = chunks[i]
            val text = recognizeChunk(rec, c, sampleRate)
            if (text.isNotEmpty()) out.addAll(splitTextToCues(text, c.startSec, c.endSec))
            onProgress(i + 1, chunks.size)
        }
        return out
    }

    /** 块内文本按时长比例拆条：目标每条 ≤6 秒，优先在标点/空格处断。 */
    fun splitTextToCues(text: String, startSec: Float, endSec: Float): List<Segment> {
        val dur = endSec - startSec
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (dur <= 7.0f || t.length <= 30) return listOf(Segment(startSec, endSec, t))
        val n = maxOf(1, kotlin.math.ceil(dur / 6.0).toInt())
        // 切分单元：拉丁文本按词，CJK 按字；标点吸附在前一单元尾
        val units = ArrayList<String>()
        if (t.contains(' ')) {
            for (w in t.split(' ')) if (w.isNotEmpty()) units.add(w)
        } else {
            for (ch in t) units.add(ch.toString())
        }
        if (units.size <= n) return listOf(Segment(startSec, endSec, t))
        val totalLen = units.sumOf { it.length }
        val parts = ArrayList<String>()
        val sb = StringBuilder()
        var target = totalLen.toFloat() / n
        var acc = 0
        for (u in units) {
            sb.append(if (t.contains(' ') && sb.isNotEmpty()) " " else "").append(u)
            acc += u.length
            val lastChar = u.last()
            val boundary = lastChar in "。.!?！？…," || lastChar == '，' || lastChar == '、'
            if (parts.size < n - 1 && acc >= target && (boundary || acc >= target * 1.4)) {
                parts.add(sb.toString().trim())
                sb.setLength(0)
                acc = 0
            }
        }
        if (sb.isNotEmpty()) parts.add(sb.toString().trim())
        val result = ArrayList<Segment>()
        val totalChars = parts.sumOf { it.length }.toFloat()
        var cursor = startSec
        for (i in parts.indices) {
            val frac = if (totalChars > 0) parts[i].length / totalChars else 1f / parts.size
            val e = if (i == parts.size - 1) endSec else cursor + dur * frac
            result.add(Segment(cursor, e, parts[i]))
            cursor = e
        }
        return result
    }
}
