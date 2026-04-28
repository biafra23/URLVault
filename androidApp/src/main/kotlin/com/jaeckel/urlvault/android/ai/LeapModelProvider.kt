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

private const val TAG = "LeapModelProvider"
private const val MAX_PAGE_CONTENT_LENGTH = 800
private const val MAX_DESCRIPTION_LENGTH = 300

/**
 * `LocalModelProvider` backed by Liquid AI's LeapSDK runtime.
 *
 * Designed around LFM2 1.2B Extract, which is fine-tuned for grammar-
 * constrained JSON output. Each of the three operations (tags / description /
 * title) issues a JSON-schema-constrained query so the response is always a
 * parseable JSON object — no regex-y free-text cleanup like the GGUF/llama.cpp
 * provider needs.
 *
 * Schema-constrained generation is delegated to [LeapNativeBridge.generateStructured].
 */
class LeapModelProvider(
    override val id: String,
    override val displayName: String,
    private val modelFile: String,
    private val bridge: LeapNativeBridge,
    httpClient: HttpClient,
) : LocalModelProvider {

    override val runtime: ModelRuntime = ModelRuntime.LEAP

    private val contentExtractor = WebPageContentExtractor(httpClient)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun isReady(): Boolean = bridge.isAvailable()

    override suspend fun preload() {
        // Mutex matches ensureLoaded() so a concurrent generate() can't race
        // a warm-up call into LeapClient.loadModel.
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

    override suspend fun generateTags(url: String, title: String, content: String): Result<List<String>> = runCatching {
        val pageContent = runCatching { contentExtractor.extract(url) }.getOrNull()
        val pageSummary = pageContent?.bestSummary(MAX_PAGE_CONTENT_LENGTH).orEmpty()

        val schema = """
            {
              "type": "object",
              "properties": {
                "tags": {
                  "type": "array",
                  "minItems": 3,
                  "maxItems": 6,
                  "items": {
                    "type": "string",
                    "pattern": "^[a-z0-9][a-z0-9 -]{1,29}$"
                  }
                }
              },
              "required": ["tags"],
              "additionalProperties": false
            }
        """.trimIndent()

        val task = buildString {
            appendLine("Extract 3 to 6 short, descriptive, lowercase tags for this bookmark.")
            appendLine("URL: $url")
            if (title.isNotBlank()) appendLine("Title: $title")
            if (content.isNotBlank()) appendLine("User description: $content")
            if (pageSummary.isNotBlank()) appendLine("Page summary: $pageSummary")
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
            {
              "type": "object",
              "properties": {
                "description": {
                  "type": "string",
                  "minLength": 1,
                  "maxLength": 300
                }
              },
              "required": ["description"],
              "additionalProperties": false
            }
        """.trimIndent()

        // LFM2 Extract is fine-tuned for extraction, not generation. Frame
        // this as "extract a summary from the supplied text" rather than
        // "write a description"; otherwise the model has nothing to extract,
        // the grammar still forces a non-empty string, and we get garbage
        // (the original prompt produced a single-comma description).
        val task = buildString {
            appendLine("Extract a 1-2 sentence summary describing what the web page below is about. Use only information present in the supplied text.")
            appendLine()
            appendLine("URL: $url")
            if (title.isNotBlank()) appendLine("Title: $title")
            if (pageSummary.isNotBlank()) {
                appendLine("Page content:")
                appendLine(pageSummary)
            } else {
                // No page content fetched — give the model something concrete
                // to extract from rather than asking it to invent prose.
                appendLine("Page content: (unavailable — derive a one-sentence summary from the URL and title only)")
            }
            appendLine()
            appendLine("Return the extracted summary as the \"description\" field.")
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
            {
              "type": "object",
              "properties": {
                "title": {
                  "type": "string",
                  "minLength": 1,
                  "maxLength": 80
                }
              },
              "required": ["title"],
              "additionalProperties": false
            }
        """.trimIndent()

        // Same extraction framing as description: the Extract fine-tune
        // wants "extract X from supplied text", not "generate X".
        val task = buildString {
            appendLine("Extract a short descriptive title (max 6 words) for the web page below. Use only information present in the supplied text.")
            appendLine()
            appendLine("URL: $url")
            if (pageSummary.isNotBlank()) {
                appendLine("Page content:")
                appendLine(pageSummary)
            }
            appendLine()
            appendLine("Return the extracted title as the \"title\" field.")
        }

        val raw = mutex.withLock {
            ensureLoaded()
            bridge.generateStructured(prompt = task, jsonSchema = schema, maxTokens = 96)
        }
        Log.i(TAG, "[$id] title raw: $raw")

        parseJson<TitleExtraction>(raw).title.trim().removeSurrounding("\"")
    }

    /**
     * Models that aren't perfectly schema-constrained sometimes wrap their
     * JSON in markdown fences, leading prose, or trailing commentary. Strip
     * those and parse the largest plausible JSON object out of the response.
     */
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
