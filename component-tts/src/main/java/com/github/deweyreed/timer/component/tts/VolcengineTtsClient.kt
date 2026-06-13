package com.github.deweyreed.timer.component.tts

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import timber.log.Timber
import xyz.aprildown.timer.app.base.data.PreferenceData.CloudTtsSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class VolcengineTtsClient(
    private val settings: CloudTtsSettings,
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun synthesizeToFile(context: Context, text: String): File = withContext(Dispatchers.IO) {
        val audio = synthesize(text)
        createTempCloudSpeechFile(context).also { file ->
            file.writeBytes(audio)
        }
    }

    private suspend fun synthesize(text: String): ByteArray {
        val result = CompletableDeferred<Result<ByteArray>>()
        val audioOutput = ByteArrayOutputStream()
        val sessionId = UUID.randomUUID().toString()

        val request = Request.Builder()
            .url(URL)
            .header("X-Api-Key", settings.apiKey)
            .header("X-Api-Resource-Id", settings.resourceId)
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .build()

        var webSocket: WebSocket? = null
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(RequestFrame.connection(Event.START_CONNECTION))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching {
                    handleFrame(
                        frame = ResponseFrame.parse(bytes.toByteArray()),
                        webSocket = webSocket,
                        sessionId = sessionId,
                        text = text,
                        audioOutput = audioOutput,
                        result = result,
                    )
                }.onFailure { error ->
                    result.complete(Result.failure(error))
                    webSocket.cancel()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                result.complete(Result.failure(IllegalStateException(text)))
                webSocket.cancel()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result.complete(Result.failure(t))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!result.isCompleted) {
                    result.complete(Result.failure(IllegalStateException("WebSocket closed: $code $reason")))
                }
            }
        }

        return try {
            webSocket = okHttpClient.newWebSocket(request, listener)
            withTimeout(SYNTHESIZE_TIMEOUT_MILLIS) {
                result.await().getOrThrow()
            }
        } catch (e: CancellationException) {
            webSocket?.cancel()
            throw e
        } catch (e: TimeoutCancellationException) {
            webSocket?.cancel()
            throw e
        } finally {
            webSocket?.close(1000, null)
        }
    }

    private fun handleFrame(
        frame: ResponseFrame,
        webSocket: WebSocket,
        sessionId: String,
        text: String,
        audioOutput: ByteArrayOutputStream,
        result: CompletableDeferred<Result<ByteArray>>,
    ) {
        when (frame.event) {
            Event.CONNECTION_STARTED -> {
                webSocket.send(RequestFrame.session(Event.START_SESSION, sessionId, startSessionPayload()))
            }
            Event.SESSION_STARTED -> {
                webSocket.send(RequestFrame.session(Event.TASK_REQUEST, sessionId, taskRequestPayload(text)))
                webSocket.send(RequestFrame.session(Event.FINISH_SESSION, sessionId))
            }
            Event.TTS_RESPONSE -> {
                audioOutput.write(frame.payload)
            }
            Event.SESSION_FINISHED -> {
                result.complete(Result.success(audioOutput.toByteArray()))
                webSocket.send(RequestFrame.connection(Event.FINISH_CONNECTION))
            }
            Event.CONNECTION_FAILED,
            Event.SESSION_FAILED -> {
                result.complete(Result.failure(IllegalStateException(frame.payloadText())))
                webSocket.cancel()
            }
            else -> Unit
        }

        if (frame.messageType == MessageType.ERROR) {
            result.complete(Result.failure(IllegalStateException(frame.payloadText())))
            webSocket.cancel()
        }
    }

    private fun startSessionPayload(): String {
        return moshiMapAdapter.toJson(
            mapOf(
                "user" to mapOf("uid" to "timer-machine-android"),
                "event" to Event.START_SESSION,
                "namespace" to "BidirectionalTTS",
                "req_params" to mapOf(
                    "speaker" to settings.speaker,
                    "audio_params" to mapOf(
                        "format" to "mp3",
                        "sample_rate" to 24000,
                        "bit_rate" to 64000,
                        "speech_rate" to settings.speechRate.coerceIn(-50, 100),
                    ),
                ),
            )
        )
    }

    private fun taskRequestPayload(text: String): String {
        return moshiMapAdapter.toJson(
            mapOf(
                "event" to Event.TASK_REQUEST,
                "namespace" to "BidirectionalTTS",
                "req_params" to mapOf(
                    "text" to text,
                ),
            )
        )
    }

    private fun createTempCloudSpeechFile(context: Context): File {
        val folder = File(context.cacheDir, "tts-cloud-temp")
        folder.mkdirs()
        return File(folder, "${UUID.randomUUID()}.mp3")
    }

    private object RequestFrame {
        private const val HEADER_FULL_CLIENT_JSON_WITH_EVENT = 0x11
        private const val MESSAGE_FULL_CLIENT_WITH_EVENT = 0x14
        private const val SERIALIZATION_JSON_NO_COMPRESSION = 0x10
        private const val RESERVED = 0x00

        fun connection(event: Int): ByteString = build(
            event = event,
            sessionId = null,
            payload = "{}",
        )

        fun session(event: Int, sessionId: String, payload: String = "{}"): ByteString = build(
            event = event,
            sessionId = sessionId,
            payload = payload,
        )

        private fun build(event: Int, sessionId: String?, payload: String): ByteString {
            val payloadBytes = payload.encodeToByteArray()
            val sessionIdBytes = sessionId?.encodeToByteArray()
            val size = 8 + (sessionIdBytes?.let { 4 + it.size } ?: 0) + payloadBytes.size
            val buffer = ByteBuffer.allocate(4 + size).order(ByteOrder.BIG_ENDIAN)
            buffer.put(HEADER_FULL_CLIENT_JSON_WITH_EVENT.toByte())
            buffer.put(MESSAGE_FULL_CLIENT_WITH_EVENT.toByte())
            buffer.put(SERIALIZATION_JSON_NO_COMPRESSION.toByte())
            buffer.put(RESERVED.toByte())
            buffer.putInt(event)
            if (sessionIdBytes != null) {
                buffer.putInt(sessionIdBytes.size)
                buffer.put(sessionIdBytes)
            }
            buffer.putInt(payloadBytes.size)
            buffer.put(payloadBytes)
            return ByteString.of(*buffer.array())
        }
    }

    private data class ResponseFrame(
        val messageType: Int,
        val event: Int?,
        val payload: ByteArray,
    ) {
        fun payloadText(): String = payload.decodeToString()

        companion object {
            fun parse(bytes: ByteArray): ResponseFrame {
                if (bytes.size < 8) error("Invalid TTS response frame")
                val messageType = (bytes[1].toInt() ushr 4) and 0x0F
                val flags = bytes[1].toInt() and 0x0F
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                buffer.position(4)

                if (messageType == MessageType.ERROR) {
                    val errorCode = buffer.int
                    val payload = ByteArray(buffer.remaining())
                    buffer.get(payload)
                    Timber.e("Volcengine TTS error frame: %s", errorCode)
                    return ResponseFrame(messageType = messageType, event = null, payload = payload)
                }

                val event = if (flags and 0x04 != 0) buffer.int else null
                if (event in EVENTS_WITH_ID) {
                    val idLength = buffer.int
                    buffer.position(buffer.position() + idLength)
                }
                val payloadLength = buffer.int
                val payload = ByteArray(payloadLength)
                buffer.get(payload)
                return ResponseFrame(messageType = messageType, event = event, payload = payload)
            }
        }
    }

    private object MessageType {
        const val ERROR = 0x0F
    }

    private object Event {
        const val START_CONNECTION = 1
        const val FINISH_CONNECTION = 2
        const val CONNECTION_STARTED = 50
        const val CONNECTION_FAILED = 51
        const val START_SESSION = 100
        const val FINISH_SESSION = 102
        const val SESSION_STARTED = 150
        const val SESSION_FINISHED = 152
        const val SESSION_FAILED = 153
        const val TASK_REQUEST = 200
        const val TTS_SENTENCE_START = 350
        const val TTS_SENTENCE_END = 351
        const val TTS_RESPONSE = 352
    }

    private companion object {
        const val URL = "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
        const val SYNTHESIZE_TIMEOUT_MILLIS = 30_000L

        val EVENTS_WITH_ID = setOf(
            Event.CONNECTION_STARTED,
            Event.CONNECTION_FAILED,
            Event.SESSION_STARTED,
            Event.SESSION_FINISHED,
            Event.SESSION_FAILED,
            Event.TTS_SENTENCE_START,
            Event.TTS_SENTENCE_END,
            Event.TTS_RESPONSE,
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
