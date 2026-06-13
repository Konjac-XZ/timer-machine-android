package com.github.deweyreed.timer.component.tts

import android.content.Context
import android.util.Base64
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import timber.log.Timber
import xyz.aprildown.timer.app.base.data.PreferenceData
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

    suspend fun synthesizeToFile(
        context: Context,
        text: String,
    ): File = withContext(Dispatchers.IO) {
        val audio = synthesize(
            text = text,
            audioFormat = AUDIO_FORMAT_MP3,
            enableSubtitle = false,
        ).audio
        createTempCloudSpeechFile(context).also { file ->
            file.writeBytes(audio)
        }
    }

    suspend fun synthesizeTimedSpeech(
        texts: List<String>,
    ): List<TimedSpeech> = withContext(Dispatchers.IO) {
        val requests = texts.map { text ->
            TimedSpeechRequest(
                text = text,
                normalizedText = normalizeSubtitleText(text),
                synthesisText = asSentenceText(text),
            )
        }
        val maxSentencesPerRequest = settings.maxSentencesPerRequest.coerceIn(
            PreferenceData.CLOUD_TTS_MIN_SENTENCES_PER_REQUEST,
            PreferenceData.CLOUD_TTS_MAX_SENTENCES_PER_REQUEST,
        )
        val maxConcurrency = settings.maxConcurrency.coerceIn(
            PreferenceData.CLOUD_TTS_MIN_CONCURRENCY,
            PreferenceData.CLOUD_TTS_MAX_CONCURRENCY,
        )
        val batches = requests.chunked(maxSentencesPerRequest)
        val semaphore = Semaphore(maxConcurrency)
        Timber
            .tag(TTS_LOG_TAG)
            .i(
                "Timed synthesis batching: texts=%d batches=%d maxSentencesPerRequest=%d maxConcurrency=%d textsByIndex=%s",
                requests.size,
                batches.size,
                maxSentencesPerRequest,
                maxConcurrency,
                requests.debugRequestsByIndex(),
            )
        batches
            .mapIndexed { batchIndex, batchRequests ->
                async {
                    semaphore.withPermit {
                        synthesizeTimedSpeechBatch(
                            requests = batchRequests,
                            batchIndex = batchIndex,
                            batchCount = batches.size,
                        )
                    }
                }
            }
            .awaitAll()
            .flatten()
    }

    private fun synthesizeTimedSpeechBatch(
        requests: List<TimedSpeechRequest>,
        batchIndex: Int,
        batchCount: Int,
    ): List<TimedSpeech> {
        val synthesisText = requests.joinToString(separator = "\n") { it.synthesisText }
        Timber
            .tag(TTS_LOG_TAG)
            .i(
                "Timed synthesis batch request: batch=%d/%d count=%d first=%s last=%s chars=%d requests=%s synthesisText=%s",
                batchIndex + 1,
                batchCount,
                requests.size,
                requests.firstOrNull()?.text,
                requests.lastOrNull()?.text,
                synthesisText.length,
                requests.debugRequestsByIndex(),
                synthesisText.escapeForLog(),
            )
        val synthesisResult = synthesizeSse(
            text = synthesisText,
            audioFormat = AUDIO_FORMAT_PCM,
            enableSubtitle = true,
        )
        val words = synthesisResult.subtitles.flatMap { it.words }
        Timber
            .tag(TTS_LOG_TAG)
            .i(
                "Timed synthesis batch response: batch=%d/%d audioBytes=%d audioDuration=%.3f sentences=%d words=%d subtitleEnd=%.3f trailingAudio=%.3f sentencesDetail=%s wordsDetail=%s",
                batchIndex + 1,
                batchCount,
                synthesisResult.audio.size,
                synthesisResult.audio.pcmDurationSeconds(sampleRate = SAMPLE_RATE),
                synthesisResult.subtitles.size,
                words.size,
                synthesisResult.subtitles.maxOfOrNull { it.endSeconds } ?: 0.0,
                synthesisResult.audio.pcmDurationSeconds(sampleRate = SAMPLE_RATE) -
                    (synthesisResult.subtitles.maxOfOrNull { it.endSeconds } ?: 0.0),
                synthesisResult.subtitles.debugSubtitlesByIndex(),
                words.debugWordsAround(index = 0, radius = DEBUG_WORDS_FULL_BATCH_RADIUS),
            )
        val subtitleSlices = synthesisResult.sliceTimedSpeechBySubtitles(
            requests = requests,
            words = words,
            batchIndex = batchIndex,
            batchCount = batchCount,
        )
        if (subtitleSlices.size == requests.size) {
            return subtitleSlices
        }

        Timber
            .tag(TTS_LOG_TAG)
            .w(
                "Timed synthesis subtitles incomplete, falling back to PCM segmentation: batch=%d/%d expected=%d subtitleSlices=%d",
                batchIndex + 1,
                batchCount,
                requests.size,
                subtitleSlices.size,
            )
        return synthesisResult.audio.sliceTimedSpeechByPcmEnergy(
            requests = requests,
            batchIndex = batchIndex,
            batchCount = batchCount,
            fallback = subtitleSlices,
        )
    }

    private fun SynthesisResult.sliceTimedSpeechBySubtitles(
        requests: List<TimedSpeechRequest>,
        words: List<SubtitleWord>,
        batchIndex: Int,
        batchCount: Int,
    ): List<TimedSpeech> {
        var wordIndex = 0
        return requests.mapNotNull { request ->
            val startWordIndex = wordIndex
            val matchedWords = mutableListOf<SubtitleWord>()
            var matchedText = ""
            while (wordIndex < words.size && matchedText != request.normalizedText) {
                val word = words[wordIndex++]
                matchedWords += word
                matchedText += word.normalizedWord
            }
            if (matchedWords.isEmpty() || matchedText != request.normalizedText) {
                Timber
                    .tag(TTS_LOG_TAG)
                    .w(
                        "Timed synthesis subtitle mismatch: batch=%d/%d text=%s normalized=%s actual=%s startWordIndex=%d endWordIndex=%d words=%d nearbyWords=%s",
                        batchIndex + 1,
                        batchCount,
                        request.text,
                        request.normalizedText,
                        matchedText,
                        startWordIndex,
                        wordIndex,
                        words.size,
                        words.debugWordsAround(startWordIndex),
                    )
                return@mapNotNull null
            }
            TimedSpeech(
                text = request.text,
                audio = audio.slicePcmBySeconds(
                    startSeconds = matchedWords.first().startTime,
                    endSeconds = matchedWords.last().endTime,
                    sampleRate = SAMPLE_RATE,
                ).also { slicedAudio ->
                    Timber
                        .tag(TTS_LOG_TAG)
                        .i(
                            "Timed synthesis slice: batch=%d/%d text=%s normalized=%s wordRange=%d..%d words=%s start=%.3f end=%.3f bytes=%d",
                            batchIndex + 1,
                            batchCount,
                            request.text,
                            request.normalizedText,
                            startWordIndex,
                            wordIndex - 1,
                            matchedWords.joinToString(separator = "|") { it.word },
                            matchedWords.first().startTime,
                            matchedWords.last().endTime,
                            slicedAudio.size,
                        )
                },
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
            Timber
                .tag(TTS_LOG_TAG)
                .i(
                    "HTTP synthesis finished: code=%d requestId=%s logId=%s bodyChars=%d",
                    response.code,
                    requestId,
                    response.header("X-Tt-Logid"),
                    body.length,
                )
            if (!response.isSuccessful) {
                error(
                    "Volcengine TTS failed: code=${response.code}, requestId=$requestId, " +
                        "logId=${response.header("X-Tt-Logid")}, body=$body"
                )
            }
            return parseSynthesisResponse(body)
        }
    }

    private fun synthesizeSse(
        text: String,
        audioFormat: String,
        enableSubtitle: Boolean,
    ): SynthesisResult {
        val requestId = UUID.randomUUID().toString()
        val request = Request.Builder()
            .url(HTTP_SSE_URL)
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
            Timber
                .tag(TTS_LOG_TAG)
                .i(
                    "HTTP SSE synthesis finished: code=%d requestId=%s logId=%s bodyChars=%d contentType=%s",
                    response.code,
                    requestId,
                    response.header("X-Tt-Logid"),
                    body.length,
                    response.header("Content-Type"),
                )
            if (!response.isSuccessful) {
                error(
                    "Volcengine TTS SSE failed: code=${response.code}, requestId=$requestId, " +
                        "logId=${response.header("X-Tt-Logid")}, body=$body"
                )
            }
            return parseSseSynthesisResponse(body)
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
        val contextText = settings.contextText.trim()
        if (settings.resourceId in RESOURCE_IDS_SUPPORTING_CONTEXT_TEXTS && contextText.isNotEmpty()) {
            reqParams["additions"] = moshiMapAdapter.toJson(
                mapOf("context_texts" to listOf(contextText))
            )
        }
        return reqParams
    }

    private fun parseSynthesisResponse(body: String): SynthesisResult {
        val audioOutput = ByteArrayOutputStream()
        val subtitles = mutableListOf<Subtitle>()
        var finalStatus: Map<*, *>? = null
        var frameCount = 0
        var audioFrameCount = 0
        var subtitleFrameCount = 0
        val reader = JsonReader.of(Buffer().writeUtf8(body)).apply {
            isLenient = true
        }

        while (reader.peek() != JsonReader.Token.END_DOCUMENT) {
            val frame = reader.readJsonValue() as? Map<*, *> ?: continue
            frameCount++
            if (LOG_RESPONSE_FRAMES) {
                logResponseFrame(
                    source = "chunked",
                    frame = frameCount.toString(),
                    event = frame["event"]?.toString(),
                    data = frame,
                )
            }
            val hasAudio = frame["data"] is String
            if (hasAudio) {
                val data = frame["data"] as String
                audioOutput.write(Base64.decode(data, Base64.DEFAULT))
                audioFrameCount++
            }
            val subtitle = frame.subtitleOrNull()
            if (subtitle != null) {
                subtitles += subtitle
                subtitleFrameCount++
            } else if (!hasAudio) {
                frame.logUnparsedTextFrame(source = "chunked", frame = frameCount.toString())
            }
            if (frame["code"] is Number) {
                finalStatus = frame
            }
        }

        val finalCode = (finalStatus?.get("code") as? Number)?.toInt()
        if (finalCode != null && finalCode != CODE_OK) {
            error("Volcengine TTS failed: $finalStatus")
        }

        Timber
            .tag(TTS_LOG_TAG)
            .i(
                "Parsed synthesis response: frames=%d audioFrames=%d subtitleFrames=%d audioBytes=%d finalStatus=%s",
                frameCount,
                audioFrameCount,
                subtitleFrameCount,
                audioOutput.size(),
                finalStatus,
            )

        return SynthesisResult(
            audio = audioOutput.toByteArray(),
            subtitles = subtitles,
        )
    }

    private fun parseSseSynthesisResponse(body: String): SynthesisResult {
        val audioOutput = ByteArrayOutputStream()
        val subtitles = mutableListOf<Subtitle>()
        var finalStatus: Map<*, *>? = null
        var event: String? = null
        val dataLines = mutableListOf<String>()
        var eventCount = 0
        var audioFrameCount = 0
        var subtitleFrameCount = 0
        val eventCounts = mutableMapOf<String, Int>()

        fun flushEvent() {
            if (dataLines.isEmpty()) return

            eventCount++
            val eventName = event.orEmpty()
            eventCounts[eventName] = eventCounts.getOrDefault(eventName, 0) + 1
            val data = dataLines.joinToString(separator = "\n")
            dataLines.clear()

            val frame = JsonReader.of(Buffer().writeUtf8(data)).apply {
                isLenient = true
            }.readJsonValue() as? Map<*, *> ?: return
            if (LOG_RESPONSE_FRAMES) {
                logResponseFrame(
                    source = "sse",
                    frame = eventCount.toString(),
                    event = eventName,
                    data = frame,
                )
            }

            val hasAudio = frame["data"] is String
            if (hasAudio) {
                audioOutput.write(Base64.decode(frame["data"] as String, Base64.DEFAULT))
                audioFrameCount++
            }
            val subtitle = frame.subtitleOrNull()
            if (subtitle != null) {
                subtitles += subtitle
                subtitleFrameCount++
            } else if (!hasAudio) {
                frame.logUnparsedTextFrame(source = "sse", frame = eventName)
            }
            if (frame["code"] is Number) {
                finalStatus = frame
            }
        }

        body.lineSequence().forEach { line ->
            when {
                line.isEmpty() -> {
                    flushEvent()
                    event = null
                }
                line.startsWith("event:") -> {
                    event = line.substringAfter("event:").trim()
                }
                line.startsWith("data:") -> {
                    dataLines += line.substringAfter("data:").trimStart()
                }
            }
        }
        flushEvent()

        val finalCode = (finalStatus?.get("code") as? Number)?.toInt()
        if (finalCode != null && finalCode != CODE_OK) {
            error("Volcengine TTS SSE failed: $finalStatus")
        }

        Timber
            .tag(TTS_LOG_TAG)
            .i(
                "Parsed SSE synthesis response: events=%d eventCounts=%s audioFrames=%d subtitleFrames=%d audioBytes=%d finalStatus=%s",
                eventCount,
                eventCounts,
                audioFrameCount,
                subtitleFrameCount,
                audioOutput.size(),
                finalStatus,
            )

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
            when (value) {
                is Number -> return value.toDouble().normalizeTimestampSeconds()
                is String -> value.toDoubleOrNull()?.let { return it.normalizeTimestampSeconds() }
            }
        }
        return null
    }

    private fun Double.normalizeTimestampSeconds(): Double {
        return if (this > TIMESTAMP_MILLIS_THRESHOLD) this / 1000 else this
    }

    private fun createTempCloudSpeechFile(context: Context): File {
        val folder = File(context.cacheDir, "tts-cloud-temp")
        folder.mkdirs()
        return File(folder, "${UUID.randomUUID()}.mp3")
    }

    private data class TimedSpeechRequest(
        val text: String,
        val normalizedText: String,
        val synthesisText: String,
    )

    private fun asSentenceText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.last() in SENTENCE_ENDINGS) trimmed else "$trimmed。"
    }

    private fun normalizeSubtitleText(text: String): String {
        return text.trim().trim(*SENTENCE_ENDINGS_ARRAY)
    }

    private val SubtitleWord.normalizedWord: String
        get() = normalizeSubtitleText(word)

    private val Subtitle.debugText: String
        get() = "text=${text.escapeForLog()} words=${words.joinToString(separator = "|") { it.debugText }}"

    private val SubtitleWord.debugText: String
        get() = "${word.escapeForLog()}@${"%.3f".format(startTime)}-${"%.3f".format(endTime)}"

    private fun List<TimedSpeechRequest>.debugRequestsByIndex(): String {
        return mapIndexed { index, request ->
            "$index:${request.text.escapeForLog()}=>${request.synthesisText.escapeForLog()}"
        }.joinToString(separator = "|")
    }

    private fun List<Subtitle>.debugSubtitlesByIndex(): String {
        return mapIndexed { index, subtitle ->
            "$index:${subtitle.debugText}"
        }.joinToString(separator = " || ")
    }

    private fun List<SubtitleWord>.debugWordsAround(
        index: Int,
        radius: Int = DEBUG_WORDS_RADIUS,
    ): String {
        if (isEmpty()) return ""
        val start = (index - radius).coerceAtLeast(0)
        val end = (index + radius).coerceAtMost(size)
        return subList(start, end)
            .mapIndexed { offset, word -> "${start + offset}:${word.debugText}" }
            .joinToString(separator = "|")
    }

    private fun String.escapeForLog(): String {
        return replace("\r", "\\r").replace("\n", "\\n")
    }

    private fun Map<*, *>.logUnparsedTextFrame(source: String, frame: String) {
        if (this["code"] is Number && this["sentence"] !is Map<*, *> && this["words"] !is List<*>) return

        Timber
            .tag(TTS_LOG_TAG)
            .i(
                "Parsed %s text frame without subtitle: frame=%s keys=%s code=%s message=%s sentenceType=%s sentence=%s wordsType=%s words=%s",
                source,
                frame,
                keys.joinToString(separator = "|"),
                this["code"],
                this["message"],
                this["sentence"]?.javaClass?.simpleName,
                this["sentence"].debugValueForLog(),
                this["words"]?.javaClass?.simpleName,
                this["words"].debugValueForLog(),
            )
    }

    private fun logResponseFrame(
        source: String,
        frame: String,
        event: String?,
        data: Map<*, *>,
    ) {
        val text = "Parsed $source response frame: frame=$frame event=$event data=${data.debugFrameForLog()}"
        text.chunked(LOG_CHUNK_SIZE).forEachIndexed { index, chunk ->
            Timber
                .tag(TTS_LOG_TAG)
                .i(
                    "Response frame detail part=%d/%d %s",
                    index + 1,
                    (text.length + LOG_CHUNK_SIZE - 1) / LOG_CHUNK_SIZE,
                    chunk,
                )
        }
    }

    private fun Any?.debugValueForLog(): String {
        return when (this) {
            null -> "null"
            is String -> escapeForLog()
            is Map<*, *> -> debugMapForLog()
            is List<*> -> joinToString(separator = "|", prefix = "[", postfix = "]") { it.debugValueForLog() }
            else -> toString()
        }
    }

    private fun Map<*, *>.debugFrameForLog(): String {
        return entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            val displayValue = if (key == "data" && value is String) {
                "base64(length=${value.length})"
            } else {
                value.debugValueForLog()
            }
            "$key=$displayValue"
        }
    }

    private fun Map<*, *>.debugMapForLog(): String {
        return entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            "$key=${value.debugValueForLog()}"
        }
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

    private fun ByteArray.sliceTimedSpeechByPcmEnergy(
        requests: List<TimedSpeechRequest>,
        batchIndex: Int,
        batchCount: Int,
        fallback: List<TimedSpeech>,
    ): List<TimedSpeech> {
        val activeRegions = findPcmActiveRegions(sampleRate = SAMPLE_RATE)
        val splitRegions = activeRegions.splitIntoSpeechRegions(
            expectedCount = requests.size,
            audioSize = size,
        )
        Timber
            .tag(TTS_LOG_TAG)
            .i(
                "Timed synthesis PCM segmentation: batch=%d/%d expected=%d activeRegions=%d splitRegions=%d activeDetail=%s splitDetail=%s",
                batchIndex + 1,
                batchCount,
                requests.size,
                activeRegions.size,
                splitRegions.size,
                activeRegions.debugPcmRegionsForLog(),
                splitRegions.debugPcmRegionsForLog(),
            )

        if (splitRegions.size != requests.size) {
            Timber
                .tag(TTS_LOG_TAG)
                .w(
                    "Timed synthesis PCM segmentation failed: batch=%d/%d expected=%d actual=%d fallback=%d",
                    batchIndex + 1,
                    batchCount,
                    requests.size,
                    splitRegions.size,
                    fallback.size,
                )
            return fallback
        }

        return requests.zip(splitRegions) { request, region ->
            TimedSpeech(
                text = request.text,
                audio = copyOfRange(region.startByte, region.endByte).trimPcmSilence()
                    .also { slicedAudio ->
                        Timber
                            .tag(TTS_LOG_TAG)
                            .i(
                                "Timed synthesis PCM slice: batch=%d/%d text=%s start=%.3f end=%.3f rawBytes=%d bytes=%d",
                                batchIndex + 1,
                                batchCount,
                                request.text,
                                region.startSeconds,
                                region.endSeconds,
                                region.endByte - region.startByte,
                                slicedAudio.size,
                            )
                    },
                sampleRate = SAMPLE_RATE,
            )
        }
    }

    private fun ByteArray.findPcmActiveRegions(sampleRate: Int): List<PcmRegion> {
        val frameBytes = (sampleRate * PCM_BYTES_PER_SAMPLE * PCM_VAD_FRAME_MILLIS / 1000)
            .coerceAtLeast(PCM_BYTES_PER_SAMPLE)
            .alignPcmOffset()
        val alignedSize = size.alignPcmOffset()
        if (alignedSize <= 0 || frameBytes <= 0) return emptyList()

        val frames = buildList {
            var start = 0
            while (start < alignedSize) {
                val end = (start + frameBytes).coerceAtMost(alignedSize).alignPcmOffset()
                if (start < end) {
                    add(
                        PcmEnergyFrame(
                            startByte = start,
                            endByte = end,
                            energy = averagePcmAmplitude(start, end),
                        )
                    )
                }
                start += frameBytes
            }
        }
        val peakEnergy = frames.maxOfOrNull { it.energy } ?: 0
        if (peakEnergy <= 0) return emptyList()

        val threshold = maxOf(
            PCM_ENERGY_MIN_THRESHOLD,
            (peakEnergy * PCM_ENERGY_THRESHOLD_RATIO).toInt(),
        )
        val minRegionBytes = (sampleRate * PCM_BYTES_PER_SAMPLE * PCM_ACTIVE_REGION_MIN_MILLIS / 1000)
            .alignPcmOffset()
        val mergeGapBytes = (sampleRate * PCM_BYTES_PER_SAMPLE * PCM_ACTIVE_REGION_MERGE_MILLIS / 1000)
            .alignPcmOffset()

        val rawRegions = mutableListOf<PcmRegion>()
        var regionStart: Int? = null
        frames.forEach { frame ->
            if (frame.energy >= threshold) {
                if (regionStart == null) regionStart = frame.startByte
            } else {
                val start = regionStart
                if (start != null) {
                    rawRegions += PcmRegion(startByte = start, endByte = frame.startByte)
                    regionStart = null
                }
            }
        }
        regionStart?.let { start ->
            rawRegions += PcmRegion(startByte = start, endByte = alignedSize)
        }

        val speechRegions = rawRegions
            .filter { it.endByte - it.startByte >= minRegionBytes }
            .mergeClosePcmRegions(maxGapBytes = mergeGapBytes)

        Timber
            .tag(TTS_LOG_TAG)
            .i(
                "Timed synthesis PCM energy scan: audioBytes=%d duration=%.3f frameMs=%d peak=%d threshold=%d rawRegions=%d mergedRegions=%d",
                size,
                pcmDurationSeconds(sampleRate = sampleRate),
                PCM_VAD_FRAME_MILLIS,
                peakEnergy,
                threshold,
                rawRegions.size,
                speechRegions.size,
            )
        return speechRegions
    }

    private fun ByteArray.averagePcmAmplitude(startByte: Int, endByte: Int): Int {
        var total = 0L
        var count = 0
        var offset = startByte.alignPcmOffset()
        val end = endByte.coerceAtMost(size).alignPcmOffset()
        while (offset + 1 < end) {
            total += pcmAmplitudeAt(offset)
            count++
            offset += PCM_BYTES_PER_SAMPLE
        }
        return if (count == 0) 0 else (total / count).toInt()
    }

    private fun List<PcmRegion>.mergeClosePcmRegions(maxGapBytes: Int): List<PcmRegion> {
        if (isEmpty()) return emptyList()
        val merged = mutableListOf<PcmRegion>()
        var current = first()
        drop(1).forEach { region ->
            if (region.startByte - current.endByte <= maxGapBytes) {
                current = current.copy(endByte = region.endByte)
            } else {
                merged += current
                current = region
            }
        }
        merged += current
        return merged
    }

    private fun List<PcmRegion>.splitIntoSpeechRegions(
        expectedCount: Int,
        audioSize: Int,
    ): List<PcmRegion> {
        if (expectedCount <= 0) return emptyList()
        if (expectedCount == 1) {
            val region = if (isEmpty()) {
                PcmRegion(startByte = 0, endByte = audioSize.alignPcmOffset())
            } else {
                PcmRegion(startByte = first().startByte, endByte = last().endByte)
            }
            return listOf(region.withPcmPadding(audioSize))
        }
        if (size >= expectedCount) {
            return splitByLargestPcmGaps(expectedCount = expectedCount, audioSize = audioSize)
        }
        return splitEvenlyByPcmDuration(expectedCount = expectedCount, audioSize = audioSize)
    }

    private fun List<PcmRegion>.splitByLargestPcmGaps(
        expectedCount: Int,
        audioSize: Int,
    ): List<PcmRegion> {
        val gapIndexes = zipWithNext()
            .mapIndexed { index, (left, right) ->
                PcmGap(index = index, bytes = right.startByte - left.endByte)
            }
            .sortedByDescending { it.bytes }
            .take(expectedCount - 1)
            .map { it.index }
            .sorted()

        if (gapIndexes.size != expectedCount - 1) {
            return splitEvenlyByPcmDuration(expectedCount = expectedCount, audioSize = audioSize)
        }

        val regions = mutableListOf<PcmRegion>()
        var segmentStartRegion = 0
        gapIndexes.forEach { gapIndex ->
            regions += PcmRegion(
                startByte = this[segmentStartRegion].startByte,
                endByte = this[gapIndex].endByte,
            ).withPcmPadding(audioSize)
            segmentStartRegion = gapIndex + 1
        }
        regions += PcmRegion(
            startByte = this[segmentStartRegion].startByte,
            endByte = last().endByte,
        ).withPcmPadding(audioSize)
        return regions
    }

    private fun List<PcmRegion>.splitEvenlyByPcmDuration(
        expectedCount: Int,
        audioSize: Int,
    ): List<PcmRegion> {
        val alignedAudioSize = audioSize.alignPcmOffset()
        val startByte = firstOrNull()?.startByte ?: 0
        val endByte = lastOrNull()?.endByte ?: alignedAudioSize
        val spanBytes = (endByte - startByte).coerceAtLeast(PCM_BYTES_PER_SAMPLE)
        return (0 until expectedCount).map { index ->
            val start = (startByte + spanBytes * index / expectedCount).alignPcmOffset()
            val end = (startByte + spanBytes * (index + 1) / expectedCount)
                .alignPcmOffset()
                .coerceAtLeast(start + PCM_BYTES_PER_SAMPLE)
                .coerceAtMost(alignedAudioSize)
            PcmRegion(startByte = start, endByte = end).withPcmPadding(audioSize)
        }
    }

    private fun PcmRegion.withPcmPadding(audioSize: Int): PcmRegion {
        return PcmRegion(
            startByte = (startByte - PCM_SEGMENT_PADDING_BYTES)
                .coerceAtLeast(0)
                .alignPcmOffset(),
            endByte = (endByte + PCM_SEGMENT_PADDING_BYTES)
                .coerceAtMost(audioSize)
                .alignPcmOffset(),
        )
    }

    private fun List<PcmRegion>.debugPcmRegionsForLog(): String {
        if (isEmpty()) return ""
        val regions = take(DEBUG_PCM_REGION_LIMIT)
            .joinToString(separator = "|") { region ->
                "%.3f-%.3f".format(region.startSeconds, region.endSeconds)
            }
        return if (size > DEBUG_PCM_REGION_LIMIT) "$regions|...(+${size - DEBUG_PCM_REGION_LIMIT})" else regions
    }

    private fun ByteArray.pcmDurationSeconds(sampleRate: Int): Double {
        return size.toDouble() / (sampleRate * PCM_BYTES_PER_SAMPLE)
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

    private data class PcmEnergyFrame(
        val startByte: Int,
        val endByte: Int,
        val energy: Int,
    )

    private data class PcmRegion(
        val startByte: Int,
        val endByte: Int,
    ) {
        val startSeconds: Double
            get() = startByte.toDouble() / (SAMPLE_RATE * PCM_BYTES_PER_SAMPLE)

        val endSeconds: Double
            get() = endByte.toDouble() / (SAMPLE_RATE * PCM_BYTES_PER_SAMPLE)
    }

    private data class PcmGap(
        val index: Int,
        val bytes: Int,
    )

    private companion object {
        const val HTTP_CHUNKED_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
        const val HTTP_SSE_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional/sse"
        const val TTS_LOG_TAG = "VolcengineTtsClient"
        const val CODE_OK = 20_000_000
        const val AUDIO_FORMAT_MP3 = "mp3"
        const val AUDIO_FORMAT_PCM = "pcm"
        const val SAMPLE_RATE = 24000
        const val PCM_BYTES_PER_SAMPLE = 2
        const val SILENCE_THRESHOLD = 256
        const val TRIM_PADDING_MILLIS = 20
        const val TRIM_PADDING_BYTES = SAMPLE_RATE * PCM_BYTES_PER_SAMPLE * TRIM_PADDING_MILLIS / 1000
        const val DEBUG_WORDS_RADIUS = 5
        const val DEBUG_WORDS_FULL_BATCH_RADIUS = 60
        const val TIMESTAMP_MILLIS_THRESHOLD = 1000
        const val LOG_CHUNK_SIZE = 3000
        const val LOG_RESPONSE_FRAMES = false
        const val PCM_VAD_FRAME_MILLIS = 20
        const val PCM_ENERGY_MIN_THRESHOLD = 160
        const val PCM_ENERGY_THRESHOLD_RATIO = 0.06
        const val PCM_ACTIVE_REGION_MIN_MILLIS = 40
        const val PCM_ACTIVE_REGION_MERGE_MILLIS = 80
        const val PCM_SEGMENT_PADDING_MILLIS = 80
        const val PCM_SEGMENT_PADDING_BYTES =
            SAMPLE_RATE * PCM_BYTES_PER_SAMPLE * PCM_SEGMENT_PADDING_MILLIS / 1000
        const val DEBUG_PCM_REGION_LIMIT = 24

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

        val SENTENCE_ENDINGS_ARRAY = charArrayOf(
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
