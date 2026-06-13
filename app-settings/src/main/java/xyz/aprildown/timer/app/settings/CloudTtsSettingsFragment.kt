package xyz.aprildown.timer.app.settings

import android.os.Bundle
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import com.github.deweyreed.timer.component.tts.TtsBakery
import com.github.deweyreed.tools.anko.dp
import com.github.deweyreed.tools.anko.longSnackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import xyz.aprildown.timer.app.base.data.PreferenceData
import xyz.aprildown.timer.app.base.ui.BasePreferenceFragmentCompat
import xyz.aprildown.timer.component.key.SimpleInputDialog
import xyz.aprildown.timer.domain.utils.fireAndForget
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import xyz.aprildown.timer.app.base.R as RBase

@AndroidEntryPoint
class CloudTtsSettingsFragment :
    BasePreferenceFragmentCompat(),
    Preference.OnPreferenceChangeListener,
    Preference.OnPreferenceClickListener {

    private var ttsPrerenderDialogJob: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.pref_cloud_tts_settings, rootKey)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        when (preference.key) {
            KEY_CLOUD_TTS_SPEECH_RATE -> {
                val value = newValue?.toString()?.toIntOrNull()
                if (value == null || value !in -50..100) return false
                clearTtsCache()
            }
            KEY_CLOUD_TTS_EMOTION_SCALE -> {
                val value = newValue?.toString()?.toIntOrNull()
                if (value == null || value !in 1..5) return false
                clearTtsCache()
            }
            KEY_CLOUD_TTS_MAX_SENTENCES_PER_REQUEST -> {
                val value = newValue?.toString()?.toIntOrNull()
                if (value == null ||
                    value !in PreferenceData.CLOUD_TTS_MIN_SENTENCES_PER_REQUEST..
                    PreferenceData.CLOUD_TTS_MAX_SENTENCES_PER_REQUEST
                ) {
                    return false
                }
                clearTtsCache()
            }
            KEY_CLOUD_TTS_MAX_CONCURRENCY -> {
                val value = newValue?.toString()?.toIntOrNull()
                if (value == null ||
                    value !in PreferenceData.CLOUD_TTS_MIN_CONCURRENCY..
                    PreferenceData.CLOUD_TTS_MAX_CONCURRENCY
                ) {
                    return false
                }
                clearTtsCache()
            }
            KEY_CLOUD_TTS_ENABLED,
            KEY_CLOUD_TTS_API_KEY,
            KEY_CLOUD_TTS_RESOURCE_ID,
            KEY_CLOUD_TTS_SPEAKER,
            KEY_CLOUD_TTS_CONTEXT_TEXT -> {
                clearTtsCache()
            }
        }
        return true
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when (preference.key) {
            KEY_TTS_PRERENDER -> {
                showTtsPrerenderDialog()
            }
            KEY_TTS_CLEAR_CACHE -> {
                showTtsClearCacheDialog()
            }
            else -> return false
        }
        return true
    }

    private fun refresh() {
        findPreference<Preference>(KEY_CLOUD_TTS_ENABLED)?.onPreferenceChangeListener = this
        findPreference<EditTextPreference>(KEY_CLOUD_TTS_API_KEY)?.run {
            onPreferenceChangeListener = this@CloudTtsSettingsFragment
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                if (preference.text.isNullOrBlank()) {
                    getString(RBase.string.pref_cloud_tts_api_key_summary)
                } else {
                    getString(RBase.string.pref_cloud_tts_api_key_configured)
                }
            }
        }
        findPreference<EditTextPreference>(KEY_CLOUD_TTS_RESOURCE_ID)?.run {
            onPreferenceChangeListener = this@CloudTtsSettingsFragment
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT
            }
        }
        findPreference<EditTextPreference>(KEY_CLOUD_TTS_SPEAKER)?.run {
            onPreferenceChangeListener = this@CloudTtsSettingsFragment
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT
            }
        }
        findPreference<EditTextPreference>(KEY_CLOUD_TTS_SPEECH_RATE)?.run {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            onPreferenceChangeListener = this@CloudTtsSettingsFragment
        }
        findPreference<EditTextPreference>(KEY_CLOUD_TTS_EMOTION_SCALE)?.run {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
            }
            onPreferenceChangeListener = this@CloudTtsSettingsFragment
        }
        findPreference<EditTextPreference>(KEY_CLOUD_TTS_CONTEXT_TEXT)?.run {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                it.setSingleLine(false)
            }
            onPreferenceChangeListener = this@CloudTtsSettingsFragment
        }
        findPreference<EditTextPreference>(KEY_CLOUD_TTS_MAX_SENTENCES_PER_REQUEST)?.run {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
            }
            onPreferenceChangeListener = this@CloudTtsSettingsFragment
        }
        findPreference<EditTextPreference>(KEY_CLOUD_TTS_MAX_CONCURRENCY)?.run {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
            }
            onPreferenceChangeListener = this@CloudTtsSettingsFragment
        }
        findPreference<Preference>(KEY_TTS_PRERENDER)?.onPreferenceClickListener = this
        findPreference<Preference>(KEY_TTS_CLEAR_CACHE)?.onPreferenceClickListener = this
    }

    private fun showTtsPrerenderDialog() {
        SimpleInputDialog(requireContext()).show(
            titleRes = RBase.string.pref_tts_prerender_dialog_title,
            message = getString(RBase.string.pref_tts_prerender_dialog_message_cloud),
            hint = getString(RBase.string.pref_tts_prerender_dialog_hint),
            preFill = DEFAULT_TTS_PRERENDER_COUNT.toString(),
            inputType = InputType.TYPE_CLASS_NUMBER
        ) { input ->
            val count = input.toIntOrNull()
            if (count == null || count !in 1..MAX_TTS_PRERENDER_COUNT) {
                view?.longSnackbar(RBase.string.pref_tts_prerender_invalid_input)
                return@show
            }

            startTtsPrerendering(count)
        }
    }

    private fun startTtsPrerendering(count: Int) {
        if (TtsBakery.startCountdownPrerendering(requireContext(), count)) {
            showTtsPrerenderProgressDialog()
        } else {
            view?.longSnackbar(RBase.string.pref_tts_prerender_already_running)
            showTtsPrerenderProgressDialog()
        }
    }

    private fun showTtsClearCacheDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(RBase.string.pref_tts_clear_cache)
            .setMessage(RBase.string.pref_tts_clear_cache_confirmation)
            .setNegativeButton(RBase.string.cancel, null)
            .setPositiveButton(RBase.string.ok) { _, _ ->
                clearTtsCache()
                view?.longSnackbar(RBase.string.pref_tts_clear_cache_done)
            }
            .show()
    }

    private fun clearTtsCache() {
        val context = requireContext().applicationContext
        fireAndForget {
            TtsBakery.tearDown(context)
        }
    }

    private fun showTtsPrerenderProgressDialog() {
        ttsPrerenderDialogJob?.cancel()

        val dialogView = layoutInflater.inflate(
            android.R.layout.simple_list_item_2,
            null,
            false,
        )
        val titleView = dialogView.findViewById<TextView>(android.R.id.text1)
        val progressTextView = dialogView.findViewById<TextView>(android.R.id.text2)
        val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(RBase.string.pref_tts_prerender_title)
            .setView(
                LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    val padding = requireContext().dp(24).toInt()
                    setPadding(padding, padding, padding, 0)
                    addView(dialogView)
                    addView(progressBar)
                }
            )
            .setPositiveButton(RBase.string.ok, null)
            .create()

        dialog.setOnDismissListener {
            ttsPrerenderDialogJob?.cancel()
            ttsPrerenderDialogJob = null
        }
        dialog.show()

        ttsPrerenderDialogJob = viewLifecycleOwner.lifecycleScope.launch {
            TtsBakery.countdownPrerenderState.collectLatest { state ->
                when (state) {
                    TtsBakery.CountdownPrerenderState.Idle -> Unit
                    TtsBakery.CountdownPrerenderState.Batching -> {
                        titleView.setText(RBase.string.pref_tts_prerender_notification_title)
                        progressTextView.setText(RBase.string.pref_tts_prerender_cloud_batching)
                        progressBar.isIndeterminate = true
                    }
                    is TtsBakery.CountdownPrerenderState.Running -> {
                        titleView.setText(RBase.string.pref_tts_prerender_notification_title)
                        progressTextView.text = getString(
                            RBase.string.pref_tts_prerender_progress,
                            state.current,
                            state.total,
                        )
                        progressBar.max = state.total
                        progressBar.progress = state.current
                        progressBar.isIndeterminate = state.total == 0
                    }
                    is TtsBakery.CountdownPrerenderState.Complete -> {
                        titleView.text = if (state.failed == 0) {
                            getString(
                                RBase.string.pref_tts_prerender_completed,
                                state.success,
                                state.total,
                            )
                        } else {
                            getString(
                                RBase.string.pref_tts_prerender_completed_with_failures,
                                state.success,
                                state.total,
                                state.failed,
                            )
                        }
                        progressTextView.text = null
                        progressBar.max = state.total
                        progressBar.progress = state.success
                        progressBar.isIndeterminate = false
                    }
                    TtsBakery.CountdownPrerenderState.FailedToStart -> {
                        titleView.setText(RBase.string.pref_tts_prerender_failed)
                        progressTextView.text = null
                        progressBar.isIndeterminate = false
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ttsPrerenderDialogJob?.cancel()
        ttsPrerenderDialogJob = null
    }
}

private const val KEY_CLOUD_TTS_ENABLED = PreferenceData.PREF_CLOUD_TTS_ENABLED
private const val KEY_CLOUD_TTS_API_KEY = PreferenceData.PREF_CLOUD_TTS_API_KEY
private const val KEY_CLOUD_TTS_RESOURCE_ID = PreferenceData.PREF_CLOUD_TTS_RESOURCE_ID
private const val KEY_CLOUD_TTS_SPEAKER = PreferenceData.PREF_CLOUD_TTS_SPEAKER
private const val KEY_CLOUD_TTS_SPEECH_RATE = PreferenceData.PREF_CLOUD_TTS_SPEECH_RATE
private const val KEY_CLOUD_TTS_EMOTION_SCALE = PreferenceData.PREF_CLOUD_TTS_EMOTION_SCALE
private const val KEY_CLOUD_TTS_MAX_SENTENCES_PER_REQUEST =
    PreferenceData.PREF_CLOUD_TTS_MAX_SENTENCES_PER_REQUEST
private const val KEY_CLOUD_TTS_MAX_CONCURRENCY = PreferenceData.PREF_CLOUD_TTS_MAX_CONCURRENCY
private const val KEY_CLOUD_TTS_CONTEXT_TEXT = PreferenceData.PREF_CLOUD_TTS_CONTEXT_TEXT
private const val KEY_TTS_PRERENDER = "key_tts_prerender"
private const val KEY_TTS_CLEAR_CACHE = "key_tts_clear_cache"
private const val DEFAULT_TTS_PRERENDER_COUNT = 60
private const val MAX_TTS_PRERENDER_COUNT = 300
