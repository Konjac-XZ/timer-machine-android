package xyz.aprildown.timer.app.timer.run.screen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.github.deweyreed.tools.arch.observeEvent
import com.github.deweyreed.tools.arch.observeNonNull
import com.github.deweyreed.tools.helper.startDrawableAnimation
import com.github.deweyreed.tools.helper.stopDrawableAnimation
import com.github.deweyreed.tools.helper.toColorStateList
import com.github.deweyreed.tools.utils.ThemeColorUtils
import android.graphics.Color
import dagger.hilt.android.AndroidEntryPoint
import xyz.aprildown.timer.app.base.data.PreferenceData.resolveColor
import xyz.aprildown.timer.app.base.ui.BaseActivity
import xyz.aprildown.timer.app.base.ui.newDynamicTheme
import xyz.aprildown.timer.app.base.utils.AppThemeUtils
import xyz.aprildown.timer.app.base.utils.ScreenWakeLock
import xyz.aprildown.timer.app.base.utils.setTime
import xyz.aprildown.timer.app.timer.run.MachineService
import xyz.aprildown.timer.app.timer.run.databinding.ActivityScreenBinding
import xyz.aprildown.timer.domain.entities.TimerEntity
import xyz.aprildown.timer.domain.entities.StepType
import xyz.aprildown.timer.domain.utils.Constants
import xyz.aprildown.timer.presentation.screen.ScreenViewModel
import xyz.aprildown.timer.presentation.stream.MachineContract
import xyz.aprildown.timer.app.base.R as RBase
import com.github.deweyreed.tools.R as RTools

@AndroidEntryPoint
class ScreenActivity : BaseActivity() {

    private lateinit var binding: ActivityScreenBinding

    private val viewModel: ScreenViewModel by viewModels()
    private lateinit var windowInsetsController: WindowInsetsControllerCompat
    private var appliedLightStatusBars: Boolean? = null
    private var appliedLightNavigationBars: Boolean? = null
    
    // Track current step info for gradient animation
    private var currentStepType: StepType? = null
    private var currentStepColor: Int = Color.TRANSPARENT
    private var currentStepDuration: Long = 0L
    private var currentStepColorIsLight: Boolean = false
    
    // For smooth animation updates
    private var isAnimating = false
    private val animationRunnable = object : Runnable {
        override fun run() {
            if (isAnimating) {
                updateGradientProgress()
                binding.root.postOnAnimation(this)
            }
        }
    }

    // Interpolation state between timer ticks
    private var lastTickRemainingMs: Long = 0L
    private var lastTickRealtimeMs: Long = 0L
    private val windowLocation = IntArray(2)
    private var lastGradientProgress: Float = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setBackgroundDrawable(null)

        screen = this

        binding = ActivityScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.clipToPadding = false
        binding.root.clipChildren = false

        // Enable edge-to-edge layout
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        windowInsetsController = WindowCompat.getInsetsController(window, binding.root)
        appliedLightStatusBars = null
        appliedLightNavigationBars = null

        init()
        setUpFullscreen()
        setUpObservers()

        bindService(
            MachineService.bindIntent(this),
            mConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun init() {
        viewModel.setTimerId(
            intent?.getIntExtra(Constants.EXTRA_TIMER_ID, TimerEntity.NULL_ID)
                ?: TimerEntity.NULL_ID
        )
    }

    private fun setUpFullscreen() {
        val isLandscape = resources.getBoolean(RTools.bool.is_landscape)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val targetInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val isLtr = v.layoutDirection == View.LAYOUT_DIRECTION_LTR
            if (!isLandscape) {
                binding.root.updatePadding(
                    left = targetInsets.left,
                    right = targetInsets.right,
                    top = targetInsets.top,
                )
                binding.btnStop.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    bottomMargin = targetInsets.bottom
                }
            } else {
                if (isLtr) {
                    binding.root.updatePadding(
                        left = targetInsets.left,
                        top = targetInsets.top,
                    )
                    binding.btnStop.updateLayoutParams<ConstraintLayout.LayoutParams> {
                        rightMargin = targetInsets.right
                    }
                } else {
                    binding.root.updatePadding(
                        top = targetInsets.top,
                        right = targetInsets.right,
                    )
                    binding.btnStop.updateLayoutParams<ConstraintLayout.LayoutParams> {
                        leftMargin = targetInsets.left
                    }
                }
            }
            binding.gradientOverlay.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topMargin = -targetInsets.top
                bottomMargin = -targetInsets.bottom
                marginStart = -targetInsets.left
                marginEnd = -targetInsets.right
            }
            insets
        }

        // Hide system bars for full immersive experience
        windowInsetsController.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        @Suppress("DEPRECATION") // LOW_PROFILE only exists in old APIs.
        binding.root.systemUiVisibility =
            binding.root.systemUiVisibility or View.SYSTEM_UI_FLAG_LOW_PROFILE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        // Without the first two flags and only with the two functions above,
        // it won't work on some devices.
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Close dialogs and window shade, so this is fully visible
            @Suppress("MissingPermission", "DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
        }
    }

    private fun setUpObservers() {
        viewModel.step.observeNonNull(this) { step ->
            currentStepType = step.type
            val color = step.resolveColor(this)
            currentStepColor = color
            currentStepColorIsLight = ThemeColorUtils.isLightColor(color)
            val isLightColor = currentStepColorIsLight
            val onColor = AppThemeUtils.calculateOnColor(color)

            setSystemBarAppearance(isLightColor, isLightColor)

            // Show ringing bell only for NOTIFIER steps
            val showBell = step.type == StepType.NOTIFIER
            binding.imageRingtone.isVisible = showBell
        
            // Enable gradient overlay for NORMAL steps only
            val useGradient = step.type == StepType.NORMAL
            if (!useGradient) {
                lastGradientProgress = 0f
            }
            binding.gradientOverlay.isVisible = useGradient
            
            if (!useGradient) {
                // Stop smooth animation for non-NORMAL steps
                stopSmoothAnimation()
                
                // For non-NORMAL steps, use solid color background
                binding.rootLayout.setBackgroundColor(color)
                binding.textStepInfo.clearSplitColors()
                binding.textTime.clearSplitColors()
                binding.btnAddOneMinute.clearSplitColors()

                val staticTextColor = if (step.type == StepType.NOTIFIER) {
                    Color.WHITE
                } else {
                    onColor
                }

                binding.textStepInfo.setTextColor(staticTextColor)
                binding.textTime.setTextColor(staticTextColor)
                ImageViewCompat.setImageTintList(binding.imageRingtone, onColor.toColorStateList())
                // textTime uses SplitColorTextView for partial inversion; do not force a single color here
                binding.btnAddOneMinute.setTextColor(staticTextColor)
            } else {
                // For NORMAL steps, background will be handled by gradient overlay
                binding.rootLayout.setBackgroundColor(color)
                binding.textTime.setTextColor(Color.WHITE)
                binding.textStepInfo.setTextColor(Color.WHITE)
                binding.btnAddOneMinute.setTextColor(Color.WHITE)
                lastGradientProgress = 0f
                updateSystemBarsForProgress(lastGradientProgress)
                // Start smooth animation
                startSmoothAnimation()
            }

            if (showBell) {
                binding.imageRingtone.startDrawableAnimation()
            } else {
                binding.imageRingtone.stopDrawableAnimation()
            }

            if (ColorUtils.calculateContrast(newDynamicTheme.colorSecondary, color) <=
                3.0 // Min contrast
            ) {
                ViewCompat.setBackgroundTintList(
                    binding.btnStop,
                    ThemeColorUtils.setAlpha(onColor, 1f).toColorStateList()
                )
                ImageViewCompat.setImageTintList(binding.btnStop, color.toColorStateList())
            }
        }
        
        viewModel.stepDuration.observe(this) { duration ->
            currentStepDuration = duration
            updateGradientProgress()
        }
        
        viewModel.timerStepInfo.observe(this) {
            binding.textStepInfo.text = it
        }
        viewModel.timerCurrentTime.observe(this) { time ->
            val remaining = time ?: 0L
            binding.textTime.setTime(remaining)
            // Capture tick baseline for per-frame interpolation
            lastTickRemainingMs = remaining
            lastTickRealtimeMs = android.os.SystemClock.elapsedRealtime()
            // Don't call updateGradientProgress here - it's handled by smooth animation
        }
        binding.btnAddOneMinute.setOnClickListener {
            viewModel.onAddOneMinute()
        }
        binding.btnStop.setOnClickListener {
            viewModel.onStop()
        }

        viewModel.intentEvent.observeEvent(this) {
            startService(it)
        }
        viewModel.stopEvent.observeEvent(this) {
            finish()
        }
    }

    private fun startSmoothAnimation() {
        if (!isAnimating) {
            isAnimating = true
            binding.root.postOnAnimation(animationRunnable)
        }
    }
    
    private fun stopSmoothAnimation() {
        isAnimating = false
        binding.root.removeCallbacks(animationRunnable)
    }

    private fun setSystemBarAppearance(lightStatus: Boolean, lightNav: Boolean) {
        if (!::windowInsetsController.isInitialized) {
            return
        }
        if (appliedLightStatusBars != lightStatus) {
            windowInsetsController.isAppearanceLightStatusBars = lightStatus
            appliedLightStatusBars = lightStatus
        }
        if (appliedLightNavigationBars != lightNav) {
            windowInsetsController.isAppearanceLightNavigationBars = lightNav
            appliedLightNavigationBars = lightNav
        }
    }

    private fun updateSystemBarsForProgress(progress: Float) {
        if (currentStepType != StepType.NORMAL) {
            return
        }
        val lightStatus = if (progress > 0f) {
            true
        } else {
            currentStepColorIsLight
        }
        val lightNav = if (progress >= 1f) {
            true
        } else {
            currentStepColorIsLight
        }
        setSystemBarAppearance(lightStatus, lightNav)
    }

    /**
     * Update progress overlay and text colors for NORMAL steps.
     * As time progresses, the screen turns white from top to bottom (sharp transition),
     * and text colors invert from white to step color accordingly.
     * This is called every frame for smooth animation.
     */
    private fun updateGradientProgress() {
        if (currentStepType != StepType.NORMAL || !binding.gradientOverlay.isVisible) {
            return
        }
        
        // Interpolate remaining time smoothly using monotonic clock
        val nowRealtime = android.os.SystemClock.elapsedRealtime()
        val dt = (nowRealtime - lastTickRealtimeMs).coerceAtLeast(0L)
        // Remaining time decreases as time passes
        val interpolatedRemaining = (lastTickRemainingMs - dt).coerceAtLeast(0L)
        val currentTime = interpolatedRemaining
        val duration = currentStepDuration
        
        if (duration <= 0) {
            return
        }
        
        // Calculate elapsed time (duration - remaining time)
        // currentTime is the REMAINING time (counts down from duration to 0)
        val elapsed = duration - currentTime
        val progress = if (duration > 0) {
            (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        
        // Update overlay with sharp color transition
        binding.gradientOverlay.setColorAndProgress(currentStepColor, progress)
        
        // Calculate text colors based on position
        val gradientView = binding.gradientOverlay
        val gradientHeight = gradientView.height
        if (gradientHeight == 0) {
            // Layout not ready yet, will update on next call
            return
        }

        val gradientHeightFloat = gradientHeight.toFloat()
        gradientView.getLocationInWindow(windowLocation)
        val transitionYGlobal =
            windowLocation[1].toFloat() + gradientHeightFloat * progress
        
        // Update timer text with split colors (convert global Y inside the view)
        val topColor = currentStepColor // text on white area
        val bottomColor = Color.WHITE // text on colored area
        binding.textTime.setSplitGlobal(
            globalSplitY = transitionYGlobal,
            topColor = topColor,
            bottomColor = bottomColor
        )
        binding.textStepInfo.setSplitGlobal(
            globalSplitY = transitionYGlobal,
            topColor = topColor,
            bottomColor = bottomColor
        )
        binding.btnAddOneMinute.setSplitGlobal(
            globalSplitY = transitionYGlobal,
            topColor = topColor,
            bottomColor = bottomColor
        )

        lastGradientProgress = progress
        updateSystemBarsForProgress(progress)
    }

    override fun onResume() {
        super.onResume()

        // Acquire wake lock to keep screen on reliably across all devices
        ScreenWakeLock.acquireScreenWakeLock(
            context = this,
            screenTiming = getString(RBase.string.pref_screen_timing_value_timer)
        )

        // Re-hide system bars when returning to the activity
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        binding.imageRingtone.post {
            if (binding.imageRingtone.isVisible) {
                binding.imageRingtone.startDrawableAnimation()
            }
        }
        // Resume smooth animation if it's a NORMAL step
        if (currentStepType == StepType.NORMAL && binding.gradientOverlay.isVisible) {
            startSmoothAnimation()
        }
    }

    override fun onPause() {
        binding.imageRingtone.stopDrawableAnimation()
        stopSmoothAnimation()

        // Release wake lock when activity is no longer visible
        ScreenWakeLock.releaseScreenLock(
            context = this,
            screenTiming = getString(RBase.string.pref_screen_timing_value_timer)
        )

        super.onPause()
    }

    override fun onDestroy() {
        stopSmoothAnimation()
        unbindService(mConnection)
        viewModel.dropPresenter()

        // Release wake lock as a safety measure
        ScreenWakeLock.releaseScreenLock(
            context = this,
            screenTiming = getString(RBase.string.pref_screen_timing_value_timer)
        )

        screen = null
        super.onDestroy()
    }

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            viewModel.setPresenter((service as MachineContract.PresenterProvider).getPresenter())
        }

        override fun onServiceDisconnected(name: ComponentName?) = Unit
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        var screen: Activity? = null

        fun intent(context: Context, id: Int): Intent {
            return Intent(context, ScreenActivity::class.java)
                .putExtra(Constants.EXTRA_TIMER_ID, id)
        }
    }
}
