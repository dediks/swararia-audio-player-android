package com.swararia.audiolab.foh

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.swararia.audiolab.PlaybackPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform-agnostic contract for reading/updating FOH Safe Mode settings and launching OS policy screens.
 */
internal interface FohSafeModeStore {
    val settings: StateFlow<FohSafeModeSettings>
    fun isNotificationPolicyGranted(): Boolean
    fun updateSettings(transform: (FohSafeModeSettings) -> FohSafeModeSettings): FohSafeModeSettings
    fun setEnabled(enabled: Boolean): FohSafeModeSettings
    fun refreshPolicySync()
    fun openNotificationPolicySettings()
    fun openSamsungSeparateAppSound()
}

/**
 * Owns persistent FOH Safe Mode configuration and best-effort OS Do-Not-Disturb transitions.
 *
 * On Android 15+ (API 35+), `setInterruptionFilter` toggles an app-owned implicit Zen Rule per
 * platform rules rather than mutating unrelated user DND schedules.
 */
class FohSafeModeController(context: Context) : FohSafeModeStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PlaybackPreferences.PREFS_NAME, Context.MODE_PRIVATE)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)

    private val mutableSettings = MutableStateFlow(readSettings())
    override val settings: StateFlow<FohSafeModeSettings> = mutableSettings.asStateFlow()

    fun current(): FohSafeModeSettings = mutableSettings.value

    override fun isNotificationPolicyGranted(): Boolean =
        notificationManager?.isNotificationPolicyAccessGranted == true

    override fun updateSettings(transform: (FohSafeModeSettings) -> FohSafeModeSettings): FohSafeModeSettings {
        val next = transform(mutableSettings.value)
        prefs.edit()
            .putBoolean(KEY_SAFE_ENABLED, next.enabled)
            .putBoolean(KEY_AUTO_DND, next.autoDnd)
            .putBoolean(KEY_FOCUS_LOCK, next.focusLock)
            .apply()
        mutableSettings.value = next
        syncSystemDnd(next)
        return next
    }

    override fun setEnabled(enabled: Boolean): FohSafeModeSettings =
        updateSettings { it.copy(enabled = enabled) }

    fun toggleEnabled(): FohSafeModeSettings =
        updateSettings { it.copy(enabled = !it.enabled) }

    /** Re-syncs DND state after returning from system Notification Policy settings. */
    override fun refreshPolicySync() {
        syncSystemDnd(mutableSettings.value)
    }

    override fun openNotificationPolicySettings() {
        Companion.openNotificationPolicySettings(appContext)
    }

    override fun openSamsungSeparateAppSound() {
        Companion.openSamsungSeparateAppSound(appContext)
    }

    private fun syncSystemDnd(current: FohSafeModeSettings) {
        val manager = notificationManager ?: return
        val granted = manager.isNotificationPolicyAccessGranted
        if (!granted) {
            if (prefs.getBoolean(KEY_APPLIED_DND, false)) {
                prefs.edit().putBoolean(KEY_APPLIED_DND, false).apply()
            }
            return
        }
        if (FohSafeModeRules.shouldApplySystemDnd(current, policyAccessGranted = true)) {
            if (!prefs.getBoolean(KEY_APPLIED_DND, false)) {
                prefs.edit()
                    .putInt(KEY_PREV_FILTER, manager.currentInterruptionFilter)
                    .putBoolean(KEY_APPLIED_DND, true)
                    .apply()
            }
            if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALARMS) {
                runCatching { manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS) }
            }
        } else if (prefs.getBoolean(KEY_APPLIED_DND, false)) {
            val restoreFilter = if (Build.VERSION.SDK_INT >= 35) {
                NotificationManager.INTERRUPTION_FILTER_ALL
            } else {
                prefs.getInt(KEY_PREV_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
                    .takeIf { it != NotificationManager.INTERRUPTION_FILTER_ALARMS }
                    ?: NotificationManager.INTERRUPTION_FILTER_ALL
            }
            prefs.edit().putBoolean(KEY_APPLIED_DND, false).apply()
            runCatching { manager.setInterruptionFilter(restoreFilter) }
        }
    }

    private fun readSettings(): FohSafeModeSettings =
        FohSafeModeSettings(
            enabled = prefs.getBoolean(KEY_SAFE_ENABLED, false),
            autoDnd = prefs.getBoolean(KEY_AUTO_DND, true),
            focusLock = prefs.getBoolean(KEY_FOCUS_LOCK, true),
        )

    companion object {
        const val KEY_SAFE_ENABLED = "foh_safe_mode_enabled"
        const val KEY_AUTO_DND = "foh_safe_auto_dnd"
        const val KEY_FOCUS_LOCK = "foh_safe_focus_lock"
        private const val KEY_APPLIED_DND = "foh_safe_applied_dnd"
        private const val KEY_PREV_FILTER = "foh_safe_prev_filter"

        fun openNotificationPolicySettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }

        fun openSamsungSeparateAppSound(context: Context) {
            val candidates = listOf(
                Intent().setComponent(
                    ComponentName(
                        "com.samsung.android.setting.multisound",
                        "com.samsung.android.setting.multisound.MultiSoundSettingsActivity",
                    ),
                ),
                Intent("com.samsung.settings.MULTISOUND"),
                Intent(Settings.ACTION_SOUND_SETTINGS),
            )
            for (candidate in candidates) {
                candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (runCatching { context.startActivity(candidate) }.isSuccess) return
            }
        }
    }
}
