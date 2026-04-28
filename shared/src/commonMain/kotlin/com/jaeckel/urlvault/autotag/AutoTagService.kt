package com.jaeckel.urlvault.autotag

import com.jaeckel.urlvault.BuildConfig
import com.jaeckel.urlvault.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText

/**
 * Fetches a URL's HTML content and extracts meaningful keywords as tag suggestions.
 *
 * Uses lightweight regex-based extraction of title, meta tags, and headings —
 * no external AI API or HTML parser library required.
 */
class AutoTagService(private val httpClient: HttpClient) {

    private val TAG = "AutoTagService"

    data class PageMetadata(
        val title: String?,
        val description: String?,
        val tags: List<String>
    )

    suspend fun fetchMetadata(url: String, maxTags: Int = 6): Result<PageMetadata> {
        return runCatching {
            Logger.d(TAG, "Fetching metadata for $url")
            val html = httpClient.get(url) {
                header("User-Agent", "URLVault/1.0 (Bookmark Manager)")
            }.bodyAsText()
            Logger.d(TAG, "Received ${html.length} bytes of HTML")

            // Limit processing to first 1MB to avoid memory issues on huge pages
            val trimmedHtml = if (html.length > 1_000_000) html.take(1_000_000) else html

            val textParts = mutableListOf<String>()

            // Extract <title>
            val title = extractTitle(trimmedHtml)?.let {
                val cleaned = stripHtmlTags(it)
                if (cleaned.isNotBlank()) {
                    textParts.add(cleaned)
                    textParts.add(cleaned) // Double weight for title
                    if (BuildConfig.DEBUG) {
                        Logger.v(TAG, "Extracted title: $cleaned")
                    }
                    cleaned
                } else null
            }

            // Extract description
            val description = extractMetaContent(trimmedHtml, "description")?.let {
                val cleaned = stripHtmlTags(it)
                if (cleaned.isNotBlank()) {
                    textParts.add(cleaned)
                    if (BuildConfig.DEBUG) {
                        Logger.v(TAG, "Extracted description: $cleaned")
                    }
                    cleaned
                } else null
            }

            // Also check Open Graph tags if different
            extractMetaContent(trimmedHtml, "og:title")?.let { ogTitle ->
                val cleaned = stripHtmlTags(ogTitle)
                if (cleaned.isNotBlank() && cleaned != title) {
                    textParts.add(cleaned)
                    textParts.add(cleaned)
                }
            }
            extractMetaContent(trimmedHtml, "og:description")?.let { ogDesc ->
                val cleaned = stripHtmlTags(ogDesc)
                if (cleaned.isNotBlank() && cleaned != description) {
                    textParts.add(cleaned)
                }
            }

            // Extract <meta name="keywords" content="...">
            val keywordsTags = extractMetaContent(trimmedHtml, "keywords")?.let { keywords ->
                keywords.split(Regex("[,;]+")).map {
                    it.trim()
                        .lowercase()
                        .replace(Regex("[^a-z0-9äöüß\\s-]"), "")
                        .trim()
                }
                .filter { it.isNotBlank() && it.length in 2..30 }
                .take(maxTags)
            }

            // Extract h1-h3 headings
            HEADING_REGEX.findAll(trimmedHtml).forEach { match ->
                match.groupValues.getOrNull(2)?.let {
                    val cleaned = stripHtmlTags(it)
                    if (cleaned.isNotBlank()) {
                        textParts.add(cleaned)
                    }
                }
            }

            // Tokenize, filter, count frequency
            val wordCounts = mutableMapOf<String, Int>()
            
            // If we have explicit keywords, add them to counts with high weight
            Logger.v(TAG, "keywordsTags found: $keywordsTags")
            keywordsTags?.forEach { keyword ->
                val clean = keyword.trim().lowercase()
                if (clean.isNotBlank()) {
                    wordCounts[clean] = (wordCounts[clean] ?: 0) + 5
                }
            }

            val combinedText = textParts.joinToString(" ")
            if (BuildConfig.DEBUG) {
                Logger.v(TAG, "combinedText for analysis (length ${combinedText.length}): $combinedText")
            }
            
            combinedText
                .lowercase()
                .split(WORD_SPLIT_REGEX)
                .filter { it.isNotBlank() }
                .forEach { word ->
                    if (word.length in 3..25 && word !in STOP_WORDS && !word.all { it.isDigit() }) {
                        wordCounts[word] = (wordCounts[word] ?: 0) + 1
                    }
                }

            Logger.v(TAG, "wordCounts map: $wordCounts")

            val tags = wordCounts.entries
                .filter { it.key.length >= 3 }
                .sortedByDescending { it.value }
                .take(maxTags)
                .map { it.key }
            
            Logger.d(TAG, "Final generated tags: $tags")

            PageMetadata(title, description, tags)
        }
    }

    private fun extractTitle(html: String): String? {
        val match = TITLE_REGEX.find(html)
        return match?.groupValues?.getOrNull(1)?.trim()
    }

    private fun extractMetaContent(html: String, name: String): String? {
        // More robust meta extraction that handles arbitrary attribute order and spacing
        val metaRegex = Regex("<meta[^>]+>", RegexOption.IGNORE_CASE)
        val nameRegex = Regex("""(?:name|property)\s*=\s*["'](?:$name|og:$name)["']""", RegexOption.IGNORE_CASE)
        val contentRegex = Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

        metaRegex.findAll(html).forEach { metaMatch ->
            val tag = metaMatch.value
            if (nameRegex.containsMatchIn(tag)) {
                contentRegex.find(tag)?.groupValues?.getOrNull(1)?.let { return it }
            }
        }
        return null
    }

    suspend fun extractTags(url: String, maxTags: Int = 6): Result<List<String>> {
        return fetchMetadata(url, maxTags).map { it.tags }
    }

    fun close() {
        httpClient.close()
    }

    companion object {
        private val TITLE_REGEX =
            Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
        private val HEADING_REGEX =
            Regex("<h([1-3])[^>]*>([\\s\\S]*?)</h\\1>", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("<[^>]+>")
        private val HTML_ENTITY_REGEX = Regex("&[a-z]+;")
        private val WORD_SPLIT_REGEX = Regex("[^a-z0-9äöüß]+")

        private fun stripHtmlTags(text: String): String =
            text.replace(HTML_TAG_REGEX, " ").replace(HTML_ENTITY_REGEX, " ")
    }
}

/**
 * Factory function to create an [AutoTagService] with a default HTTP client.
 */
fun createAutoTagService(): AutoTagService {
    val client = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
        followRedirects = true
    }
    return AutoTagService(client)
}
