package xyz.aprildown.timer.app.base.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.annotation.MainThread
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide foreground/background visibility tracker.
 * Tracks number of started activities; foreground if > 0.
 */
object AppVisibilityTracker : Application.ActivityLifecycleCallbacks {

    private val startedActivityCount = AtomicInteger(0)

    @Volatile
    private var isInitialized: Boolean = false

    @JvmStatic
    @MainThread
    fun init(application: Application) {
        if (isInitialized) return
        application.registerActivityLifecycleCallbacks(this)
        isInitialized = true
    }

    /** Returns true when any activity is in STARTED/RESUMED state. */
    @JvmStatic
    fun isAppInForeground(): Boolean = startedActivityCount.get() > 0

    override fun onActivityStarted(activity: Activity) {
        startedActivityCount.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount.decrementAndGet()
    }

    // Unused lifecycle callbacks
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}


