package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import com.nuvio.app.core.ui.smooth.SmoothnessMode

// Fork: Settings > Layout > Performance.
internal fun LazyListScope.smoothnessSettingsContent(isTablet: Boolean) {
    item {
        val reason = SmoothnessMode.deviceReason
        val autoNote = when {
            SmoothnessMode.userEnabled -> null
            SmoothnessMode.automatic && reason != null -> "On now: ${reason.label}."
            else -> null
        }
        SettingsSection(
            title = "PERFORMANCE",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Smoothness mode",
                    description = "Smoother scrolling. Swaps the live glass blur and text shadows for a " +
                        "lighter frosted look. Your glass settings are kept for when you turn it off.",
                    checked = SmoothnessMode.userEnabled,
                    isTablet = isTablet,
                    onCheckedChange = SmoothnessMode::updateUserEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = "Turn on automatically",
                    description = listOfNotNull(
                        "When your iPhone is in Low Power Mode, has Reduce Transparency on, or gets warm.",
                        autoNote,
                    ).joinToString(" "),
                    checked = SmoothnessMode.automatic,
                    enabled = !SmoothnessMode.userEnabled,
                    isTablet = isTablet,
                    onCheckedChange = SmoothnessMode::updateAutomatic,
                )
            }
        }
    }
}
