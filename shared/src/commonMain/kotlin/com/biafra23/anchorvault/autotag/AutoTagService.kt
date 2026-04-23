package com.biafra23.anchorvault.autotag

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

    data class PageMetadata(
        val title: String?,
        val description: String?,
        val tags: List<String>
    )

    suspend fun fetchMetadata(url: String, maxTags: Int = 6): Result<PageMetadata> {
        return runCatching {
            val html = httpClient.get(url) {
                header("User-Agent", "AnchorVault/1.0")
            }.bodyAsText()

            // Limit processing to first 100KB to avoid memory issues on huge pages
            val trimmedHtml = if (html.length > 100_000) html.take(100_000) else html

            val textParts = mutableListOf<String>()

            // Extract <title>
            val title = TITLE_REGEX.find(trimmedHtml)?.groupValues?.getOrNull(1)?.let {
                val cleaned = stripHtmlTags(it)
                textParts.add(cleaned)
                textParts.add(cleaned)
                cleaned
            }

            // Extract <meta name="description" content="..."> (handles both attribute orders)
            val description = META_DESC_REGEX.find(trimmedHtml)?.let { match ->
                val content = match.groupValues[1].ifEmpty { match.groupValues[2] }
                if (content.isNotEmpty()) {
                    textParts.add(stripHtmlTags(content))
                    stripHtmlTags(content)
                } else null
            }

            // Extract <meta name="keywords" content="...">
            val keywordsTags = META_KEYWORDS_REGEX.find(trimmedHtml)?.groupValues?.getOrNull(1)?.let { keywords ->
                // Keywords meta tag is already comma-separated — treat each as a potential tag
                keywords.split(",").map { it.trim().lowercase() }
                    .filter { it.isNotBlank() && it.length in 2..30 }
                    .take(maxTags)
            }

            if (keywordsTags != null && keywordsTags.isNotEmpty()) {
                return@runCatching PageMetadata(title, description, keywordsTags)
            }

            // Extract h1-h3 headings
            HEADING_REGEX.findAll(trimmedHtml).forEach { match ->
                match.groupValues.getOrNull(2)?.let {
                    textParts.add(stripHtmlTags(it))
                }
            }

            // Tokenize, filter, count frequency
            val wordCounts = mutableMapOf<String, Int>()
            textParts.joinToString(" ")
                .lowercase()
                .split(WORD_SPLIT_REGEX)
                .filter { word ->
                    word.length in 3..25 &&
                        word !in STOP_WORDS &&
                        !word.all { it.isDigit() }
                }
                .forEach { word ->
                    wordCounts[word] = (wordCounts[word] ?: 0) + 1
                }

            val tags = wordCounts.entries
                .sortedByDescending { it.value }
                .take(maxTags)
                .map { it.key }

            PageMetadata(title, description, tags)
        }
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
        private val META_DESC_REGEX =
            Regex("""<meta\s+(?:name=["']description["']\s+content=["']([^"']*)["']|content=["']([^"']*)["']\s+name=["']description["'])""",
                RegexOption.IGNORE_CASE)
        private val META_KEYWORDS_REGEX =
            Regex("""<meta\s+name=["']keywords["']\s+content=["']([^"']*)["']""",
                RegexOption.IGNORE_CASE)
        private val HEADING_REGEX =
            Regex("<h([1-3])[^>]*>([\\s\\S]*?)</h\\1>", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("<[^>]+>")
        private val HTML_ENTITY_REGEX = Regex("&[a-z]+;")
        private val WORD_SPLIT_REGEX = Regex("[^a-z0-9]+")

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
    }
    return AutoTagService(client)
}
