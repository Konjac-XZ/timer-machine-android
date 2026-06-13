package xyz.aprildown.timer.app.timer.run.screen

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import com.google.android.material.button.MaterialButton

private class SplitColorTextDelegate(
    private val view: TextView
) {
    private val windowLocation = IntArray(2)

    private var splitY: Float = Float.NaN
    private var topTextColor: Int = Color.WHITE
    private var bottomTextColor: Int = Color.WHITE

    fun setSplit(splitY: Float, topColor: Int, bottomColor: Int) {
        this.splitY = splitY
        this.topTextColor = topColor
        this.bottomTextColor = bottomColor
        view.invalidate()
    }

    fun setSplitGlobal(globalSplitY: Float, topColor: Int, bottomColor: Int) {
        if (view.height == 0) {
            // View not laid out yet; skip until we have dimensions
            return
        }

        val layout = view.layout ?: return

        view.getLocationInWindow(windowLocation)

        val extendedTop = view.extendedPaddingTop
        val extendedBottom = view.extendedPaddingBottom
        val boxHeight = view.height - extendedTop - extendedBottom
        if (boxHeight <= 0) {
            return
        }

        val textHeight = layout.height
        val vgrav = view.gravity and Gravity.VERTICAL_GRAVITY_MASK
        val vOffset = if (vgrav != Gravity.TOP && textHeight < boxHeight) {
            when (vgrav) {
                Gravity.BOTTOM -> boxHeight - textHeight
                else -> (boxHeight - textHeight) / 2
            }
        } else 0

        val layoutOriginGlobalY = windowLocation[1] + extendedTop + vOffset
        val splitInLayout = (globalSplitY - layoutOriginGlobalY)
            .coerceIn(0f, boxHeight.toFloat())

        setSplit(splitInLayout, topColor, bottomColor)
    }

    fun clearSplit() {
        if (!splitY.isNaN()) {
            splitY = Float.NaN
            view.invalidate()
        }
    }

    fun hasSplit(): Boolean = !splitY.isNaN()

    fun drawSplit(canvas: Canvas): Boolean {
        val text = view.text
        if (splitY.isNaN() || text.isNullOrEmpty() || !TextUtils.isGraphic(text)) {
            return false
        }

        val layout = view.layout ?: return false

        val sc = canvas.save()

        val totalPaddingLeft = view.totalPaddingLeft
        val totalPaddingRight = view.totalPaddingRight
        val extendedTop = view.extendedPaddingTop
        val extendedBottom = view.extendedPaddingBottom

        val vgrav = view.gravity and Gravity.VERTICAL_GRAVITY_MASK
        val boxHeight = view.height - extendedTop - extendedBottom
        if (boxHeight <= 0) {
            canvas.restoreToCount(sc)
            return false
        }
        val textHeight = layout.height
        val vOffset = if (vgrav != Gravity.TOP && textHeight < boxHeight) {
            when (vgrav) {
                Gravity.BOTTOM -> boxHeight - textHeight
                else -> (boxHeight - textHeight) / 2
            }
        } else 0

        val contentWidth = view.width - totalPaddingLeft - totalPaddingRight
        val contentHeight = boxHeight

        val dx = (totalPaddingLeft - view.scrollX).toFloat()
        val dy = (extendedTop + vOffset - view.scrollY).toFloat()
        canvas.translate(dx, dy)

        val splitInLayout = splitY.coerceIn(0f, contentHeight.toFloat())

        val paint = view.paint

        paint.color = topTextColor
        canvas.save()
        canvas.clipRect(0f, 0f, contentWidth.toFloat(), splitInLayout)
        layout.draw(canvas)
        canvas.restore()

        paint.color = bottomTextColor
        canvas.save()
        canvas.clipRect(0f, splitInLayout, contentWidth.toFloat(), contentHeight.toFloat())
        layout.draw(canvas)
        canvas.restore()

        canvas.restoreToCount(sc)
        return true
    }
}

/**
 * Draws text with two solid colors split by a horizontal line (splitY in view coords).
 * Top area uses [top color], bottom uses [bottom color]. No gradients.
 */
class SplitColorTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var splitDelegate: SplitColorTextDelegate? = null

    private fun ensureDelegate(): SplitColorTextDelegate {
        var delegate = splitDelegate
        if (delegate == null) {
            delegate = SplitColorTextDelegate(this)
            splitDelegate = delegate
        }
        return delegate
    }

    fun setSplit(splitY: Float, topColor: Int, bottomColor: Int) {
        ensureDelegate().setSplit(splitY, topColor, bottomColor)
    }

    fun setSplitGlobal(globalSplitY: Float, topColor: Int, bottomColor: Int) {
        ensureDelegate().setSplitGlobal(globalSplitY, topColor, bottomColor)
    }

    fun clearSplitColors() {
        splitDelegate?.clearSplit()
    }

    override fun onDraw(canvas: Canvas) {
        val delegate = splitDelegate
        if (delegate != null && delegate.drawSplit(canvas)) {
            return
        }
        super.onDraw(canvas)
    }
}

class SplitColorMaterialButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialButtonStyle
) : MaterialButton(context, attrs, defStyleAttr) {

    private var splitDelegate: SplitColorTextDelegate? = null

    private fun ensureDelegate(): SplitColorTextDelegate {
        var delegate = splitDelegate
        if (delegate == null) {
            delegate = SplitColorTextDelegate(this)
            splitDelegate = delegate
        }
        return delegate
    }

    fun setSplit(splitY: Float, topColor: Int, bottomColor: Int) {
        ensureDelegate().setSplit(splitY, topColor, bottomColor)
    }

    fun setSplitGlobal(globalSplitY: Float, topColor: Int, bottomColor: Int) {
        ensureDelegate().setSplitGlobal(globalSplitY, topColor, bottomColor)
    }

    fun clearSplitColors() {
        splitDelegate?.clearSplit()
    }

    override fun onDraw(canvas: Canvas) {
        val delegate = splitDelegate
        val shouldOverlaySplit = delegate?.hasSplit() == true
        val textPaint = paint
        val originalColor = textPaint.color
        if (shouldOverlaySplit) {
            textPaint.color = Color.TRANSPARENT
        }
        super.onDraw(canvas)
        if (shouldOverlaySplit && delegate != null) {
            delegate.drawSplit(canvas)
            textPaint.color = originalColor
        }
    }

    override fun setTextColor(colors: ColorStateList?) {
        super.setTextColor(colors)
        // When the base color changes, refresh split drawing to keep palette in sync
        if (splitDelegate?.hasSplit() == true) {
            invalidate()
        }
    }
}
