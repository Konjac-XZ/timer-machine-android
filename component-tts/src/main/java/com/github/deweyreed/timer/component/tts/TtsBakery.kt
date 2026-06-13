package com.github.deweyreed.timer.component.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object TtsBakery {
    private const val SYNTHESIZE_TIMEOUT_MILLIS = 20_000L

    data class BatchBakeResult(
        val total: Int,
        val success: Int,
        val failed: Int,
    )

    fun getSpeechFile(context: Context, text: String): File? {
        return TtsBakeryDiskCache.get(context, text)
    }

    fun scheduleBaking(context: Context, text: String) {
        if (text.isBlank()) return
        WorkManager.getInstance(context)
            .enqueue(
                OneTimeWorkRequest.Builder(TtsBakeryWorker::class.java)
                    .setInputData(TtsBakeryWorker.getData(text))
                    .setConstraints(
                        Constraints(
                            requiredNetworkType = NetworkType.CONNECTED,
                            requiresBatteryNotLow = true,
                            requiresStorageNotLow = true,
                        )
                    )
                    .build()
            )
    }

    suspend fun bakeMultipleImmediately(
        context: Context,
        texts: List<String>,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Result<BatchBakeResult> {
        if (texts.isEmpty()) {
            return Result.success(BatchBakeResult(total = 0, success = 0, failed = 0))
        }

        var tts: TextToSpeech? = null
        var successCount = 0
        var failedCount = 0

        return try {
            tts = createTextToSpeech(context)
            texts.forEachIndexed { index, text ->
                val current = index + 1
                if (text.isBlank() || TtsBakeryDiskCache.get(context, text) != null) {
                    successCount++
                    onProgress?.invoke(current, texts.size)
                    return@forEachIndexed
                }

                runCatching {
                    val file = createTempSpeechFile(context)
                    synthesizeToFile(tts, text, file)
                    TtsBakeryDiskCache.put(context, text, file)
                }.onSuccess {
                    successCount++
                }.onFailure { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) {
                        throw error
                    }
                    failedCount++
                    Timber.e(error, "Failed to bake TTS text: %s", text)
                }
                onProgress?.invoke(current, texts.size)
            }

            Result.success(
                BatchBakeResult(
                    total = texts.size,
                    success = successCount,
                    failed = failedCount,
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tts?.run {
                stop()
                shutdown()
            }
        }
    }

    private suspend fun createTextToSpeech(context: Context): TextToSpeech {
        return suspendCancellableCoroutine { cont ->
            var tts: TextToSpeech? = null
            val resumed = AtomicBoolean(false)
            tts = TextToSpeech(context) { status ->
                if (!resumed.compareAndSet(false, true)) return@TextToSpeech
                if (status == TextToSpeech.SUCCESS) {
                    val initializedTts = tts
                    if (initializedTts != null) {
                        cont.resume(initializedTts)
                    } else {
                        cont.resumeWithException(IllegalStateException("TTS initialized before assignment"))
                    }
                } else {
                    tts?.shutdown()
                    cont.resumeWithException(IllegalStateException("TTS initialization failed: $status"))
                }
            }
            cont.invokeOnCancellation {
                tts?.shutdown()
            }
        }
    }

    private fun createTempSpeechFile(context: Context): File {
        val folder = File(context.cacheDir, "tts-bakery-temp")
        folder.mkdirs()
        return File(folder, UUID.randomUUID().toString())
    }

    private suspend fun synthesizeToFile(tts: TextToSpeech, text: String, file: File) {
        withTimeout(SYNTHESIZE_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { cont ->
                val utteranceId = UUID.randomUUID().toString()
                val resumed = AtomicBoolean(false)
                tts.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(doneUtteranceId: String?) {
                            if (doneUtteranceId == utteranceId && resumed.compareAndSet(false, true)) {
                                cont.resume(Unit)
                            }
                        }

                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onError(errorUtteranceId: String?) {
                            onError(errorUtteranceId, TextToSpeech.ERROR)
                        }

                        override fun onError(errorUtteranceId: String?, errorCode: Int) {
                            if (errorUtteranceId == utteranceId && resumed.compareAndSet(false, true)) {
                                cont.resumeWithException(
                                    IllegalStateException("TTS error for $text: $errorCode")
                                )
                            }
                        }
                    }
                )
                val result = tts.synthesizeToFile(text, Bundle.EMPTY, file, utteranceId)
                if (result == TextToSpeech.ERROR && resumed.compareAndSet(false, true)) {
                    cont.resumeWithException(IllegalStateException("TTS rejected synthesis for $text"))
                }
                cont.invokeOnCancellation {
                    tts.stop()
                }
            }
        }
    }

    fun tearDown(context: Context) {
        TtsBakeryDiskCache.deleteAll(context)
    }
}
