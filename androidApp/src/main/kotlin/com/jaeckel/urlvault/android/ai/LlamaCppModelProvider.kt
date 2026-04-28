package com.jaeckel.urlvault.android.ai

import android.util.Log
import com.jaeckel.urlvault.ai.LocalModelProvider
import com.jaeckel.urlvault.ai.ModelRuntime
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "LlamaCppModelProvider"
private const val MAX_PAGE_CONTENT_LENGTH = 800
private const val MAX_DESCRIPTION_LENGTH = 300

/**
 * `LocalModelProvider` backed by a downloaded GGUF run through a llama.cpp
 * native wrapper (see [LlamaCppNativeBridge]).
 *
 * Plain free-text prompts only — Llamatik's grammar-constrained JSON entry
 * was removed (see ferranpons/Llamatik#90); structured-output models will
 * use a separate runtime (LeapSDK) once wired up.
 */
class LlamaCppModelProvider(
    override val id: String,
    override val displayName: String,
    private val modelFile: String,
    private val bridge: LlamaCppNativeBridge,
    httpClient: HttpClient,
) : LocalModelProvider {

    override val runtime: ModelRuntime = ModelRuntime.LLAMA_CPP

    private val contentExtractor = WebPageContentExtractor(httpClient)
    private val mutex = Mutex()

    override suspend fun isReady(): Boolean = bridge.isAvailable()

    override suspend fun preload() {
        // The Llamatik bridge holds a singleton model. Activating one
        // provider clears another, so warming a second provider before the
        // first request would swap the active singleton — matches the
        // radio-button activation semantics described in MainActivity.
        mutex.withLock { ensureLoaded() }
    }

    /**
     * Always delegates to the bridge. The bridge owns the singleton
     * "currently-loaded model" state — a per-provider `loaded` flag would
     * desynchronize when a second provider's load swaps the singleton.
     */
    private suspend fun ensureLoaded() {
        bridge.load(modelFile)
    }

    override suspend fun generateTags(url: String, title: String, content: String): Result<List<String>> = runCatching {
        val pageContent = runCatching { contentExtractor.extract(url) }.getOrNull()
        val pageSummary = pageContent?.bestSummary(MAX_PAGE_CONTENT_LENGTH).orEmpty()

        val prompt = buildString {
            appendLine("You are a helpful assistant that generates tags for bookmarks.")
            appendLine("Your task is to provide 3 to 6 short, descriptive, lowercase tags.")
            appendLine("Return ONLY a comma-separated list of tags. Do not include any other text or explanation.")
            appendLine("Example output: android, kotlin, development, security")
            appendLine()
            appendLine("Use the following data for context:")
            appendLine("URL: $url")
            if (title.isNotBlank()) appendLine("Title: $title")
            if (content.isNotBlank()) appendLine("User description: $content")
            if (pageSummary.isNotBlank()) appendLine("Page summary: $pageSummary")
        }
        val text = mutex.withLock {
            ensureLoaded()
            bridge.generate(prompt, maxTokens = 96)
        }
        Log.v(TAG, "[$id] tags raw: $text")
        parseTagsFreeText(text)
    }

    override suspend fun generateDescription(url: String, title: String): Result<String> = runCatching {
        val pageContent = runCatching { contentExtractor.extract(url) }.getOrNull()
        val pageSummary = pageContent?.bestSummary(MAX_PAGE_CONTENT_LENGTH).orEmpty()

        val prompt = buildString {
            appendLine("Write a 1-2 sentence factual description for this bookmark.")
            appendLine("Return ONLY the description, nothing else.")
            appendLine()
            appendLine("Context data:")
            appendLine("URL: $url")
            if (title.isNotBlank()) appendLine("Title: $title")
            if (pageSummary.isNotBlank()) {
                appendLine("Page summary: $pageSummary")
            } else {
                appendLine("If you cannot determine what the page is about, respond with: Unable to generate description.")
            }
        }
        val text = mutex.withLock {
            ensureLoaded()
            bridge.generate(prompt, maxTokens = 96)
        }
        validateDescription(text.trim())
    }

    override suspend fun generateTitle(url: String): Result<String> = runCatching {
        val pageContent = runCatching { contentExtractor.extract(url) }.getOrNull()
            ?: error("Could not fetch page content")
        val nativeTitle = pageContent.bestTitle()
        if (!nativeTitle.isNullOrBlank()) return@runCatching nativeTitle

        val pageSummary = pageContent.bestSummary(MAX_PAGE_CONTENT_LENGTH)
        val prompt = buildString {
            appendLine("Generate a short, descriptive title for this bookmark (max 6 words).")
            appendLine("Return ONLY the title, nothing else.")
            appendLine()
            appendLine("Context data:")
            appendLine("URL: $url")
            if (pageSummary.isNotBlank()) appendLine("Page summary: $pageSummary")
        }
        val text = mutex.withLock {
            ensureLoaded()
            bridge.generate(prompt, maxTokens = 96)
        }
        text.trim().removeSurrounding("\"")
    }

    private fun parseTagsFreeText(text: String): List<String> {
        return text.split(Regex("[,\\n;]+"))
            .map { raw ->
                raw.trim().lowercase()
                    .replace(Regex("[^a-z0-9\\s-]"), "").trim()
            }
            .filter { it.isNotBlank() && it.length in 2..30 }
            .distinct()
            .take(6)
            .ifEmpty { error("Model returned no usable tags") }
    }

    suspend fun close() {
        mutex.withLock {
            runCatching { bridge.unload() }
            contentExtractor.close()
        }
    }

    private fun validateDescription(text: String): String {
        val truncated = if (text.length > MAX_DESCRIPTION_LENGTH) text.take(MAX_DESCRIPTION_LENGTH) else text
        if (Regex("https?://\\S+").containsMatchIn(truncated)) {
            return Regex("https?://\\S+").replace(truncated, "").trim()
        }
        return truncated
    }
}
