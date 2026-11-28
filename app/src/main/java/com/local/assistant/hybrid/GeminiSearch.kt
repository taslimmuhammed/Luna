@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package com.local.assistant.hybrid

import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.generationConfig
import com.local.assistant.memory.tools.WebResult
import com.local.assistant.memory.tools.WebResults
import com.local.assistant.memory.tools.WebSearch
import com.local.assistant.memory.tools.WebSearchError
import kotlinx.coroutines.CancellationException

/**
 * web_lookup for Cloud chats: Gemini with Grounding with Google Search, in a request of its own
 * (a chat's request has its function tools, which a search tool doesn't sit beside), so a Cloud
 * chat can look things up without the user setting up a search key. Only the model's query goes.
 * Local chats keep their own search (Tavily, with the user's key) or none.
 */
class GeminiSearch(
    private val setup: HybridSetup,
    private val ledger: CloudLedger,
    /** The user's own search, used instead when they have set one up. */
    private val own: WebSearch? = null,
) : WebSearch {

    override val enabled: Boolean get() = own?.enabled == true || setup.cloudAvailable

    override suspend fun search(query: String, news: Boolean): WebResults {
        own?.takeIf { it.enabled }?.let { return it.search(query, news) }
        val prompt = "Search the web and answer in at most 4 short sentences, with the facts and dates that matter" +
            (if (news) ", newest first" else "") + ": $query"
        // Before the ledger: a search with no one signed in sent nothing.
        try {
            setup.ensureSignedIn()
        } catch (e: HybridSetup.SignInRequired) {
            throw WebSearchError("The web search needs the user signed in with Google. Answer from what you know, and say it may be out of date.")
        }
        ledger.record(CloudLedger.Entry(System.currentTimeMillis(), null, CloudLedger.Kind.SEARCH.key, setup.liteModel, query))
        val response = try {
            setup.ai().generativeModel(
                modelName = setup.liteModel,
                generationConfig = generationConfig { maxOutputTokens = 1_024 },
                tools = listOf(Tool.googleSearch()),
            ).generateContent(prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WebSearchError(if (FirebaseBackend.unreachable(e)) "The web search couldn't reach the internet. Say so." else "The web search failed. Answer from what you know, and say it may be out of date.")
        }
        val chunks = response.candidates.firstOrNull()?.groundingMetadata?.groundingChunks.orEmpty()
        val results = chunks.mapNotNull { chunk ->
            val web = chunk.web ?: return@mapNotNull null
            // Grounding links are short-lived redirects through Google: the site itself is what's shown and opened.
            val site = web.domain ?: web.title?.takeIf { '.' in it && ' ' !in it }
            val url = site?.let { "https://$it" } ?: web.uri ?: return@mapNotNull null
            WebResult(title = web.title ?: site ?: url, url = url, snippet = "")
        }.distinctBy { it.url }
        return WebResults(answer = response.text?.trim(), results = results)
    }
}
