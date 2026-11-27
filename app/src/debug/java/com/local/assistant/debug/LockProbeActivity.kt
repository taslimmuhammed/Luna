package com.local.assistant.debug

import android.app.KeyguardManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.local.assistant.llm.AiCoreBackend
import com.local.assistant.llm.Sampling
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug builds only. Whether Gemini Nano answers while one of the app's screens is shown over the
 * lock screen, as the overlay is: asks Nano for one word and writes what came back to
 * `files/lock-probe.md`. Lock the phone, then
 *
 *   adb shell am start -n com.local.assistant.dev/com.local.assistant.debug.LockProbeActivity [--ei wait 10]
 *
 * `wait`: seconds before asking, to turn the screen off in between. `stay`: seconds to stay
 * shown afterwards, for a chat sent meanwhile (the probe's `--es mode cloud --es chatmode local`)
 * to be made over the lock screen as the overlay's are.
 */
class LockProbeActivity : ComponentActivity() {

    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
    }

    override fun onResume() {
        super.onResume()
        if (asked) return
        asked = true
        val file = File(filesDir, "lock-probe.md").also { it.writeText("") }
        fun log(line: String) {
            Log.i(TAG, line)
            file.appendText(line + "\n")
        }
        lifecycleScope.launch {
            delay(intent.getIntExtra("wait", 0) * 1000L)
            log("# Lock probe, ${java.util.Date()}")
            log("locked=${getSystemService(KeyguardManager::class.java).isKeyguardLocked} screenOn=${getSystemService(PowerManager::class.java).isInteractive} resumed=${lifecycle.currentState}")
            val started = SystemClock.elapsedRealtime()
            val reply = runCatching { AiCoreBackend().complete("Answer in one word.", "Say hi.", null, 16, Sampling.BACKGROUND) }
            log("Nano in ${SystemClock.elapsedRealtime() - started} ms -> ${reply.getOrElse { "ERROR ${it.javaClass.simpleName}: ${it.message}" }.take(120)}")
            log("PROBE DONE")
            delay(intent.getIntExtra("stay", 0) * 1000L)
            finish()
        }
    }

    private companion object {
        const val TAG = "LockProbe"
    }
}
