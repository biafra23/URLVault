package com.biafra23.urlvault.android.ai

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

private const val TAG = "WebPageContentExtractor"

/**
 * Extracted content from a web page, sanitized for safe use in AI prompts.
 */
data class PageContent(
    val title: String?,
    val metaDescription: String?,
    val ogDescription: String?,
    val ogTitle: String?,
    val visibleText: String
) {
    /**
     * Returns the best available title, preferring OG title over HTML title.
     */
    fun bestTitle(): String? = ogTitle ?: title

    /**
     * Returns the best available description, preferring structured meta tags
     * over free-form page text. Truncated to [maxLength] characters.
     */
    fun bestSummary(maxLength: Int = 800): String {
        // Prefer OG description > meta description > visible text
        val candidate = when {
            !ogDescription.isNullOrBlank() -> ogDescription
            !metaDescription.isNullOrBlank() -> metaDescription
            visibleText.isNotBlank() -> visibleText
            else -> return ""
        }
        return candidate.take(maxLength)
    }
}

/**
 * Fetches a web page and extracts meta tags and visible text content.
 * All content is sanitized to reduce prompt injection risk.
 */
class WebPageContentExtractor(private val client: HttpClient) {

    /**
     * Fetches the URL and extracts structured content.
     * Returns null if the page cannot be fetched.
     */
    suspend fun extract(url: String): PageContent? {
        return try {
            Log.d(TAG, "Fetching content from: $url")
            val response = client.get(url) {
                header("User-Agent", "URLVault/1.0 (Bookmark Manager)")
                header("Accept", "text/html")
            }

            if (!response.status.isSuccess()) {
                Log.w(TAG, "HTTP ${response.status.value} for $url")
                return null
            }

            val html = response.bodyAsText()
            if (com.biafra23.urlvault.android.BuildConfig.DEBUG) {
                Log.v(TAG, "HTML content length: ${html.length}")
            }
            parseHtml(html)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch $url: ${e.message}")
            null
        }
    }

    /**
     * Parses HTML and extracts meta tags and visible text.
     */
    internal fun parseHtml(html: String): PageContent {
        val title = extractTitle(html)
        val metaDescription = extractMetaContent(html, "name", "description")
        val ogDescription = extractMetaContent(html, "property", "og:description")
        val ogTitle = extractMetaContent(html, "property", "og:title")
        val visibleText = extractVisibleText(html)

        return PageContent(
            title = sanitize(title),
            metaDescription = sanitize(metaDescription),
            ogDescription = sanitize(ogDescription),
            ogTitle = sanitize(ogTitle),
            visibleText = sanitize(visibleText) ?: ""
        )
    }

    /**
     * Extracts the content of the <title> tag.
     */
    private fun extractTitle(html: String): String? {
        val match = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)
        return match?.groupValues?.getOrNull(1)?.trim()
    }

    /**
     * Extracts the content attribute from a <meta> tag.
     * Handles both `name="x"` and `property="x"` attributes,
     * and both single and double quotes, in any attribute order.
     */
    private fun extractMetaContent(html: String, attrName: String, attrValue: String): String? {
        // Match <meta ...attrName="attrValue"...content="..."...> in either order
        val patterns = listOf(
            // attrName before content
            Regex(
                """<meta\s[^>]*$attrName\s*=\s*["']$attrValue["'][^>]*content\s*=\s*["']([^"']+)["'][^>]*/?>""",
                RegexOption.IGNORE_CASE
            ),
            // content before attrName
            Regex(
                """<meta\s[^>]*content\s*=\s*["']([^"']+)["'][^>]*$attrName\s*=\s*["']$attrValue["'][^>]*/?>""",
                RegexOption.IGNORE_CASE
            )
        )
        for (pattern in patterns) {
            val match = pattern.find(html)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    /**
     * Strips HTML tags, scripts, styles, and invisible content to extract
     * human-readable text.
     */
    private fun extractVisibleText(html: String): String {
        var text = html
        // Remove script and style blocks entirely
        text = Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE).replace(text, " ")
        text = Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE).replace(text, " ")
        // Remove HTML comments
        text = Regex("<!--[\\s\\S]*?-->").replace(text, " ")
        // Remove all remaining HTML tags
        text = Regex("<[^>]+>").replace(text, " ")
        // Decode common HTML entities
        text = text
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        // Collapse whitespace
        text = Regex("\\s+").replace(text, " ").trim()
        return text
    }

    /**
     * Sanitizes extracted text to reduce prompt injection risk.
     * Removes patterns that look like instructions to an AI model.
     */
    private fun sanitize(text: String?): String? {
        if (text.isNullOrBlank()) return null
        var sanitized = text
        // Remove lines that look like prompt injection attempts
        sanitized = Regex(
            "(ignore|disregard|forget)\\s+(all\\s+)?(previous|above|prior)\\s+(instructions|prompts|rules)",
            RegexOption.IGNORE_CASE
        ).replace(sanitized, "[removed]")
        sanitized = Regex(
            "(you\\s+are|act\\s+as|pretend|roleplay|system\\s*:)",
            RegexOption.IGNORE_CASE
        ).replace(sanitized, "[removed]")
        return sanitized.trim().ifBlank { null }
    }

    fun close() {
        // client is shared and managed externally
    }
}
