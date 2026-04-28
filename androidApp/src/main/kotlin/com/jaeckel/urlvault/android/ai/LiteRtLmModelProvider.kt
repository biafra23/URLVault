package com.jaeckel.urlvault.android.ai

import android.util.Log
import com.jaeckel.urlvault.ai.LocalModelProvider
import com.jaeckel.urlvault.ai.ModelRuntime
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "LiteRtLmModelProvider"
private const val MAX_PAGE_CONTENT_LENGTH = 1200
private const val MAX_DESCRIPTION_LENGTH = 300

/**
 * `LocalModelProvider` backed by Google's LiteRT-LM runtime.
 *
 * Currently used for Gemma 4 E2B IT (`.litertlm` bundle). Unlike the LeapSDK
 * provider this can't grammar-constrain the sampler, so the prompt asks for
 * JSON and `parseJson` does the defensive cleanup (markdown fences, leading
 * prose, trailing commentary). Prompts are written as instructions —
 * Gemma-style chat fine-tunes follow them better than they follow
 * extraction-style framing.
 */
class LiteRtLmModelProvider(
    override val id: String,
    override val displayName: String,
    private val modelFile: String,
    private val bridge: LiteRtLmNativeBridge,
    httpClient: HttpClient,
) : LocalModelProvider {

    override val runtime: ModelRuntime = ModelRuntime.MEDIAPIPE

    private val contentExtractor = WebPageContentExtractor(httpClient)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun isReady(): Boolean = bridge.isAvailable()

    override suspend fun preload() {
        // Same mutex as the generate path so an inference call can't race a
        // warm-up into the LiteRT-LM Engine constructor.
        mutex.withLock { ensureLoaded() }
    }

    private suspend fun ensureLoaded() {
        bridge.load(modelFile)
    }

    @Serializable
    private data class TagsExtraction(
        @SerialName("tags") val tags: List<String> = emptyList(),
    )

    @Serializable
    private data class DescriptionExtraction(
        @SerialName("description") val description: String = "",
    )

    @Serializable
    private data class TitleExtraction(
        @SerialName("title") val title: String = "",
    )

    override suspend fun generateTags(
        url: String,
        title: String,
        content: String,
    ): Result<List<String>> = runCatching {
        val pageContent = runCatching { contentExtractor.extract(url) }.getOrNull()
        val pageSummary = pageContent?.bestSummary(MAX_PAGE_CONTENT_LENGTH).orEmpty()

        val schema = """
            {"tags": ["..."]}
        """.trimIndent()

        val task = buildString {
            appendLine("Suggest 3 to 6 short, descriptive, lowercase tags for this bookmark.")
            appendLine("Tags must use only lowercase letters, digits, spaces, and hyphens; each 2-30 chars.")
            appendLine()
            appendLine("URL: $url")
            if (title.isNotBlank()) appendLine("Title: $title")
            if (content.isNotBlank()) appendLine("User description: $content")
            if (pageSummary.isNotBlank()) {
                appendLine("Page content:")
                appendLine(pageSummary)
            }
        }

        val raw = mutex.withLock {
            ensureLoaded()
            bridge.generateStructured(prompt = task, jsonSchema = schema, maxTokens = 192)
        }
        Log.i(TAG, "[$id] tags raw: $raw")

        val parsed = parseJson<TagsExtraction>(raw)
        parsed.tags
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it.length in 2..30 }
            .distinct()
            .take(6)
            .ifEmpty { error("Model returned no usable tags") }
    }

    override suspend fun generateDescription(url: String, title: String): Result<String> = runCatching {
        val pageContent = runCatching { contentExtractor.extract(url) }.getOrNull()
        val pageSummary = pageContent?.bestSummary(MAX_PAGE_CONTENT_LENGTH).orEmpty()

        val schema = """
            {"description": "..."}
        """.trimIndent()

        val task = buildString {
            appendLine("Write a 1-2 sentence factual description for this bookmark, max 300 characters.")
            appendLine()
            appendLine("URL: $url")
            if (title.isNotBlank()) appendLine("Title: $title")
            if (pageSummary.isNotBlank()) {
                appendLine("Page content:")
                appendLine(pageSummary)
            }
        }

        val raw = mutex.withLock {
            ensureLoaded()
            bridge.generateStructured(prompt = task, jsonSchema = schema, maxTokens = 192)
        }
        Log.i(TAG, "[$id] description raw: $raw")

        validateDescription(parseJson<DescriptionExtraction>(raw).description.trim())
    }

    override suspend fun generateTitle(url: String): Result<String> = runCatching {
        val pageContent = runCatching { contentExtractor.extract(url) }.getOrNull()
            ?: error("Could not fetch page content")
        val nativeTitle = pageContent.bestTitle()
        if (!nativeTitle.isNullOrBlank()) return@runCatching nativeTitle

        val pageSummary = pageContent.bestSummary(MAX_PAGE_CONTENT_LENGTH)
        val schema = """
            {"title": "..."}
        """.trimIndent()

        val task = buildString {
            appendLine("Write a short, descriptive title for this bookmark (max 6 words).")
            appendLine()
            appendLine("URL: $url")
            if (pageSummary.isNotBlank()) {
                appendLine("Page content:")
                appendLine(pageSummary)
            }
        }

        val raw = mutex.withLock {
            ensureLoaded()
            bridge.generateStructured(prompt = task, jsonSchema = schema, maxTokens = 96)
        }
        Log.i(TAG, "[$id] title raw: $raw")

        parseJson<TitleExtraction>(raw).title.trim().removeSurrounding("\"")
    }

    private inline fun <reified T> parseJson(raw: String): T {
        val cleaned = extractJsonObject(raw) ?: error("No JSON object found in model output: $raw")
        return try {
            json.decodeFromString<T>(cleaned)
        } catch (t: Throwable) {
            Log.w(TAG, "parseJson failed for cleaned=$cleaned (raw=$raw)", t)
            throw t
        }
    }

    private fun extractJsonObject(raw: String): String? {
        val trimmed = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else null
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
