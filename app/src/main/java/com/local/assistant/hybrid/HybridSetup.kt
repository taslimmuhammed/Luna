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
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
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
 *
 * Cloud and Auto run under the user's Google account, signed in to Firebase Authentication
 * ([signInWithGoogle]); Firebase AI Logic sends its ID token with each request. Nothing goes to
 * the cloud signed out ([ensureSignedIn]). Local chats never need an account.
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

    /** The Google account Cloud and Auto run under. */
    data class Account(val email: String?, val name: String?)

    private val _account = MutableStateFlow<Account?>(null)

    /** Who is signed in, or null: Cloud and Auto wait for an account. */
    val account: StateFlow<Account?> = _account.asStateFlow()

    /** A Google account is signed in (an anonymous one, from an earlier build, doesn't count). */
    val signedIn: Boolean get() = !configured || googleUser() != null

    /** A cloud request with no one signed in; the message is for the chat. */
    class SignInRequired : Exception("Sign in with Google to use Cloud and Auto: tap Cloud or Auto above, or go to Settings › Cloud. Local chats need no account.")

    /** How [signInWithGoogle] went: [Failed.message] is for the user; closing the picker is [Cancelled]. */
    sealed interface SignIn {
        data object Done : SignIn
        data object Cancelled : SignIn
        data class Failed(val message: String) : SignIn
    }

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
        // Signed in before (it lasts until sign-out or the app's data is cleared): shown from the start.
        runCatching { Firebase.auth.addAuthStateListener { _account.value = googleUser()?.let { Account(it.email, it.displayName) } } }
            .onFailure { Log.w(TAG, "Firebase Auth didn't start", it) }
        val config = Firebase.remoteConfig
        config.setDefaultsAsync(DEFAULTS)
        config.setConfigSettingsAsync(remoteConfigSettings { minimumFetchIntervalInSeconds = if (BuildConfig.DEBUG) 60 else 3600 })
        scope.launch {
            runCatching { config.fetchAndActivate().await() }.onFailure { Log.i(TAG, "Remote Config not fetched; defaults stand: ${it.javaClass.simpleName}") }
            refreshNano()
        }
    }

    private fun googleUser(): FirebaseUser? =
        runCatching { Firebase.auth.currentUser }.getOrNull()?.takeUnless { it.isAnonymous }

    /**
     * Before any request to Gemini in the cloud (Cloud chats, Auto's questions, web search):
     * throws [SignInRequired] with no Google account signed in. Firebase refreshes the ID token
     * itself, so once signed in this needs no connection of its own.
     */
    fun ensureSignedIn() {
        if (!signedIn) throw SignInRequired()
    }

    /**
     * Google's account picker, then Firebase Authentication with the account chosen. [activity]
     * shows the picker. The OAuth web client comes from `google-services.json` (the Google
     * provider turned on in the console, then the file downloaded again); without it, says so.
     */
    suspend fun signInWithGoogle(activity: Context): SignIn {
        if (!configured) return SignIn.Failed("Cloud mode isn't set up in this build.")
        val clientId = webClientId()
            ?: return SignIn.Failed("Google sign-in isn't set up in this build yet: turn on the Google provider in Firebase, then download google-services.json again.")
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            return SignIn.Cancelled
        } catch (e: NoCredentialException) {
            return SignIn.Failed("There's no Google account on this phone. Add one in Android's Settings, then try again.")
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Google sign-in failed: ${e.type}", e)
            return SignIn.Failed("Google sign-in didn't work (${e.type.substringAfterLast('.')}). Try again.")
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return SignIn.Failed("Google sign-in gave something Luna can't use. Try again.")
        }
        return try {
            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
            Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
            Log.i(TAG, "Signed in to Firebase with Google")
            SignIn.Done
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = (e as? FirebaseAuthException)?.errorCode ?: e.javaClass.simpleName
            Log.w(TAG, "Firebase didn't take the Google sign-in: $code", e)
            SignIn.Failed("Firebase didn't accept the Google sign-in ($code).")
        }
    }

    /** Signs out of Firebase, and forgets the account chosen so the picker asks next time. */
    suspend fun signOut(context: Context) {
        if (!configured) return
        runCatching { Firebase.auth.signOut() }
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
            .onFailure { Log.i(TAG, "Couldn't clear the chosen account: ${it.javaClass.simpleName}") }
    }

    /** The OAuth web client the google-services plugin writes from the JSON, when it has one. */
    private fun webClientId(): String? {
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) null else context.getString(id).takeIf { it.isNotBlank() }
    }

    /** Cloud mode can be used: Firebase is set up and Remote Config hasn't turned it off. */
    val cloudAvailable: Boolean
        get() = configured && runCatching { Firebase.remoteConfig.getBoolean(KEY_CLOUD_ENABLED) }.getOrDefault(true)

    /** The Gemini model Cloud chats use. */
    val cloudModel: String
        get() = config(KEY_CLOUD_MODEL, DEFAULT_CLOUD_MODEL)

    /**
     * How many questions Auto chats may send Gemini a day ([AutoRouter]): past it, they answer on
     * the phone. Remote Config's, so it can be raised for a demo without a new APK.
     */
    val autoDailyAsks: Int
        get() = config(KEY_AUTO_DAILY_ASKS, DEFAULT_AUTO_DAILY_ASKS.toString()).toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_AUTO_DAILY_ASKS

    /** The smaller Gemini for one-shot work in the cloud (reading a document sent in a Cloud chat). */
    val liteModel: String
        get() = config(KEY_LITE_MODEL, DEFAULT_LITE_MODEL)

    /**
     * Which Gemini Nano: the preview (Nano 4, on phones enrolled in AICore's preview) or the stable
     * one. Remote Config can say; when it doesn't, whichever the phone has ([refreshNano]). The
     * test phone, in the preview, ran Nano 4 while the stable one was only downloadable, so with
     * the stable one fixed a Cloud chat that couldn't reach Gemini had no Nano to fall back on.
     */
    val nanoOption: OnDeviceModelOption
        get() = when (config(KEY_NANO_OPTION, "")) {
            "preview" -> OnDeviceModelOption.PREVIEW
            "preview_fast" -> OnDeviceModelOption.PREVIEW_FAST
            "stable" -> OnDeviceModelOption.STABLE
            else -> foundOption
        }

    /** The Nano the phone has, as [refreshNano] last found; the stable one until it looks. */
    @Volatile private var foundOption: OnDeviceModelOption = OnDeviceModelOption.STABLE

    private fun config(key: String, default: String): String =
        if (!configured) default else runCatching { Firebase.remoteConfig.getString(key) }.getOrNull()?.takeIf { it.isNotBlank() } ?: default

    fun ai(): FirebaseAI = Firebase.ai(backend = GenerativeBackend.googleAI())

    /** A model that only ever runs on the phone: Gemini Nano, or nothing. */
    fun nanoModel(maxOutputTokens: Int = NANO_MAX_OUTPUT, temperature: Float = 0.4f, option: OnDeviceModelOption = nanoOption): GenerativeModel = ai().generativeModel(
        modelName = cloudModel,
        onDeviceConfig = OnDeviceConfig(
            mode = InferenceMode.ONLY_ON_DEVICE,
            maxOutputTokens = maxOutputTokens,
            temperature = temperature,
            modelOption = option,
        ),
    )

    /** Asks AICore what it has of Nano. */
    suspend fun refreshNano(): Nano {
        if (!configured) return Nano.UNAVAILABLE.also { _nano.value = it }
        suspend fun statusOf(option: OnDeviceModelOption) = runCatching { nanoModel(option = option).onDeviceExtension?.checkStatus() }
            .onFailure { Log.i(TAG, "Nano status unknown: ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
        var status = statusOf(nanoOption)
        // Not set in Remote Config, and no stable Nano yet: the preview one, if the phone runs it.
        if (status != OnDeviceModelStatus.AVAILABLE && config(KEY_NANO_OPTION, "").isEmpty() && foundOption == OnDeviceModelOption.STABLE) {
            val preview = statusOf(OnDeviceModelOption.PREVIEW)
            if (preview == OnDeviceModelStatus.AVAILABLE) {
                Log.i(TAG, "No stable Gemini Nano here; using the preview one")
                foundOption = OnDeviceModelOption.PREVIEW
                status = preview
            }
        }
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
        const val KEY_AUTO_DAILY_ASKS = "auto_daily_asks"
        const val DEFAULT_AUTO_DAILY_ASKS = 40

        /**
         * The lighter model for everything in the cloud for now: the user asked on 26 Nov 2026,
         * gemini-3.8-flash spending too many tokens. Remote Config's `cloud_model` still wins when
         * set, to go back without a new APK.
         */
        const val DEFAULT_CLOUD_MODEL = "gemini-3.5-flash-lite"
        const val DEFAULT_LITE_MODEL = "gemini-3.5-flash-lite"

        /** What Nano may write in one go. */
        const val NANO_MAX_OUTPUT = 512

        private val DEFAULTS: Map<String, Any> = mapOf(
            KEY_CLOUD_ENABLED to true,
            KEY_CLOUD_MODEL to DEFAULT_CLOUD_MODEL,
            KEY_LITE_MODEL to DEFAULT_LITE_MODEL,
            KEY_NANO_OPTION to "stable",
            KEY_AUTO_DAILY_ASKS to DEFAULT_AUTO_DAILY_ASKS.toString(),
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
