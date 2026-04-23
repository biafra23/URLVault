package com.biafra23.anchorvault.android.ai

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AICoreService"

/** Maximum length of page content included in prompts. */
private const val MAX_PAGE_CONTENT_LENGTH = 800

/** Maximum allowed length for a generated description. */
private const val MAX_DESCRIPTION_LENGTH = 300

sealed class AICoreStatus {
    data object Unknown : AICoreStatus()
    data object Unavailable : AICoreStatus()
    data object Downloading : AICoreStatus()
    data object Available : AICoreStatus()
    data class Failed(val message: String) : AICoreStatus()
}

/**
 * Wraps the ML Kit GenAI Prompt API (Gemini Nano) for on-device tag and
 * description generation. Fetches web page content to provide context.
 * All inference runs locally — no data leaves the device (except the HTTP fetch).
 */
class AICoreService {

    private var generativeModel: GenerativeModel? = null
    private val contentExtractor = WebPageContentExtractor()

    private val _status = MutableStateFlow<AICoreStatus>(AICoreStatus.Unknown)
    val status: StateFlow<AICoreStatus> = _status.asStateFlow()

    private fun getOrCreateModel(): GenerativeModel =
        generativeModel ?: Generation.getClient().also { generativeModel = it }

    /**
     * Initializes the model. Suspends until the model is ready or a terminal
     * state is reached — if the model needs downloading, this collects the
     * download progress Flow and updates [status] as it advances.
     */
    suspend fun initialize() {
        try {
            Log.d(TAG, "Initializing ML Kit GenAI Prompt API (Gemini Nano)...")
            val model = getOrCreateModel()
            when (model.checkStatus()) {
                FeatureStatus.UNAVAILABLE -> {
                    Log.w(TAG, "Gemini Nano unavailable on this device")
                    _status.value = AICoreStatus.Unavailable
                }
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                    collectDownload(model)
                }
                FeatureStatus.AVAILABLE -> {
                    Log.d(TAG, "Model available, warming up inference engine...")
                    model.warmup()
                    _status.value = AICoreStatus.Available
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit GenAI initialization error: ${e.javaClass.simpleName}: ${e.message}", e)
            _status.value = AICoreStatus.Failed(e.message ?: "Initialization failed")
        }
    }

    private suspend fun collectDownload(model: GenerativeModel) {
        _status.value = AICoreStatus.Downloading
        model.download().collect { downloadStatus ->
            when (downloadStatus) {
                is DownloadStatus.DownloadStarted -> {
                    Log.i(TAG, "Model download started")
                    _status.value = AICoreStatus.Downloading
                }
                is DownloadStatus.DownloadProgress -> {
                    Log.d(TAG, "Model download progress: ${downloadStatus.totalBytesDownloaded} bytes")
                    _status.value = AICoreStatus.Downloading
                }
                DownloadStatus.DownloadCompleted -> {
                    Log.i(TAG, "Model download completed, warming up...")
                    model.warmup()
                    _status.value = AICoreStatus.Available
                }
                is DownloadStatus.DownloadFailed -> {
                    Log.e(TAG, "Model download failed", downloadStatus.e)
                    _status.value = AICoreStatus.Failed(
                        "Download failed: ${downloadStatus.e.message ?: "unknown"}"
                    )
                }
            }
        }
    }

    private suspend fun fetchPageContent(url: String): PageContent? {
        return try {
            contentExtractor.extract(url)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch page content for $url: ${e.message}")
            null
        }
    }

    /**
     * Generate tags for a bookmark based on its URL, title, and description.
     * Fetches the web page to provide additional context.
     * Returns 3-6 short, lowercase, comma-separated tags.
     */
    suspend fun generateTags(url: String, title: String, description: String): Result<List<String>> {
        return runCatching {
            Log.d(TAG, "Generating tags for: $url")
            val pageContent = fetchPageContent(url)
            val pageSummary = pageContent?.bestSummary(MAX_PAGE_CONTENT_LENGTH) ?: ""

            val prompt = buildString {
                appendLine("You are a helpful assistant that generates tags for bookmarks.")
                appendLine("Your task is to provide 3 to 6 short, descriptive, lowercase tags.")
                appendLine("Return ONLY a comma-separated list of tags. Do not include any other text or explanation.")
                appendLine("Example output: android, kotlin, development, security")
                appendLine()
                appendLine("Use the following data for context:")
                appendLine("URL: $url")
                if (title.isNotBlank()) {
                    appendLine("Title: $title")
                }
                if (description.isNotBlank()) {
                    appendLine("User description: $description")
                }
                if (pageSummary.isNotBlank()) {
                    appendLine("Page summary: $pageSummary")
                }
            }
            Log.v(TAG, "AI Prompt prepared for tag generation (titleIncluded=${title.isNotBlank()}, descriptionIncluded=${description.isNotBlank()}, pageSummaryIncluded=${pageSummary.isNotBlank()}, length=${prompt.length})")
            val text = runInference(prompt)
            Log.d(TAG, "AI Response: $text")
            text.split(",")
                .map { it.trim().lowercase().removeSurrounding("\"") }
                .filter { it.isNotBlank() && it.length <= 30 }
                .distinct()
                .take(6)
                .ifEmpty { error("AI model returned no usable tags") }
        }
    }

    /**
     * Generate a 1-2 sentence description for a bookmark.
     * Fetches the web page to provide context for an accurate description.
     */
    suspend fun generateDescription(url: String, title: String): Result<String> {
        return runCatching {
            val pageContent = fetchPageContent(url)
            val pageSummary = pageContent?.bestSummary(MAX_PAGE_CONTENT_LENGTH) ?: ""

            val prompt = buildString {
                appendLine("Write a 1-2 sentence factual description for this bookmark.")
                appendLine("Return ONLY the description, nothing else.")
                appendLine()
                appendLine("Context data:")
                appendLine("URL: $url")
                if (title.isNotBlank()) {
                    appendLine("Title: $title")
                }
                if (pageSummary.isNotBlank()) {
                    appendLine("Page summary: $pageSummary")
                } else {
                    appendLine("If you cannot determine what the page is about, respond with: Unable to generate description.")
                }
            }
            validateDescription(runInference(prompt).trim())
        }
    }

    /**
     * Generates a title for a bookmark based on the page content if the provided title is empty.
     * If the page has a native title, returns that; otherwise, uses AI to generate one.
     */
    suspend fun generateTitle(url: String): Result<String> {
        return runCatching {
            val pageContent = fetchPageContent(url) ?: error("Could not fetch page content")
            val nativeTitle = pageContent.bestTitle()

            if (!nativeTitle.isNullOrBlank()) {
                return@runCatching nativeTitle
            }

            // Fallback to AI generation
            val pageSummary = pageContent.bestSummary(MAX_PAGE_CONTENT_LENGTH)
            val prompt = buildString {
                appendLine("Generate a short, descriptive title for this bookmark (max 6 words).")
                appendLine("Return ONLY the title, nothing else.")
                appendLine()
                appendLine("Context data:")
                appendLine("URL: $url")
                if (pageSummary.isNotBlank()) {
                    appendLine("Page summary: $pageSummary")
                }
            }
            runInference(prompt).trim().removeSurrounding("\"")
        }
    }

    private suspend fun runInference(prompt: String): String {
        val model = getOrCreateModel()
        val response = model.generateContent(
            generateContentRequest(TextPart(prompt)) {
                temperature = 0.0f
                topK = 1
                maxOutputTokens = 256
            }
        )
        val candidate = response.candidates.firstOrNull()
            ?: error("Empty response from AI model")
        return candidate.text.ifBlank { error("Empty response from AI model") }
    }

    private fun validateDescription(text: String): String {
        if (text.length > MAX_DESCRIPTION_LENGTH) {
            return text.take(MAX_DESCRIPTION_LENGTH)
        }
        if (Regex("https?://\\S+").containsMatchIn(text)) {
            Log.w(TAG, "Description contained URL, stripping it")
            return Regex("https?://\\S+").replace(text, "").trim()
        }
        return text
    }

    fun close() {
        generativeModel?.close()
        generativeModel = null
        contentExtractor.close()
    }
}
