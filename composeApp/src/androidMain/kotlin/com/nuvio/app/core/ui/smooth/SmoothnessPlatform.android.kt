package com.nuvio.app.core.ui.smooth

// Fork: the fork only ships on iOS, so Android keeps the setting in memory and never
// switches on automatically.
internal actual object SmoothnessPlatform {
    private val values = mutableMapOf<String, Boolean>()

    actual fun loadBoolean(key: String): Boolean? = values[key]

    actual fun saveBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    actual fun startObserving(onChange: (SmoothnessReason?) -> Unit) {
        onChange(null)
    }
}
