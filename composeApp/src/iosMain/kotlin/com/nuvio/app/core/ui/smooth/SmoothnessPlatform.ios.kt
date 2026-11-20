package com.nuvio.app.core.ui.smooth

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSProcessInfoPowerStateDidChangeNotification
import platform.Foundation.NSProcessInfoThermalStateCritical
import platform.Foundation.NSProcessInfoThermalStateDidChangeNotification
import platform.Foundation.NSProcessInfoThermalStateSerious
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIAccessibilityIsReduceTransparencyEnabled
import platform.UIKit.UIAccessibilityReduceTransparencyStatusDidChangeNotification

internal actual object SmoothnessPlatform {
    private var observing = false

    actual fun loadBoolean(key: String): Boolean? {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(key) == null) null else defaults.boolForKey(key)
    }

    actual fun saveBoolean(key: String, value: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(value, forKey = key)
    }

    actual fun startObserving(onChange: (SmoothnessReason?) -> Unit) {
        onChange(currentReason())
        if (observing) return
        observing = true
        val center = NSNotificationCenter.defaultCenter
        listOf(
            NSProcessInfoPowerStateDidChangeNotification,
            NSProcessInfoThermalStateDidChangeNotification,
            UIAccessibilityReduceTransparencyStatusDidChangeNotification,
        ).forEach { name ->
            center.addObserverForName(
                name = name,
                `object` = null,
                queue = NSOperationQueue.mainQueue,
            ) { _ -> onChange(currentReason()) }
        }
    }

    private fun currentReason(): SmoothnessReason? {
        val info = NSProcessInfo.processInfo
        val thermal = info.thermalState
        return when {
            info.lowPowerModeEnabled -> SmoothnessReason.LowPower
            thermal == NSProcessInfoThermalStateSerious || thermal == NSProcessInfoThermalStateCritical -> SmoothnessReason.Hot
            UIAccessibilityIsReduceTransparencyEnabled() -> SmoothnessReason.ReduceTransparency
            else -> null
        }
    }
}
