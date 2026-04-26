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
 * Prompt templates intentionally mirror those in `AICoreService` so output
 * shape is comparable between Gemini Nano and any open-source GGUF.
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
    private var loaded = false

    override suspend fun isReady(): Boolean = bridge.isAvailable()

    private suspend fun ensureLoaded() {
        if (loaded) return
        bridge.load(modelFile)
        loaded = true
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
            bridge.generate(prompt, maxTokens = 256)
        }

        Log.v(TAG, "[$id] tags raw: $text")

        text.split(Regex("[,\\n;]+"))
            .map { raw ->
                raw.trim().lowercase()
                    .replace(Regex("[^a-z0-9\\s-]"), "").trim()
            }
            .filter { it.isNotBlank() && it.length in 2..30 }
            .distinct()
            .take(6)
            .ifEmpty { error("Model returned no usable tags") }
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
            bridge.generate(prompt, maxTokens = 256)
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
            bridge.generate(prompt, maxTokens = 256)
        }
        text.trim().removeSurrounding("\"")
    }

    suspend fun close() {
        mutex.withLock {
            if (loaded) {
                runCatching { bridge.unload() }
                loaded = false
            }
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
