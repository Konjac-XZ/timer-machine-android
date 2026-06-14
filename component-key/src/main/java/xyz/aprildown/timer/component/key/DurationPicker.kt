package xyz.aprildown.timer.component.key

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import com.github.deweyreed.tools.anko.snackbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.deweyreed.scrollhmspicker.ScrollHmsPicker
import xyz.aprildown.hmspickerview.HmsPickerView
import xyz.aprildown.tools.helper.safeSharedPreference
import xyz.aprildown.timer.app.base.R as RBase

class DurationPicker(
    private val context: Context,
    private var onTimePick: ((hours: Int, minutes: Int, seconds: Int) -> Unit)
) {

    fun show() {
        val currentType: Int = getCurrentPickerType(context)

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(
                if (currentType == TYPE_PANEL) {
                    R.layout.layout_time_picker_panel
                } else {
                    R.layout.layout_time_picker_scroll
                }
            )
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(
                if (currentType == TYPE_PANEL) {
                    RBase.string.time_picker_type_scroll
                } else {
                    RBase.string.time_picker_type_panel
                },
                null
            )
            .create()

        dialog.show()

        val pickerView = dialog.findViewById<View>(R.id.hmsPicker)
        requireNotNull(pickerView)

        fun finishSelecting(hours: Int, minutes: Int, seconds: Int) {
            val isZero = hours == 0 && minutes == 0 && seconds == 0
            val isNegative = hours < 0 || minutes < 0 || seconds < 0
            if (isZero || isNegative) {
                pickerView.snackbar(RBase.string.edit_at_least_1s)
            } else {
                onTimePick.invoke(hours, minutes, seconds)
                dialog.dismiss()
            }
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (currentType == TYPE_SCROLL) {
                (pickerView as ScrollHmsPicker).let {
                    finishSelecting(it.hours, it.minutes, it.seconds)
                }
            } else {
                (pickerView as HmsPickerView).let {
                    finishSelecting(it.getHours(), it.getMinutes(), it.getSeconds())
                }
            }
        }

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            val newType = if (currentType == TYPE_SCROLL) TYPE_PANEL else TYPE_SCROLL
            context.safeSharedPreference.edit {
                putInt(PREF_TIME_PICKER_TYPE, newType)
            }
            dialog.dismiss()
            show()
        }
    }

    companion object {
        fun inflateCurrentPickerLayout(context: Context, parent: ViewGroup): View {
            return LayoutInflater.from(context).inflate(
                if (getCurrentPickerType(context) == TYPE_PANEL) {
                    R.layout.layout_time_picker_panel
                } else {
                    R.layout.layout_time_picker_scroll
                },
                parent,
                false
            )
        }

        fun findPickerView(pickerLayout: View): View {
            return requireNotNull(pickerLayout.findViewById(R.id.hmsPicker))
        }

        fun getDurationMs(pickerView: View): Long {
            val (hours, minutes, seconds) = when (pickerView) {
                is ScrollHmsPicker -> Triple(
                    pickerView.hours,
                    pickerView.minutes,
                    pickerView.seconds
                )
                is HmsPickerView -> Triple(
                    pickerView.getHours(),
                    pickerView.getMinutes(),
                    pickerView.getSeconds()
                )
                else -> error("Unsupported picker view: ${pickerView::class.java.name}")
            }
            return ((hours * 60L + minutes) * 60L + seconds) * 1000L
        }

        fun getSwitchPickerTypeTextRes(context: Context): Int {
            return if (getCurrentPickerType(context) == TYPE_PANEL) {
                RBase.string.time_picker_type_scroll
            } else {
                RBase.string.time_picker_type_panel
            }
        }

        fun switchPickerType(context: Context) {
            val currentType = getCurrentPickerType(context)
            val newType = if (currentType == TYPE_SCROLL) TYPE_PANEL else TYPE_SCROLL
            context.safeSharedPreference.edit {
                putInt(PREF_TIME_PICKER_TYPE, newType)
            }
        }

        private fun getCurrentPickerType(context: Context): Int {
            return context.safeSharedPreference.getInt(PREF_TIME_PICKER_TYPE, TYPE_PANEL)
        }
    }
}

private const val PREF_TIME_PICKER_TYPE = "pref_time_picker_type"
private const val TYPE_PANEL = 0
private const val TYPE_SCROLL = 1
