package com.local.assistant.hybrid

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager

/**
 * What the phone and the cloud allow an Auto chat right now ([AutoRouter.Signals]): a network
 * that reaches the internet, Cloud mode set up and on, questions left today, and whether the
 * phone is hot or low on battery (the router then leaves more to Gemini). Read fresh for each
 * message: each is a cheap system call.
 */
class HybridSignals(
    private val context: Context,
    private val setup: HybridSetup,
    private val ask: CloudAsk,
) {

    fun now(): AutoRouter.Signals = AutoRouter.Signals(
        online = online(),
        cloudReady = setup.cloudAvailable,
        asksLeft = ask.asksLeft(),
        hot = hot(),
        lowBattery = lowBattery(),
    )

    private fun online(): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(true)

    /** Moderate or worse: the phone's model slows down here, and heats it further. */
    private fun hot(): Boolean = runCatching {
        (context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) >= PowerManager.THERMAL_STATUS_MODERATE
    }.getOrDefault(false)

    private fun lowBattery(): Boolean = runCatching {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        level >= 0 && !plugged && level * 100 / scale < LOW_BATTERY_PERCENT
    }.getOrDefault(false)

    private companion object {
        const val LOW_BATTERY_PERCENT = 15
    }
}
