@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package com.local.assistant.hybrid

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.DownloadStatus
import com.google.firebase.ai.FirebaseAI
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.InferenceMode
import com.google.firebase.ai.OnDeviceConfig
import com.google.firebase.ai.OnDeviceModelOption
import com.google.firebase.ai.OnDeviceModelStatus
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import com.local.assistant.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Firebase AI Logic, as far as this app uses it: Gemini in the cloud for Cloud chats, and Gemini
 * Nano on the phone (Android AICore) where the phone has it.
 *
 * Nothing here runs without a `google-services.json` in the build ([configured]); the app is then
 * the on-device app it always was, and Cloud mode says it isn't set up. With one, every request
 * carries an App Check token (Play Integrity, or the debug provider in debug and demo builds), and
 * which model answers is Remote Config's to say, so it can change without a release.
 */
class HybridSetup(private val context: Context, private val scope: CoroutineScope) {

    /** Firebase is in this build and started. */
    val configured: Boolean by lazy {
        BuildConfig.FIREBASE_CONFIGURED && runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
    }

    /** What the phone has of Gemini Nano. */
    enum class Nano { UNKNOWN, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

    private val _nano = MutableStateFlow(Nano.UNKNOWN)
    val nano: StateFlow<Nano> = _nano.asStateFlow()

    private val _nanoProgress = MutableStateFlow<Float?>(null)

    /** How far a download of Nano is, 0 to 1, while there is one. */
    val nanoProgress: StateFlow<Float?> = _nanoProgress.asStateFlow()

    private var download: Job? = null

    /** Once, at start: App Check first, so no request ever goes without it. */
    fun start() {
        if (!configured) {
            Log.i(TAG, "No google-services.json in this build: Cloud mode is off")
            return
        }
        val token = BuildConfig.APP_CHECK_DEBUG_TOKEN
        val debug = BuildConfig.DEBUG || token.isNotEmpty()
        if (token.isNotEmpty()) {
            // The demo APK's token, registered in the Firebase console. The debug provider reads it
            // from where it keeps its own; its R.string route works only in debuggable builds,
            // and a debuggable demo would write chats to the log (LogText).
            runCatching {
                val key = FirebaseApp.getInstance().persistenceKey
                context.getSharedPreferences("com.google.firebase.appcheck.debug.store.$key", Context.MODE_PRIVATE)
                    .edit().putString("com.google.firebase.appcheck.debug.DEBUG_SECRET", token).apply()
            }.onFailure { Log.w(TAG, "Could not set the demo's App Check token", it) }
        }
        Firebase.appCheck.installAppCheckProviderFactory(
            if (debug) DebugAppCheckProviderFactory.getInstance() else PlayIntegrityAppCheckProviderFactory.getInstance(),
        )
        val config = Firebase.remoteConfig
        config.setDefaultsAsync(DEFAULTS)
        config.setConfigSettingsAsync(remoteConfigSettings { minimumFetchIntervalInSeconds = if (BuildConfig.DEBUG) 60 else 3600 })
        scope.launch {
            runCatching { config.fetchAndActivate().await() }.onFailure { Log.i(TAG, "Remote Config not fetched; defaults stand: ${it.javaClass.simpleName}") }
            refreshNano()
        }
    }

    /** Cloud mode can be used: Firebase is set up and Remote Config hasn't turned it off. */
    val cloudAvailable: Boolean
        get() = configured && runCatching { Firebase.remoteConfig.getBoolean(KEY_CLOUD_ENABLED) }.getOrDefault(true)

    /** The Gemini model Cloud chats use. */
    val cloudModel: String
        get() = config(KEY_CLOUD_MODEL, DEFAULT_CLOUD_MODEL)

    /** The smaller Gemini for one-shot work in the cloud (reading a document sent in a Cloud chat). */
    val liteModel: String
        get() = config(KEY_LITE_MODEL, DEFAULT_LITE_MODEL)

    /** Which Gemini Nano: the preview (Nano 4, on phones enrolled in AICore's preview) or the stable one. */
    val nanoOption: OnDeviceModelOption
        get() = when (config(KEY_NANO_OPTION, "stable")) {
            "preview" -> OnDeviceModelOption.PREVIEW
            "preview_fast" -> OnDeviceModelOption.PREVIEW_FAST
            else -> OnDeviceModelOption.STABLE
        }

    private fun config(key: String, default: String): String =
        if (!configured) default else runCatching { Firebase.remoteConfig.getString(key) }.getOrNull()?.takeIf { it.isNotBlank() } ?: default

    fun ai(): FirebaseAI = Firebase.ai(backend = GenerativeBackend.googleAI())

    /** A model that only ever runs on the phone: Gemini Nano, or nothing. */
    fun nanoModel(maxOutputTokens: Int = NANO_MAX_OUTPUT, temperature: Float = 0.4f): GenerativeModel = ai().generativeModel(
        modelName = cloudModel,
        onDeviceConfig = OnDeviceConfig(
            mode = InferenceMode.ONLY_ON_DEVICE,
            maxOutputTokens = maxOutputTokens,
            temperature = temperature,
            modelOption = nanoOption,
        ),
    )

    /** Asks AICore what it has of Nano. */
    suspend fun refreshNano(): Nano {
        if (!configured) return Nano.UNAVAILABLE.also { _nano.value = it }
        val status = runCatching { nanoModel().onDeviceExtension?.checkStatus() }
            .onFailure { Log.i(TAG, "Nano status unknown: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        val nano = when (status) {
            OnDeviceModelStatus.AVAILABLE -> Nano.AVAILABLE
            OnDeviceModelStatus.DOWNLOADABLE -> Nano.DOWNLOADABLE
            OnDeviceModelStatus.DOWNLOADING -> Nano.DOWNLOADING
            else -> Nano.UNAVAILABLE
        }
        if (download?.isActive != true) _nano.value = nano
        return nano
    }

    /** Has AICore download Nano, where the phone can run it. */
    fun downloadNano() {
        if (!configured || download?.isActive == true) return
        download = scope.launch {
            val extension = runCatching { nanoModel().onDeviceExtension }.getOrNull() ?: return@launch
            _nano.value = Nano.DOWNLOADING
            runCatching {
                extension.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadInProgress -> _nanoProgress.value = null
                        is DownloadStatus.DownloadCompleted -> _nanoProgress.value = 1f
                        is DownloadStatus.DownloadFailed -> Log.w(TAG, "Nano download failed")
                        else -> Unit
                    }
                }
            }.onFailure { Log.w(TAG, "Nano download failed", it) }
            _nanoProgress.value = null
            refreshNano()
        }
    }

    companion object {
        private const val TAG = "HybridSetup"

        const val KEY_CLOUD_ENABLED = "cloud_enabled"
        const val KEY_CLOUD_MODEL = "cloud_model"
        const val KEY_LITE_MODEL = "lite_model"
        const val KEY_NANO_OPTION = "nano_model_option"

        const val DEFAULT_CLOUD_MODEL = "gemini-3.8-flash"
        const val DEFAULT_LITE_MODEL = "gemini-3.5-flash-lite"

        /** What Nano may write in one go. */
        const val NANO_MAX_OUTPUT = 512

        private val DEFAULTS: Map<String, Any> = mapOf(
            KEY_CLOUD_ENABLED to true,
            KEY_CLOUD_MODEL to DEFAULT_CLOUD_MODEL,
            KEY_LITE_MODEL to DEFAULT_LITE_MODEL,
            KEY_NANO_OPTION to "stable",
        )
    }
}

/** A Play services task, waited for. */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}
