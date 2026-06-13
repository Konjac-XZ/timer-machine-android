package xyz.aprildown.timer.app.settings

import android.os.Bundle
import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import com.github.deweyreed.timer.component.tts.TtsBakery
import dagger.hilt.android.AndroidEntryPoint
import xyz.aprildown.timer.app.base.data.PreferenceData
import xyz.aprildown.timer.app.base.ui.BasePreferenceFragmentCompat
import xyz.aprildown.timer.domain.utils.fireAndForget
import xyz.aprildown.timer.app.base.R as RBase

@AndroidEntryPoint
class CloudTtsSettingsFragment :
    BasePreferenceFragmentCompat(),
    Preference.OnPreferenceChangeListener {

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
    }

    private fun clearTtsCache() {
        val context = requireContext().applicationContext
        fireAndForget {
            TtsBakery.tearDown(context)
        }
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
