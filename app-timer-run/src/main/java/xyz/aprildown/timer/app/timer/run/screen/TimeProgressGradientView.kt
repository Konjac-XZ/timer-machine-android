package xyz.aprildown.timer.app.timer.run.screen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A view that draws a sharp color transition from top to bottom based on time progress.
 * Used for regular steps to show an hourglass-like effect where the screen
 * gradually turns white from top to bottom as time passes (no gradient).
 */
class TimeProgressGradientView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    private var baseColor: Int = Color.TRANSPARENT
    private var progress: Float = 0f // 0.0 to 1.0
    
    /**
     * Set the base color (step color) and progress (0.0 = start, 1.0 = end)
     */
    fun setColorAndProgress(color: Int, progressValue: Float) {
        baseColor = color
        progress = progressValue.coerceIn(0f, 1f)
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (width == 0 || height == 0) return
        
        // Calculate the transition point (where white starts)
        val transitionY = height * progress
        
        paint.shader = null
        
        // Fill everything with base color first (bottom portion that hasn't turned white yet)
        paint.color = baseColor
        canvas.drawRect(0f, transitionY, width.toFloat(), height.toFloat(), paint)
        
        // Draw white from top to transition point (the portion that has turned white)
        if (transitionY > 0) {
            paint.color = Color.WHITE
            canvas.drawRect(0f, 0f, width.toFloat(), transitionY, paint)
        }
    }
}

