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

        // Concrete example, not a schema. Chat models follow examples better
        // than schema-style ellipses ("..."), and there's no grammar
        // constraint to lean on.
        val example = """{"tags": ["android", "kotlin", "security"]}"""

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
            bridge.generateStructured(prompt = task, jsonSchema = example, maxTokens = 192)
        }
        Log.i(TAG, "[$id] tags raw: $raw")

        // Try strict JSON first; if the model emitted prose instead, fall
        // back to a comma/newline split and continue rather than failing
        // outright.
        val tags = runCatching { parseJson<TagsExtraction>(raw).tags }
            .getOrElse {
                Log.w(TAG, "[$id] tags JSON parse failed, falling back to free-text split")
                parseTagsFreeText(raw)
            }
        tags
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it.length in 2..30 }
            .distinct()
            .take(6)
            .ifEmpty { error("Model returned no usable tags") }
    }

    override suspend fun generateDescription(url: String, title: String): Result<String> = runCatching {
        val pageContent = runCatching { contentExtractor.extract(url) }.getOrNull()
        val pageSummary = pageContent?.bestSummary(MAX_PAGE_CONTENT_LENGTH).orEmpty()

        val example = """{"description": "A Kotlin Multiplatform tutorial covering shared UI with Compose."}"""

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
            bridge.generateStructured(prompt = task, jsonSchema = example, maxTokens = 192)
        }
        Log.i(TAG, "[$id] description raw: $raw")

        // Strict JSON first, raw text as fallback (after stripping fences /
        // common preambles). Chat models often answer with prose when given
        // a soft schema; we still want a usable description out the other
        // side rather than a hard error.
        val text = runCatching { parseJson<DescriptionExtraction>(raw).description }
            .getOrElse {
                Log.w(TAG, "[$id] description JSON parse failed, using free-text fallback")
                cleanFreeText(raw)
            }
        validateDescription(text.trim())
    }

    override suspend fun generateTitle(url: String): Result<String> = runCatching {
        val pageContent = runCatching { contentExtractor.extract(url) }.getOrNull()
        val nativeTitle = pageContent?.bestTitle()
        if (!nativeTitle.isNullOrBlank()) return@runCatching nativeTitle

        val pageSummary = pageContent?.bestSummary(MAX_PAGE_CONTENT_LENGTH) ?: ""
        val example = """{"title": "Compose Multiplatform Quickstart"}"""

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
            bridge.generateStructured(prompt = task, jsonSchema = example, maxTokens = 96)
        }
        Log.i(TAG, "[$id] title raw: $raw")

        val text = runCatching { parseJson<TitleExtraction>(raw).title }
            .getOrElse {
                Log.w(TAG, "[$id] title JSON parse failed, using free-text fallback")
                cleanFreeText(raw)
            }
        text.trim().removeSurrounding("\"")
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

    /**
     * Used when JSON extraction failed for a tags request — try to recover
     * the list from prose like "android, kotlin, security" or
     * "1. android\n2. kotlin\n3. security".
     */
    private fun parseTagsFreeText(raw: String): List<String> {
        return raw.split(Regex("[,\\n;]+"))
            .map { line ->
                line.trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```")
                    // Strip list markers like "1." / "1)" / "-" / "*"
                    .replace(Regex("^\\s*(?:[0-9]+[.)]|[-*])\\s*"), "")
                    .trim()
                    .removeSurrounding("\"")
                    .removeSurrounding("'")
                    .lowercase()
                    .replace(Regex("[^a-z0-9\\s-]"), "")
                    .trim()
            }
            .filter { it.isNotBlank() }
    }

    /**
     * Used when JSON extraction failed for a description / title request.
     * Strips markdown fences and common LLM preambles ("Sure! Here is...",
     * "Here's a description:") to leave a usable string.
     */
    private fun cleanFreeText(raw: String): String {
        val stripped = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            .removePrefix("Sure!").removePrefix("Sure,").removePrefix("Sure")
            .removePrefix("Here is").removePrefix("Here's").removePrefix("Here are")
            .removePrefix("Of course!").removePrefix("Certainly!")
            .trim()
            .removePrefix(":").removePrefix(",").trim()
        // If the model embedded a quoted string anywhere, prefer it over
        // surrounding prose.
        val quoted = Regex("\"([^\"]{4,})\"").find(stripped)?.groupValues?.get(1)
        return (quoted ?: stripped).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
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
