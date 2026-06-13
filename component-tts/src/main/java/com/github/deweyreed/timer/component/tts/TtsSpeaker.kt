package com.github.deweyreed.timer.component.tts

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.format.DateUtils
import android.util.Log
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.core.os.postDelayed
import com.github.deweyreed.timer.component.tts.TtsSpeaker.onDone
import com.github.deweyreed.tools.anko.longToast
import com.github.deweyreed.tools.anko.toast
import com.github.deweyreed.tools.helper.HandlerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import xyz.aprildown.timer.app.base.R
import xyz.aprildown.timer.app.base.data.PreferenceData
import xyz.aprildown.timer.app.base.data.PreferenceData.isTtsBakeryOpen
import xyz.aprildown.timer.app.base.data.PreferenceData.storedAudioFocusType
import xyz.aprildown.timer.app.base.data.PreferenceData.storedAudioTypeValue
import xyz.aprildown.timer.app.base.data.PreferenceData.useBakedCount
import xyz.aprildown.timer.app.base.media.AudioFocusManager
import xyz.aprildown.timer.app.base.media.RingtonePreviewKlaxon
import xyz.aprildown.timer.app.base.media.getMediaDuration
import xyz.aprildown.timer.domain.utils.fireAndForget
import xyz.aprildown.tools.helper.safeSharedPreference
import java.io.File

object TtsSpeaker : WelcomingTextToSpeech.Listener, AudioManager.OnAudioFocusChangeListener {

    private var application: Application? = null

    private var textToSpeech: WelcomingTextToSpeech? = null

    private var oneShot = false
    private var onDone: (() -> Unit)? = null

    private var audioManager: AudioManager? = null
    private var audioManagerCleanHandler: Handler? = null

    private var nullableCleanHandler: Handler? = null
    private val cleanHandler: Handler
        get() {
            var nch = nullableCleanHandler
            if (nch == null) {
                nch = Handler(Looper.getMainLooper())
                nullableCleanHandler = nch
            }
            return nch
        }

    private fun warmUp(context: Context) {
        var application = application
        if (application == null) {
            application = context.applicationContext as Application
            this.application = application
        }

        cancelScheduledClean()

        var tts = textToSpeech
        if (tts == null) {
            tts = WelcomingTextToSpeech(application = application, listener = this)
            textToSpeech = tts
        }
    }

    fun speak(
        context: Context,
        text: CharSequence,
        oneShot: Boolean,
        onDone: (() -> Unit)? = null
    ) {
        warmUp(context)
        Log.i(
            COUNTDOWN_TTS_LOG_TAG,
            "TtsSpeaker.speak request text=${text.toLogText()} oneShot=$oneShot hasOnDone=${onDone != null}"
        )

        if (text.isNotBlank()) {
            this.oneShot = oneShot
            this.onDone = onDone

            checkNotNull(textToSpeech).speak(text, checkNotNull(application).storedAudioTypeValue)
        } else {
            Log.i(COUNTDOWN_TTS_LOG_TAG, "TtsSpeaker.speak ignored blank text")
        }
    }

    fun stopCurrentSpeaking() {
        Log.i(COUNTDOWN_TTS_LOG_TAG, "TtsSpeaker.stopCurrentSpeaking")
        textToSpeech?.stop()

        oneShot = false
        onDone = null

        abandonAudioFocus()

        scheduleClean()
    }

    override fun onError(errorCode: Int) {
        Log.e(COUNTDOWN_TTS_LOG_TAG, "TtsSpeaker.onError errorCode=$errorCode")
        application?.run {
            longToast(getString(R.string.tts_error_template, errorCode.toString()))
        }
        onDone?.invoke()

        textToSpeech?.run {
            stop()
            textToSpeech = null
        }
        oneShot = false
        onDone = null
        application = null

        abandonAudioFocus()
    }

    override fun onStart() {
        Log.i(COUNTDOWN_TTS_LOG_TAG, "TtsSpeaker.onStart audioManagerExists=${audioManager != null}")
        if (audioManager != null) return
        val context = application ?: return
        requestAudioFocus(
            context = context,
            audioFocusType = context.storedAudioFocusType,
            streamType = context.storedAudioTypeValue,
        )
    }

    /**
     * When we use baked count, only [onStart] is called to request the audio focus.
     * onDone isn't called, but it's okay because
     * 1. It's not [oneShot]. 2. It has no [onDone] action. 3. We call [scheduleClean] eventually.
     */
    override fun onDone() {
        Log.i(
            COUNTDOWN_TTS_LOG_TAG,
            "TtsSpeaker.onDone oneShot=$oneShot hasOnDone=${onDone != null}"
        )
        if (oneShot) {
            oneShot = false

            abandonAudioFocus()
        }

        onDone?.invoke()
        onDone = null

        scheduleClean()
    }

    private fun requestAudioFocus(
        context: Context,
        audioFocusType: Int,
        streamType: Int
    ) {
        var am = audioManager
        if (am == null) {
            am = context.getSystemService() ?: return
            audioManager = am
        }
        audioManagerCleanHandler?.removeCallbacksAndMessages(null)
        audioManagerCleanHandler = null
        AudioFocusManager.requestAudioFocus(
            audioManager = am,
            focusGain = audioFocusType,
            streamType = streamType,
            listener = this,
        )
    }

    private fun abandonAudioFocus() {
        var handler = audioManagerCleanHandler
        if (handler == null) {
            handler = Handler(Looper.getMainLooper())
            audioManagerCleanHandler = handler
        } else {
            handler.removeCallbacksAndMessages(null)
        }

        handler.postDelayed(500) {
            val am = audioManager ?: return@postDelayed
            AudioFocusManager.abandonAudioFocus(audioManager = am, listener = this)
            audioManager = null
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        Log.i(COUNTDOWN_TTS_LOG_TAG, "TtsSpeaker.focus focusChange=$focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                textToSpeech?.stop()
            }
        }
    }

    private fun scheduleClean() {
        if (textToSpeech == null && onDone == null && audioManager == null) return
        cancelScheduledClean()
        cleanHandler.postDelayed(DateUtils.MINUTE_IN_MILLIS) {
            clean()
        }
    }

    fun clean() {
        cancelScheduledClean()

        textToSpeech?.run {
            stop()
            shutdown()
            textToSpeech = null
        }
        oneShot = false
        onDone = null
        application = null

        abandonAudioFocus()

        nullableCleanHandler = null
    }

    private fun cancelScheduledClean() {
        if (nullableCleanHandler == null) return
        cleanHandler.removeCallbacksAndMessages(null)
    }
}

private class WelcomingTextToSpeech(
    private val application: Application,
    private val listener: Listener,
) : TextToSpeech.OnInitListener {

    interface Listener {
        fun onError(errorCode: Int)
        fun onStart()
        fun onDone()
    }

    private val textToSpeech = TextToSpeech(application, this).also { tts ->
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    HandlerHelper.runOnUiThread(listener::onStart)
                }

                override fun onDone(utteranceId: String?) {
                    HandlerHelper.runOnUiThread(listener::onDone)
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    onErrorCompat(TextToSpeech.ERROR)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onErrorCompat(errorCode)
                }

                private fun onErrorCompat(errorCode: Int) {
                    HandlerHelper.runOnUiThread {
                        listener.onError(errorCode)
                    }
                }
            }
        )
    }

    private var initialized = false
    private var pendingText: CharSequence? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speakToken = 0L
    private var cachedDoneRunnable: Runnable? = null
    private var lastLocalCountdownToastNumber: Int? = null
    private var lastLocalCountdownToastAt: Long = 0L

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            // onInit may be called in the constructor of TextToSpeech
            HandlerHelper.post {
                stop()
                listener.onError(status)
            }
            return
        }
        initialized = true

        val currentPendingText = pendingText
        if (currentPendingText != null) {
            speak(currentPendingText)
        }
    }

    fun speak(text: CharSequence, streamType: Int = AudioManager.STREAM_MUSIC) {
        if (text.isBlank()) {
            Log.i(COUNTDOWN_TTS_LOG_TAG, "WelcomingTts.speak ignored blank text")
            return
        }

        fireAndForget(Dispatchers.Main.immediate) {
            val currentSpeakToken = ++speakToken
            clearCachedDoneCallback()
            val isTtsBakeryOpen = application.safeSharedPreference.isTtsBakeryOpen
            val textString = text.toString()
            val speechText = TtsBakery.countdownSpeechText(textString)
            Log.i(
                COUNTDOWN_TTS_LOG_TAG,
                "WelcomingTts.speak begin text=${textString.toLogText()} speechText=${speechText.toLogText()} " +
                    "stream=$streamType initialized=$initialized"
            )

            val speechSource = withContext(Dispatchers.IO) {
                resolveSpeechSource(
                    context = application,
                    originalText = textString,
                    speechText = speechText,
                    isTtsBakeryOpen = isTtsBakeryOpen,
                )
            }
            if (currentSpeakToken != speakToken) {
                Log.i(
                    COUNTDOWN_TTS_LOG_TAG,
                    "WelcomingTts.speak stale text=${textString.toLogText()} token=$currentSpeakToken"
                )
                return@fireAndForget
            }
            Timber
                .tag(TTS_LOG_TAG)
                .i(
                    "Countdown TTS source=%s reason=%s userReason=%s text=%s speechText=%s bakeryOpen=%s",
                    speechSource.sourceName,
                    speechSource.reason,
                    speechSource.userReason,
                    textString,
                    speechText,
                    isTtsBakeryOpen,
                )

            val speechUri = speechSource.uri
            if (speechUri != null) {
                val duration = speechUri.getMediaDuration(application)
                Log.i(
                    COUNTDOWN_TTS_LOG_TAG,
                    "WelcomingTts.cache PLAY text=${textString.toLogText()} uri=$speechUri " +
                        "durationMs=$duration initialized=$initialized isSpeaking=${if (initialized) textToSpeech.isSpeaking else null}"
                )
                if (initialized) {
                    if (textToSpeech.isSpeaking) {
                        Log.i(COUNTDOWN_TTS_LOG_TAG, "WelcomingTts.cache stop local TTS before cached playback")
                        textToSpeech.stop()
                    }
                }

                RingtonePreviewKlaxon.start(
                    context = application,
                    uri = speechUri,
                    loop = false,
                    audioFocusType = COUNTDOWN_CACHE_AUDIO_FOCUS_TYPE,
                    streamType = streamType,
                    onComplete = {
                        Log.i(
                            COUNTDOWN_TTS_LOG_TAG,
                            "WelcomingTts.cache complete text=${textString.toLogText()}"
                        )
                        if (currentSpeakToken != speakToken) return@start
                        clearCachedDoneCallback()
                        listener.onDone()
                    }
                )

                val doneDelay = duration + CACHE_PLAYBACK_DONE_FALLBACK_MS
                val doneRunnable = Runnable {
                    if (currentSpeakToken != speakToken) return@Runnable
                    Log.i(
                        COUNTDOWN_TTS_LOG_TAG,
                        "WelcomingTts.cache fallback onDone text=${textString.toLogText()}"
                    )
                    cachedDoneRunnable = null
                    listener.onDone()
                }
                cachedDoneRunnable = doneRunnable
                Log.i(
                    COUNTDOWN_TTS_LOG_TAG,
                    "WelcomingTts.cache schedule fallback onDone text=${textString.toLogText()} " +
                        "delayMs=$doneDelay"
                )
                mainHandler.postDelayed(doneRunnable, doneDelay)

                return@fireAndForget
            }

            if (!initialized) {
                Log.i(COUNTDOWN_TTS_LOG_TAG, "WelcomingTts.local pending text=${textString.toLogText()}")
                pendingText = text
                return@fireAndForget
            }

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(
                    when (streamType) {
                        AudioManager.STREAM_ALARM -> AudioAttributes.USAGE_ALARM
                        AudioManager.STREAM_NOTIFICATION -> AudioAttributes.USAGE_NOTIFICATION
                        AudioManager.STREAM_RING -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                        else -> AudioAttributes.USAGE_MEDIA
                    }
                )
                .build()

            textToSpeech.setAudioAttributes(audioAttributes)

            Log.i(
                COUNTDOWN_TTS_LOG_TAG,
                "WelcomingTts.local SPEAK text=${textString.toLogText()} speechText=${speechText.toLogText()}"
            )
            if (shouldShowLocalReasonToast(textString)) {
                application.toast(
                    application.getString(
                        R.string.tts_local_reason_template,
                        speechSource.userReason,
                    )
                )
            }
            textToSpeech.speak(
                speechText,
                TextToSpeech.QUEUE_FLUSH,
                null,
                speechText.hashCode().toString()
            )
            if (isTtsBakeryOpen) {
                TtsBakery.scheduleBaking(application, speechText)
            }
        }
    }

    fun stop() {
        Log.i(COUNTDOWN_TTS_LOG_TAG, "WelcomingTts.stop")
        speakToken += 1
        clearCachedDoneCallback()
        textToSpeech.stop()
        HandlerHelper.remove(listener::onDone)
    }

    fun shutdown() {
        Log.i(COUNTDOWN_TTS_LOG_TAG, "WelcomingTts.shutdown")
        speakToken += 1
        clearCachedDoneCallback()
        textToSpeech.shutdown()
        HandlerHelper.remove(listener::onDone)
    }

    private fun clearCachedDoneCallback() {
        cachedDoneRunnable?.let(mainHandler::removeCallbacks)
        cachedDoneRunnable = null
    }

    private fun shouldShowLocalReasonToast(text: String): Boolean {
        val number = text.toCountdownNumberOrNull() ?: return false
        val now = System.currentTimeMillis()
        val lastNumber = lastLocalCountdownToastNumber
        val isSameCountdown = lastNumber != null &&
            number == lastNumber - 1 &&
            now - lastLocalCountdownToastAt <= COUNTDOWN_TOAST_SEQUENCE_GAP_MS
        lastLocalCountdownToastNumber = number
        lastLocalCountdownToastAt = now
        return !isSameCountdown
    }
}

private data class SpeechSource(
    val uri: Uri?,
    val sourceName: String,
    val reason: String,
    val userReason: String,
)

private fun resolveSpeechSource(
    context: Context,
    originalText: String,
    speechText: String,
    isTtsBakeryOpen: Boolean,
): SpeechSource {
    val reasons = mutableListOf<String>()
    val userReasons = mutableListOf<String>()
    if (isTtsBakeryOpen) {
        reasons += "ttsBakeryOpen=true"

        val originalCacheResult = TtsBakery.getSpeechFileWithStatus(context, originalText)
        originalCacheResult.toReasonPart("originalText")?.let(reasons::add)
        originalCacheResult.toUserReason(context, R.string.tts_local_reason_original_cache_invalid)
            ?.let(userReasons::add)
        originalCacheResult.file?.let { originalCacheFile ->
            return SpeechSource(
                uri = originalCacheFile.toUri(),
                sourceName = "cloud-cache",
                reason = (reasons + "originalTextCacheHit=true").joinToString(),
                userReason = context.getString(R.string.tts_cache_hit_original),
            )
        }

        if (speechText != originalText) {
            val speechCacheResult = TtsBakery.getSpeechFileWithStatus(context, speechText)
            speechCacheResult.toReasonPart("speechText")?.let(reasons::add)
            speechCacheResult.toUserReason(context, R.string.tts_local_reason_speech_cache_invalid)
                ?.let(userReasons::add)
            speechCacheResult.file?.let { speechCacheFile ->
                return SpeechSource(
                    uri = speechCacheFile.toUri(),
                    sourceName = "cloud-cache",
                    reason = (reasons + "speechTextCacheHit=true").joinToString(),
                    userReason = context.getString(R.string.tts_cache_hit_speech),
                )
            }
        } else {
            reasons += "speechTextSameAsOriginal=true"
        }
    } else {
        reasons += "ttsBakeryOpen=false"
        userReasons += context.getString(R.string.tts_local_reason_cache_disabled)
    }

    val bakedCountSource = getBakedCountSource(context = context, content = originalText)
    bakedCountSource.reason?.let(reasons::add)
    bakedCountSource.uri?.let { bakedCountUri ->
        return SpeechSource(
            uri = bakedCountUri,
            sourceName = "baked-count",
            reason = (reasons + "builtInCountAudioHit=true").joinToString(),
            userReason = context.getString(R.string.tts_baked_count_hit),
        )
    }
    bakedCountSource.userReason?.let(userReasons::add)

    if (isTtsBakeryOpen &&
        userReasons.none {
            it == context.getString(R.string.tts_local_reason_original_cache_invalid) ||
                it == context.getString(R.string.tts_local_reason_speech_cache_invalid)
        }
    ) {
        userReasons += context.getString(R.string.tts_local_reason_cache_missing)
    }

    return SpeechSource(
        uri = null,
        sourceName = "local-tts",
        reason = (reasons + "source=local-tts").joinToString(),
        userReason = userReasons.distinct().joinToString(
            separator = context.getString(R.string.tts_local_reason_separator),
        ),
    )
}

private fun TtsBakeryDiskCache.LookupResult.toReasonPart(name: String): String? {
    return when (status) {
        TtsBakeryDiskCache.LookupStatus.Hit -> "$name.cacheHit=true"
        TtsBakeryDiskCache.LookupStatus.Miss -> "$name.cacheMiss=true"
        TtsBakeryDiskCache.LookupStatus.Invalid -> "$name.cacheInvalid=true"
        TtsBakeryDiskCache.LookupStatus.Error ->
            "$name.cacheReadError=${errorMessage.orEmpty().toLogValue()}"
    }
}

private fun TtsBakeryDiskCache.LookupResult.toUserReason(
    context: Context,
    invalidReasonRes: Int,
): String? {
    return when (status) {
        TtsBakeryDiskCache.LookupStatus.Hit,
        TtsBakeryDiskCache.LookupStatus.Miss -> null
        TtsBakeryDiskCache.LookupStatus.Invalid -> context.getString(invalidReasonRes)
        TtsBakeryDiskCache.LookupStatus.Error -> context.getString(
            R.string.tts_local_reason_cache_read_error,
            errorMessage.orEmpty().ifBlank { context.getString(R.string.unknown) },
        )
    }
}

private data class BakedCountSource(
    val uri: Uri?,
    val reason: String?,
    val userReason: String?,
)

private fun getBakedCountSource(context: Context, content: CharSequence): BakedCountSource {
    if (!content.isCountdownNumber()) {
        return BakedCountSource(
            uri = null,
            reason = "builtInCountAudioSkipped=notCountdownNumber",
            userReason = null,
        )
    }
    if (!context.safeSharedPreference.useBakedCount) {
        return BakedCountSource(
            uri = null,
            reason = "builtInCountAudioEnabled=false",
            userReason = context.getString(R.string.tts_local_reason_baked_count_disabled),
        )
    }

    val folder = File(context.filesDir, PreferenceData.BAKED_COUNT_NAME)
    val file = File(folder, "$content.mp3")
    if (!file.exists()) {
        return BakedCountSource(
            uri = null,
            reason = "builtInCountAudioFileMissing=true file=${file.path.toLogValue()}",
            userReason = context.getString(R.string.tts_local_reason_baked_count_missing),
        )
    }

    return BakedCountSource(
        uri = file.toUri(),
        reason = null,
        userReason = null,
    )
}

private const val TTS_LOG_TAG = "TtsSpeaker"
private const val COUNTDOWN_TTS_LOG_TAG = "CountdownTts"
private const val CACHE_PLAYBACK_DONE_FALLBACK_MS = 1_500L
private const val COUNTDOWN_CACHE_AUDIO_FOCUS_TYPE = AudioManager.AUDIOFOCUS_NONE

private fun CharSequence.toLogText(): String = "\"${toString().replace("\n", "\\n")}\""

private fun String.toLogValue(): String = replace("\n", "\\n")

private fun CharSequence.isCountdownNumber(): Boolean = toCountdownNumberOrNull() != null

private fun CharSequence.toCountdownNumberOrNull(): Int? {
    val number = if (length <= 2) toString().toIntOrNull() else null
    return number?.takeIf { it in 0..20 }
}

private const val COUNTDOWN_TOAST_SEQUENCE_GAP_MS = 2_500L
