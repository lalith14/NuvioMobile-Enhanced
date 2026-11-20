package com.nuvio.app.core.ui.smooth

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Fork: Smoothness mode. When active, the app skips its most expensive drawing
 * (live liquid-glass blur and refraction, the per-card Glass UI backdrop slice and
 * text shadows) so scrolling has more headroom. It can be switched on by hand, or
 * automatically while the device is in Low Power Mode, has Reduce Transparency on,
 * or is running hot.
 */
object SmoothnessMode {
    private const val UserEnabledKey = "fork_smoothness_mode_enabled"
    private const val AutomaticKey = "fork_smoothness_mode_automatic"

    var userEnabled: Boolean by mutableStateOf(SmoothnessPlatform.loadBoolean(UserEnabledKey) ?: false)
        private set

    var automatic: Boolean by mutableStateOf(SmoothnessPlatform.loadBoolean(AutomaticKey) ?: true)
        private set

    /** Why the device currently asks for less work, or null when it doesn't. */
    var deviceReason: SmoothnessReason? by mutableStateOf(null)
        internal set

    val active: Boolean
        get() = userEnabled || (automatic && deviceReason != null)

    init {
        SmoothnessPlatform.startObserving { deviceReason = it }
    }

    fun updateUserEnabled(enabled: Boolean) {
        userEnabled = enabled
        SmoothnessPlatform.saveBoolean(UserEnabledKey, enabled)
    }

    fun updateAutomatic(enabled: Boolean) {
        automatic = enabled
        SmoothnessPlatform.saveBoolean(AutomaticKey, enabled)
    }
}

enum class SmoothnessReason(val label: String) {
    LowPower("Low Power Mode is on"),
    ReduceTransparency("Reduce Transparency is on"),
    Hot("Your phone is warm"),
}

internal expect object SmoothnessPlatform {
    fun loadBoolean(key: String): Boolean?
    fun saveBoolean(key: String, value: Boolean)

    /** Calls [onChange] now and whenever the device state changes, on the main thread. */
    fun startObserving(onChange: (SmoothnessReason?) -> Unit)
}
