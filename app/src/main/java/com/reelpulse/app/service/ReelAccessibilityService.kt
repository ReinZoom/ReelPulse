package com.reelpulse.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.animation.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.reelpulse.app.R
import com.reelpulse.app.data.ReelDatabase
import com.reelpulse.app.data.ReelEvent
import com.reelpulse.app.data.ReelRepository
import com.reelpulse.app.ui.FloatingCounter
import com.reelpulse.app.ui.MainActivity
import com.reelpulse.app.ui.theme.ReelPulseTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.LinkedHashSet
import kotlin.time.Duration.Companion.minutes

class ReelAccessibilityService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repository: ReelRepository
    private lateinit var prefs: SharedPreferences

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private val overlayParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = 150 
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry =
        savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()

    private val historyByPackage = HashMap<String, LinkedHashSet<String>>()
    private val lastProcessingTimeByPackage = HashMap<String, Long>()
    private val lastCountTimeByPackage = HashMap<String, Long>()
    
    // Stability State Machine
    private var pendingSignature: String? = null
    private var pendingSignatureTimestamp: Long = 0L

    private val trackedPackages get() = ALL_DETECTORS.keys
    
    
    
    private val launcherPackages = setOf(
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.miui.home",
        "com.sec.android.app.launcher",
        "com.motorola.launcher3",
    )
    
    private var currentPackage: String? = null
    private var lastIsOnReel: Boolean = false
    private var hideOverlayJob: Job? = null
    private var hardHideJob: Job? = null
    private var cleanupJob: Job? = null
    var isManualDisable = false
    val isTrackingPaused = MutableStateFlow(value = false)
    
    private val isOnReelFlow = MutableStateFlow(value = false)

    init {
        instance = this
    }

    companion object {
        var instance: ReelAccessibilityService? = null
            private set

        private const val RECOVERY_NOTIFICATION_ID = 9999
        private const val STABILITY_THRESHOLD_MS = 300L
        private const val DEBOUNCE_MS = 500L
        private const val MAX_HISTORY_SIZE = 150
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        
        // Periodic Memory Cleanup
        cleanupJob = serviceScope.launch {
            while (isActive) {
                delay(30.minutes)
                historyByPackage.clear() // Clear fingerprints to save memory
                Log.d("ReelPulseService", "Neural fingerprint cache cleared for performance.")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val db = ReelDatabase.getInstance(applicationContext)
        repository = ReelRepository(db.reelDao())
        prefs = getSharedPreferences("reel_pulse_prefs", MODE_PRIVATE)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or 
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or 
                         AccessibilityEvent.TYPE_VIEW_SCROLLED or
                         AccessibilityEvent.TYPE_VIEW_CLICKED or
                         AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                         AccessibilityEvent.TYPE_VIEW_FOCUSED
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or 
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "show_floating_counter") {
            updateOverlayState(lastIsOnReel)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (isTrackingPaused.value) {
            hideOverlayImmediate()
            return
        }

        // 0. Ignore System UI entirely (volume bars, notifications, status bar)
        // This keeps the counter constant during system popups
        if (pkg == "com.android.systemui") return

        // 1. Launcher handling: Trigger soft hide
        if (launcherPackages.contains(pkg)) {
            if (currentPackage != pkg) {
                currentPackage = pkg
                if (lastIsOnReel) {
                    lastIsOnReel = false
                    hideOverlayJob?.cancel()
                    hideOverlayJob = serviceScope.launch(Dispatchers.Main) {
                        delay(2000) // Quick fade out for launcher
                        if (!lastIsOnReel) updateOverlayState(false)
                    }
                } else {
                    updateOverlayState(false)
                }
            }
            return
        }

        // 2. Untracked apps: Immediate hard hide (e.g. Banking)
        if (!trackedPackages.contains(pkg)) {
            if (currentPackage != pkg) {
                currentPackage = pkg
                hideOverlayImmediate()
            }
            return
        }

        if (pkg != currentPackage) {
            currentPackage = pkg
        }

        val detector = ALL_DETECTORS[pkg]
        if (detector == null) {
            if (lastIsOnReel) {
                lastIsOnReel = false
                updateOverlayState(false)
            }
            return
        }

        val now = System.currentTimeMillis()
        val lastProc = lastProcessingTimeByPackage[pkg] ?: 0L
        if (now - lastProc < 50) return // Tightened proc interval
        lastProcessingTimeByPackage[pkg] = now

        val root = rootInActiveWindow ?: event.source?.let { getRootNode(it) } ?: run {
            // Fallback for some devices where rootInActiveWindow is flaky
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windows.firstOrNull { it.isActive }?.root
            } else null
        } ?: return
        try {
            val isSubView = detector.isSubViewActive(root)
            
            val signature = if (isSubView) {
                null
            } else if (detector.gestureCounted) {
                if (detector.isFullScreenReelSurface(root)) "gestured_reel" else null
            } else {
                detector.currentReelSignature(root)
            }

            val isOnReel = signature != null

            if (isOnReel != lastIsOnReel) {
                lastIsOnReel = isOnReel
                if (isOnReel) {
                    hideOverlayJob?.cancel()
                    updateOverlayState(true)
                } else {
                    hideOverlayJob?.cancel()
                    hideOverlayJob = serviceScope.launch(Dispatchers.Main) {
                        delay(3000) // Increased delay to prevent flickering during scrolls
                        if (!lastIsOnReel) updateOverlayState(false)
                    }
                }
            }

            if (isOnReel && !isSubView) {
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                    if (detector.gestureCounted) {
                        handleGestureDetection(pkg, detector, root, event)
                    } else {
                        handleContentSignatureDetection(pkg, signature, immediate = true)
                    }
                } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || 
                           event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    if (!detector.gestureCounted) {
                        handleContentSignatureDetection(pkg, signature, immediate = false)
                    }
                }
            } else {
                pendingSignature = null
            }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private fun handleContentSignatureDetection(pkg: String, signature: String, immediate: Boolean) {
        val history = historyByPackage.getOrPut(pkg) { LinkedHashSet() }
        val now = System.currentTimeMillis()

        if (history.contains(signature)) {
            history.remove(signature)
            history.add(signature)
            pendingSignature = null
            return
        }

        if (immediate) {
            performCount(pkg, signature, history, now)
            pendingSignature = null
        } else {
            if (signature == pendingSignature) {
                if (now - pendingSignatureTimestamp >= STABILITY_THRESHOLD_MS) {
                    performCount(pkg, signature, history, now)
                    pendingSignature = null
                }
            } else {
                pendingSignature = signature
                pendingSignatureTimestamp = now
            }
        }
    }

    private fun performCount(pkg: String, signature: String, history: LinkedHashSet<String>, now: Long) {
        val lastCount = lastCountTimeByPackage[pkg] ?: 0L
        if (now - lastCount < DEBOUNCE_MS) return

        val isFirstInAppSession = history.isEmpty()

        if (history.size >= MAX_HISTORY_SIZE) {
            val iterator = history.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        history.add(signature)
        lastCountTimeByPackage[pkg] = now

        if (!isFirstInAppSession) {
            onNewReelDetected(pkg)
        }
    }

    private fun handleGestureDetection(pkg: String, detector: ReelDetector, root: AccessibilityNodeInfo, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        if (!detector.isFullScreenReelSurface(root)) return
        
        val now = System.currentTimeMillis()
        val lastCount = lastCountTimeByPackage[pkg] ?: 0L
        
        if (now - lastCount > DEBOUNCE_MS) {
            lastCountTimeByPackage[pkg] = now
            onNewReelDetected(pkg)
        }
    }

    private fun hideOverlayImmediate() {
        lastIsOnReel = false
        hideOverlayJob?.cancel()
        hardHideJob?.cancel()
        updateOverlayState(false)
    }

    private fun onNewReelDetected(pkg: String) {
        Log.i("ReelPulseService", "New Reel Detected for $pkg! Incrementing count.")
        serviceScope.launch {
            if (!::repository.isInitialized) return@launch
            repository.recordReel(ReelEvent(packageName = pkg, timestampMillis = System.currentTimeMillis()))
            
            val todayCount = repository.getTodayCount()
            val limit = prefs.getInt("daily_reel_limit", 50)
            val alertsEnabled = prefs.getBoolean("usage_alerts_enabled", true)
            
            val shouldNotify = alertsEnabled && (todayCount == limit || (todayCount > limit && (todayCount - limit) % 20 == 0))
            
            if (shouldNotify) {
                showLimitNotification(todayCount, limit)
            }
        }
    }

    private fun showLimitNotification(current: Int, limit: Int) {
        val channelId = "reel_pulse_alerts"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        val channel = NotificationChannel(channelId, "Usage Alerts", NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(channel)
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher) // Using launcher icon
            .setContentTitle("Daily Limit Reached!")
            .setContentText("You've watched $current reels today (Limit: $limit).")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
            
        notificationManager.notify(1001, notification)
    }

    private fun updateOverlayState(isOnReel: Boolean) {
        val userEnabled = prefs.getBoolean("show_floating_counter", false)
        val isTrackedPackage = currentPackage?.let { trackedPackages.contains(it) } ?: false

        if (windowManager == null) return

        if (userEnabled && isTrackedPackage && !isTrackingPaused.value) {
            hardHideJob?.cancel()
            showOverlay()
            isOnReelFlow.value = isOnReel
        } else {
            // Soft hide (fade out)
            isOnReelFlow.value = false
            
            // Hard hide (detach from window manager) after a delay to ensure it goes away
            if (hardHideJob?.isActive != true) {
                hardHideJob = serviceScope.launch(Dispatchers.Main) {
                    delay(10000) // Increased to 10s for stability
                    val stillNotTracked = currentPackage?.let { !trackedPackages.contains(it) } ?: true
                    if (stillNotTracked) {
                        hideOverlay()
                    }
                }
            }
        }
    }


    private fun showOverlay() {
        if (overlayView != null) return
        mainLooper.post {
            if (overlayView != null) return@post
            overlayView = ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@ReelAccessibilityService)
                setViewTreeViewModelStoreOwner(this@ReelAccessibilityService)
                setViewTreeSavedStateRegistryOwner(this@ReelAccessibilityService)
                
                setContent {
                    val visible by isOnReelFlow.collectAsState()
                    val todayCount by repository.todayCount().collectAsState(initial = 0)
                    
                    ReelPulseTheme(dynamicColor = false) {
                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            FloatingCounter(count = todayCount)
                        }
                    }
                }
            }
            try {
                windowManager?.addView(overlayView, overlayParams)
            } catch (e: Exception) {
                Log.e("ReelPulseService", "Error adding overlay", e)
            }
        }
    }

    private fun hideOverlay() {
        if (overlayView == null) return
        mainLooper.post {
            overlayView?.let {
                try {
                    windowManager?.removeView(it)
                } catch (_: Exception) { /* ignore */ }
                overlayView = null
            }
        }
    }

    private fun getRootNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (true) {
            val parent = current.parent ?: return current
            current = parent
        }
    }

    private val mainLooper = Handler(Looper.getMainLooper())

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
            // Trigger recovery notification if service is destroyed unexpectedly
            if (!isManualDisable) {
                showRecoveryNotification()
            }
        }
        hideOverlayImmediate()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        cleanupJob?.cancel()
        serviceScope.cancel()
    }

    private fun showRecoveryNotification() {
        val channelId = "reel_pulse_system"
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        
        val channel = NotificationChannel(channelId, "System Status", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)

        // Intent to open the app
        val appIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val appPendingIntent = PendingIntent.getActivity(
            this, 0, appIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // Intent to open accessibility settings
        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val settingsPendingIntent = PendingIntent.getActivity(
            this, 1, settingsIntent, PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Neural Shield Offline")
            .setContentText("Tap to re-activate your usage scanner.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(appPendingIntent)
            .addAction(R.drawable.ic_logo, "Enable Now", settingsPendingIntent)
            .setAutoCancel(true)
            .build()
            
        notificationManager.notify(RECOVERY_NOTIFICATION_ID, notification)
    }

    override fun onInterrupt() {}
}
