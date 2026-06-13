package com.github.deweyreed.timer.component.tts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import xyz.aprildown.timer.app.base.R
import xyz.aprildown.timer.domain.utils.Constants
import xyz.aprildown.timer.app.base.data.PreferenceData.cloudTtsSettings
import xyz.aprildown.timer.app.base.utils.ChineseNumberUtils
import xyz.aprildown.tools.helper.safeSharedPreference
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object TtsBakery {
    private const val SYNTHESIZE_TIMEOUT_MILLIS = 20_000L
    private const val COUNTDOWN_PRERENDER_NOTIFICATION_ID = Constants.NOTIF_ID_APP_INFO - 1
    private const val WAV_BITS_PER_SAMPLE = 16
    private const val WAV_CHANNELS = 1

    private val prerenderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prerenderJob: Job? = null
    private val mutableCountdownPrerenderState =
        MutableStateFlow<CountdownPrerenderState>(CountdownPrerenderState.Idle)

    val countdownPrerenderState: StateFlow<CountdownPrerenderState> =
        mutableCountdownPrerenderState.asStateFlow()

    sealed interface CountdownPrerenderState {
        data object Idle : CountdownPrerenderState

        data class Running(
            val current: Int,
            val total: Int,
        ) : CountdownPrerenderState

        data class Complete(
            val total: Int,
            val success: Int,
            val failed: Int,
        ) : CountdownPrerenderState

        data object FailedToStart : CountdownPrerenderState
    }

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

    fun startCountdownPrerendering(context: Context, count: Int): Boolean {
        if (prerenderJob?.isActive == true) return false

        val appContext = context.applicationContext
        val texts = (count downTo 1).map { value -> countdownSpeechText(value.toString()) }
        mutableCountdownPrerenderState.value = CountdownPrerenderState.Running(
            current = 0,
            total = texts.size,
        )
        notifyCountdownPrerenderProgress(appContext, current = 0, total = texts.size)
        prerenderJob = prerenderScope.launch {
            bakeMultipleImmediately(
                context = appContext,
                texts = texts,
            ) { current, total ->
                mutableCountdownPrerenderState.value = CountdownPrerenderState.Running(
                    current = current,
                    total = total,
                )
                notifyCountdownPrerenderProgress(appContext, current = current, total = total)
            }.onSuccess { result ->
                mutableCountdownPrerenderState.value = CountdownPrerenderState.Complete(
                    total = result.total,
                    success = result.success,
                    failed = result.failed,
                )
                cancelCountdownPrerenderNotification(appContext)
            }.onFailure {
                mutableCountdownPrerenderState.value = CountdownPrerenderState.FailedToStart
                cancelCountdownPrerenderNotification(appContext)
            }
        }
        return true
    }

    private fun notifyCountdownPrerenderProgress(context: Context, current: Int, total: Int) {
        if (!context.canPostNotifications()) return

        val notificationManager = NotificationManagerCompat.from(context)
        notificationManager.createAppInfoChannelIfNecessary(context)
        notificationManager.notify(
            COUNTDOWN_PRERENDER_NOTIFICATION_ID,
            NotificationCompat.Builder(context, Constants.CHANNEL_APP_INFO_NOTIFICATION)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.pref_tts_prerender_notification_title))
                .setContentText(
                    context.getString(
                        R.string.pref_tts_prerender_notification_progress,
                        current,
                        total,
                    )
                )
                .setProgress(total, current, total == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
    }

    private fun cancelCountdownPrerenderNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(COUNTDOWN_PRERENDER_NOTIFICATION_ID)
    }

    private fun NotificationManagerCompat.createAppInfoChannelIfNecessary(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (getNotificationChannel(Constants.CHANNEL_APP_INFO_NOTIFICATION) != null) return

        createNotificationChannel(
            NotificationChannel(
                Constants.CHANNEL_APP_INFO_NOTIFICATION,
                context.getString(R.string.notif_channel_app_info_title),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_app_info_desp)
                setSound(null, null)
            }
        )
    }

    private fun Context.canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
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
        val cloudTtsSettings = context.safeSharedPreference.cloudTtsSettings
        val cloudTtsClient = cloudTtsSettings
            .takeIf { it.isConfigured }
            ?.let(::VolcengineTtsClient)

        if (cloudTtsClient != null) {
            return try {
                Result.success(
                    bakeCloudMultipleImmediately(
                        context = context,
                        texts = texts,
                        cloudTtsClient = cloudTtsClient,
                        onProgress = onProgress,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

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
                    synthesizeToFile(checkNotNull(tts), text, file)
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

    private suspend fun bakeCloudMultipleImmediately(
        context: Context,
        texts: List<String>,
        cloudTtsClient: VolcengineTtsClient,
        onProgress: ((current: Int, total: Int) -> Unit)?,
    ): BatchBakeResult {
        var successCount = 0
        var failedCount = 0
        val pendingTexts = mutableListOf<String>()

        texts.forEach { text ->
            if (text.isBlank() || TtsBakeryDiskCache.get(context, text) != null) {
                successCount++
                onProgress?.invoke(successCount + failedCount, texts.size)
            } else {
                pendingTexts += text
            }
        }

        if (pendingTexts.isNotEmpty()) {
            runCatching {
                cloudTtsClient.synthesizeTimedSpeech(pendingTexts)
            }.onSuccess { timedSpeechList ->
                pendingTexts.forEachIndexed { index, text ->
                    runCatching {
                        val timedSpeech = timedSpeechList.getOrNull(index)
                            ?: error("Missing synthesized audio for $text")
                        val file = createTempSpeechFile(context)
                        file.writeWav(
                            pcmAudio = timedSpeech.audio,
                            sampleRate = timedSpeech.sampleRate,
                        )
                        TtsBakeryDiskCache.put(context, text, file)
                    }.onSuccess {
                        successCount++
                    }.onFailure { error ->
                        failedCount++
                        Timber.e(error, "Failed to cache cloud TTS text: %s", text)
                    }
                    onProgress?.invoke(successCount + failedCount, texts.size)
                }
            }.onFailure { error ->
                if (error is CancellationException && error !is TimeoutCancellationException) {
                    throw error
                }
                pendingTexts.forEach { text ->
                    failedCount++
                    Timber.e(error, "Failed to bake cloud TTS text: %s", text)
                    onProgress?.invoke(successCount + failedCount, texts.size)
                }
            }
        }

        return BatchBakeResult(
            total = texts.size,
            success = successCount,
            failed = failedCount,
        )
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

    private fun File.writeWav(
        pcmAudio: ByteArray,
        sampleRate: Int,
    ) {
        FileOutputStream(this).use { output ->
            output.write(
                ByteBuffer.allocate(44)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".encodeToByteArray())
                    .putInt(36 + pcmAudio.size)
                    .put("WAVE".encodeToByteArray())
                    .put("fmt ".encodeToByteArray())
                    .putInt(16)
                    .putShort(1)
                    .putShort(WAV_CHANNELS.toShort())
                    .putInt(sampleRate)
                    .putInt(sampleRate * WAV_CHANNELS * WAV_BITS_PER_SAMPLE / 8)
                    .putShort((WAV_CHANNELS * WAV_BITS_PER_SAMPLE / 8).toShort())
                    .putShort(WAV_BITS_PER_SAMPLE.toShort())
                    .put("data".encodeToByteArray())
                    .putInt(pcmAudio.size)
                    .array()
            )
            output.write(pcmAudio)
        }
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

    fun countdownSpeechText(text: String): String {
        return if (ChineseNumberUtils.isChineseLocale() && text.all(Char::isDigit)) {
            ChineseNumberUtils.toChineseDigits(text.toInt())
        } else {
            text
        }
    }
}
