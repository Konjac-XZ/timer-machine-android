package com.github.deweyreed.timer.component.tts

import android.content.Context
import android.util.Base64
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import xyz.aprildown.timer.app.base.data.PreferenceData.CloudTtsSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

internal class VolcengineTtsClient(
    private val settings: CloudTtsSettings,
) {
    data class TimedSpeech(
        val text: String,
        val audio: ByteArray,
        val sampleRate: Int,
    )

    private data class SynthesisResult(
        val audio: ByteArray,
        val subtitles: List<Subtitle>,
    )

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun synthesizeToFile(context: Context, text: String): File = withContext(Dispatchers.IO) {
        val audio = synthesize(
            text = text,
            audioFormat = AUDIO_FORMAT_MP3,
            enableSubtitle = false,
        ).audio
        createTempCloudSpeechFile(context).also { file ->
            file.writeBytes(audio)
        }
    }

    suspend fun synthesizeTimedSpeech(texts: List<String>): List<TimedSpeech> = withContext(Dispatchers.IO) {
        val synthesisResult = synthesize(
            text = texts.joinToString(separator = "\n") { it.asSentenceText() },
            audioFormat = AUDIO_FORMAT_PCM,
            enableSubtitle = true,
        )
        val subtitles = synthesisResult.subtitles
        check(subtitles.size >= texts.size) {
            "Expected at least ${texts.size} subtitle segments, got ${subtitles.size}"
        }

        texts.mapIndexed { index, text ->
            val subtitle = subtitles[index]
            TimedSpeech(
                text = text,
                audio = synthesisResult.audio.slicePcmBySeconds(
                    startSeconds = subtitle.startSeconds,
                    endSeconds = subtitle.endSeconds,
                    sampleRate = SAMPLE_RATE,
                ),
                sampleRate = SAMPLE_RATE,
            )
        }
    }

    private fun synthesize(
        text: String,
        audioFormat: String,
        enableSubtitle: Boolean,
    ): SynthesisResult {
        val requestId = UUID.randomUUID().toString()
        val request = Request.Builder()
            .url(HTTP_CHUNKED_URL)
            .header("X-Api-Key", settings.apiKey)
            .header("X-Api-Resource-Id", settings.resourceId)
            .header("X-Api-Request-Id", requestId)
            .post(
                synthesisPayload(
                    text = text,
                    audioFormat = audioFormat,
                    enableSubtitle = enableSubtitle,
                ).toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(
                    "Volcengine TTS failed: code=${response.code}, requestId=$requestId, " +
                        "logId=${response.header("X-Tt-Logid")}, body=$body"
                )
            }
            return parseSynthesisResponse(body)
        }
    }

    private fun synthesisPayload(
        text: String,
        audioFormat: String,
        enableSubtitle: Boolean,
    ): String {
        return moshiMapAdapter.toJson(
            mapOf(
                "user" to mapOf("uid" to "timer-machine-android"),
                "req_params" to requestParams(
                    text = text,
                    audioFormat = audioFormat,
                    enableSubtitle = enableSubtitle,
                ),
            )
        )
    }

    private fun requestParams(
        text: String,
        audioFormat: String,
        enableSubtitle: Boolean,
    ): Map<String, Any> {
        val audioParams = mutableMapOf<String, Any>(
            "format" to audioFormat,
            "sample_rate" to SAMPLE_RATE,
            "speech_rate" to settings.speechRate.coerceIn(-50, 100),
            "emotion_scale" to settings.emotionScale.coerceIn(1, 5),
        )
        if (audioFormat == AUDIO_FORMAT_MP3) {
            audioParams["bit_rate"] = 64000
        }
        if (enableSubtitle) {
            if (settings.resourceId in RESOURCE_IDS_USING_SUBTITLE) {
                audioParams["enable_subtitle"] = true
            } else {
                audioParams["enable_timestamp"] = true
            }
        }

        val reqParams = mutableMapOf<String, Any>(
            "text" to text,
            "speaker" to settings.speaker,
            "audio_params" to audioParams,
        )
        if (settings.resourceId in RESOURCE_IDS_SUPPORTING_CONTEXT_TEXTS) {
            reqParams["additions"] = moshiMapAdapter.toJson(
                mapOf("context_texts" to listOf(COUNTDOWN_CONTEXT_TEXT))
            )
        }
        return reqParams
    }

    private fun parseSynthesisResponse(body: String): SynthesisResult {
        val audioOutput = ByteArrayOutputStream()
        val subtitles = mutableListOf<Subtitle>()
        var finalStatus: Map<*, *>? = null
        val reader = JsonReader.of(Buffer().writeUtf8(body)).apply {
            isLenient = true
        }

        while (reader.peek() != JsonReader.Token.END_DOCUMENT) {
            val frame = reader.readJsonValue() as? Map<*, *> ?: continue
            (frame["data"] as? String)?.let { data ->
                audioOutput.write(Base64.decode(data, Base64.DEFAULT))
            }
            frame.subtitleOrNull()?.let(subtitles::add)
            if (frame["code"] is Number) {
                finalStatus = frame
            }
        }

        val finalCode = (finalStatus?.get("code") as? Number)?.toInt()
        if (finalCode != null && finalCode != CODE_OK) {
            error("Volcengine TTS failed: $finalStatus")
        }

        return SynthesisResult(
            audio = audioOutput.toByteArray(),
            subtitles = subtitles,
        )
    }

    private fun Map<*, *>.subtitleOrNull(): Subtitle? {
        val subtitleMap = this["sentence"] as? Map<*, *>
            ?: takeIf { it["words"] is List<*> }
            ?: return null
        val words = subtitleMap["words"] as? List<*> ?: return null
        return Subtitle(
            text = subtitleMap["text"] as? String ?: "",
            words = words.mapNotNull { word ->
                val wordMap = word as? Map<*, *> ?: return@mapNotNull null
                val startTime = wordMap.doubleValue("startTime", "start_time") ?: return@mapNotNull null
                val endTime = wordMap.doubleValue("endTime", "end_time") ?: return@mapNotNull null
                SubtitleWord(
                    startTime = startTime,
                    endTime = endTime,
                    word = wordMap["word"] as? String ?: "",
                )
            },
        ).takeIf { it.words.isNotEmpty() }
    }

    private fun Map<*, *>.doubleValue(vararg keys: String): Double? {
        keys.forEach { key ->
            val value = this[key]
            if (value is Number) return value.toDouble()
        }
        return null
    }

    private fun createTempCloudSpeechFile(context: Context): File {
        val folder = File(context.cacheDir, "tts-cloud-temp")
        folder.mkdirs()
        return File(folder, "${UUID.randomUUID()}.mp3")
    }

    private fun String.asSentenceText(): String {
        val trimmed = trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.last() in SENTENCE_ENDINGS) trimmed else "$trimmed。"
    }

    private fun ByteArray.slicePcmBySeconds(
        startSeconds: Double,
        endSeconds: Double,
        sampleRate: Int,
    ): ByteArray {
        val byteRate = sampleRate * PCM_BYTES_PER_SAMPLE
        val start = (startSeconds * byteRate)
            .toInt()
            .coerceIn(0, size)
            .alignPcmOffset()
        val end = (endSeconds * byteRate)
            .toInt()
            .coerceIn(start, size)
            .alignPcmOffset()
        return copyOfRange(start, end).trimPcmSilence()
    }

    private fun Int.alignPcmOffset(): Int = this - (this % PCM_BYTES_PER_SAMPLE)

    private fun ByteArray.trimPcmSilence(): ByteArray {
        if (size <= PCM_BYTES_PER_SAMPLE) return this

        var start = 0
        while (start + 1 < size && pcmAmplitudeAt(start) <= SILENCE_THRESHOLD) {
            start += PCM_BYTES_PER_SAMPLE
        }

        var end = size.alignPcmOffset()
        while (end - PCM_BYTES_PER_SAMPLE >= start &&
            pcmAmplitudeAt(end - PCM_BYTES_PER_SAMPLE) <= SILENCE_THRESHOLD
        ) {
            end -= PCM_BYTES_PER_SAMPLE
        }

        start = (start - TRIM_PADDING_BYTES).coerceAtLeast(0).alignPcmOffset()
        end = (end + TRIM_PADDING_BYTES).coerceAtMost(size).alignPcmOffset()
        return if (start < end) copyOfRange(start, end) else this
    }

    private fun ByteArray.pcmAmplitudeAt(offset: Int): Int {
        val low = this[offset].toInt() and 0xFF
        val high = this[offset + 1].toInt()
        return abs(((high shl 8) or low).toShort().toInt())
    }

    private data class Subtitle(
        val text: String,
        val words: List<SubtitleWord>,
    ) {
        val startSeconds: Double
            get() = words.minOf { it.startTime }

        val endSeconds: Double
            get() = words.maxOf { it.endTime }
    }

    private data class SubtitleWord(
        val startTime: Double,
        val endTime: Double,
        val word: String,
    )

    private companion object {
        const val HTTP_CHUNKED_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
        const val CODE_OK = 20_000_000
        const val COUNTDOWN_CONTEXT_TEXT = "这是倒计时播报。请保持稳定、中性的语气和节奏，不要加入额外感情。"
        const val AUDIO_FORMAT_MP3 = "mp3"
        const val AUDIO_FORMAT_PCM = "pcm"
        const val SAMPLE_RATE = 24000
        const val PCM_BYTES_PER_SAMPLE = 2
        const val SILENCE_THRESHOLD = 256
        const val TRIM_PADDING_MILLIS = 20
        const val TRIM_PADDING_BYTES = SAMPLE_RATE * PCM_BYTES_PER_SAMPLE * TRIM_PADDING_MILLIS / 1000

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val RESOURCE_IDS_SUPPORTING_CONTEXT_TEXTS = setOf(
            "seed-tts-2.0",
            "seed-icl-2.0",
        )

        val RESOURCE_IDS_USING_SUBTITLE = setOf(
            "seed-tts-2.0",
            "seed-icl-2.0",
        )

        val SENTENCE_ENDINGS = setOf(
            '。',
            '！',
            '？',
            '.',
            '!',
            '?',
        )

        val moshiMapAdapter = Moshi.Builder().build().adapter<Map<String, Any>>(
            Types.newParameterizedType(
                Map::class.java,
                String::class.java,
                Any::class.java,
            )
        )
    }
}
