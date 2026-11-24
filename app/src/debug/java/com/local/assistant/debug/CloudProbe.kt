package com.local.assistant.debug

import android.os.SystemClock
import com.local.assistant.AppContainer
import com.local.assistant.chat.ChatSender
import com.local.assistant.memory.tools.MemoryChip
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug builds only: one Cloud message through the app's own send path ([ChatSender]), in a
 * hidden chat, with each step and the reply timed. For "Cloud mode thinks and never answers".
 *
 *   adb shell "am broadcast -a com.local.assistant.debug.MODEL_PROBE -p com.local.assistant.dev --es mode cloud [--es text '…'] [--el chat N]"
 */
class CloudProbe(private val container: AppContainer, private val log: (String) -> Unit) {

    suspend fun run(text: String, chatId: Long? = null) = coroutineScope {
        val setup = container.hybridSetup
        log("# Cloud probe, ${java.util.Date()}")
        log("- configured: ${setup.configured}, available: ${setup.cloudAvailable}, model: ${setup.cloudModel}, lite: ${setup.liteModel}")
        val started = SystemClock.elapsedRealtime()
        fun at() = "${SystemClock.elapsedRealtime() - started} ms"
        val finished = CompletableDeferred<Pair<String, String?>>()
        val watcher = launch {
            var last: String? = null
            container.chatSender.live.collect { live ->
                val now = live?.let { "step=${it.step} text=${it.text.length} chars" } ?: "idle"
                if (now != last) log("  ${at()}: $now")
                last = now
            }
        }
        val sent = container.chatSender.send(
            chatId = chatId,
            text = text,
            attachment = null,
            owner = this@CloudProbe,
            listener = object : ChatSender.Listener {
                override fun onChatCreated(chatId: Long) = log("  ${at()}: chat $chatId created")
                override fun onUserMessageStored(messageId: Long) = log("  ${at()}: message $messageId stored")
                override fun onMemory(chip: MemoryChip) = log("  ${at()}: chip ${chip.kind} ${chip.label}")
                override fun onFinished(reply: String, failure: String?) {
                    finished.complete(reply to failure)
                }
            },
            hidden = true,
            toCloud = true,
        )
        log("- sent: $sent")
        val result = withTimeoutOrNull(TIMEOUT_MS) { finished.await() }
        watcher.cancel()
        if (result == null) {
            log("- NO ANSWER in ${TIMEOUT_MS / 1000} s; still: ${container.chatSender.live.value}")
            container.chatSender.stop()
        } else {
            log("- finished at ${at()}; failure: ${result.second}")
            log("- reply: ${result.first.replace("\n", " ").take(300)}")
        }
        log("- served by: ${container.cloudBackend.lastServedBy.value}, stats: ${container.cloudBackend.lastStats.value}")
    }

    private companion object {
        const val TIMEOUT_MS = 90_000L
    }
}
