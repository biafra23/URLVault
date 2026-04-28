package com.jaeckel.urlvault.android.ai

import android.util.Log
import io.ktor.client.HttpClient
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "AICoreService"

/** Maximum length of page content included in prompts. */
private const val MAX_PAGE_CONTENT_LENGTH = 800

/**
 * MLKit GenAI error code surfaced when the Prompt API feature (ID 647) is
 * registered in AICore but not available or supported on this specific device.
 * Treated as [AICoreStatus.Unavailable] rather than [AICoreStatus.Failed].
 */
private const val AICORE_FEATURE_NOT_FOUND_CODE = "606"
private const val AICORE_FEATURE_NOT_FOUND_MSG = "FEATURE_NOT_FOUND"

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
class AICoreService(httpClient: HttpClient) {

    private var generativeModel: GenerativeModel? = null
    private val contentExtractor = WebPageContentExtractor(httpClient)

    private val initMutex = Mutex()
    private val _status = MutableStateFlow<AICoreStatus>(AICoreStatus.Unknown)
    val status: StateFlow<AICoreStatus> = _status.asStateFlow()

    /**
     * Obtains the generative model instance.
     *
     * In the ML Kit GenAI Prompt API, "Gemini Nano" is the system-managed model.
     * The underlying architecture is based on Gemma. You can influence model selection
     * via ModelConfig:
     * - ModelReleaseStage.PREVIEW: Accesses newer architectures (like Nano v2/Gemma 4 based).
     * - ModelPreference.FULL: Prioritizes accuracy (e.g. 4B variant) over speed (2B variant).
     */
    private fun getOrCreateModel(): GenerativeModel {
        return generativeModel ?: createModelClient(
            ModelReleaseStage.STABLE,
            ModelPreference.FULL
        ).also { generativeModel = it }
    }

    private fun createModelClient(
        stage: Int,
        pref: Int
    ): GenerativeModel {
        val config = generationConfig {
            modelConfig = modelConfig {
                releaseStage = stage
                preference = pref
            }
        }
        return Generation.getClient(config)
    }

    /**
     * Initializes the model. Suspends until the model is ready or a terminal
     * state is reached. This follows the official Google recommendation to
     * iterate through configurations and use [FeatureStatus] to find the
     * best supported variant.
     */
    suspend fun initialize() {
        initMutex.withLock {
            if (generativeModel != null && _status.value is AICoreStatus.Available) {
                Log.v(TAG, "Already initialized")
                return
            }

            // Wrap the core probing logic in NonCancellable to prevent internal SDK crashes
            // in CompletionHandlerException (invokeOnCancellation) when the Activity/Scope
            // is destroyed during the status check / client creation phase.
            withContext(NonCancellable) {
                try {
                    Log.d(TAG, "Initializing ML Kit GenAI Prompt API (Gemini Nano)...")

                    // Preference order for models: try to find the most capable one supported by the device.
                    val configs = listOf(
                        ModelReleaseStage.PREVIEW to ModelPreference.FULL,
                        ModelReleaseStage.PREVIEW to ModelPreference.FAST,
                        ModelReleaseStage.STABLE to ModelPreference.FULL,
                        ModelReleaseStage.STABLE to ModelPreference.FAST
                    )

                    var successfulModel: GenerativeModel? = null

                    for ((stage, pref) in configs) {
                        var model: GenerativeModel? = null
                        try {
                            model = createModelClient(stage, pref)
                            val statusValue = model.checkStatus()

                            Log.d(TAG, "Config Stage=${stage.toStageString()}, Pref=${pref.toPrefString()} has status: ${statusValue.toStatusString()}")

                            if (statusValue != FeatureStatus.UNAVAILABLE) {
                                Log.i(TAG, "Matched supported model: Stage=${stage.toStageString()}, Pref=${pref.toPrefString()} (Status=${statusValue.toStatusString()})")

                                try {
                                    val baseModelName = model.getBaseModelName()
                                    Log.i(TAG, "Base Model Name: $baseModelName")
                                } catch (_: Exception) {
                                    // Not all AICore versions support this call yet
                                    Log.v(TAG, "Base model name not available for this variant")
                                }

                                successfulModel = model
                                break
                            } else {
                                model.close()
                            }
                        } catch (e: Exception) {
                            val statusStr = try {
                                model?.checkStatus()?.toStatusString() ?: "UNINITIALIZED"
                            } catch (_: Exception) {
                                "ERROR_DURING_CHECK"
                            }
                            Log.w(TAG, "Config Stage=${stage.toStageString()}, Pref=${pref.toPrefString()} (Status=$statusStr) is unsupported or threw error: ${e.message}")
                            model?.close()
                        }
                    }

                    val model = successfulModel
                    if (model == null) {
                        Log.w(TAG, "Gemini Nano is not supported by any configuration on this device")
                        _status.value = AICoreStatus.Unavailable
                        return@withContext
                    }

                    generativeModel = model

                    // Final status check to handle downloads vs availability
                    when (model.checkStatus()) {
                        FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                            collectDownload(model)
                        }

                        FeatureStatus.AVAILABLE -> {
                            Log.d(TAG, "Model available, warming up...")
                            model.warmup()
                            _status.value = AICoreStatus.Available
                        }

                        else -> {
                            _status.value = AICoreStatus.Unavailable
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ML Kit GenAI initialization error: ${e.message}", e)

                    // Error 606 (FEATURE_NOT_FOUND) often means AICore is present but this specific 
                    // feature (Prompt API / Feature 647) is not available or supported on this device.
                    if (e.message?.contains(AICORE_FEATURE_NOT_FOUND_CODE) == true ||
                        e.message?.contains(AICORE_FEATURE_NOT_FOUND_MSG) == true) {
                        _status.value = AICoreStatus.Unavailable
                    } else {
                        _status.value = AICoreStatus.Failed(e.message ?: "Initialization failed")
                    }
                }
            }
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

            // Inline benchmark fan-out removed: it iterated 4 Gemini Nano
            // variants and every registered local model BEFORE returning a
            // result, turning a single tap into 30+ seconds of waiting. For
            // explicit cross-model comparison use ModelComparisonScreen.
            val text = runInference(prompt)
            if (com.jaeckel.urlvault.android.BuildConfig.DEBUG) {
                Log.d(TAG, "AI Response: $text")
            }
            
            // Aggressively clean the AI response: split by comma/newline/semicolon,
            // then strip all non-alphanumeric (allowing hyphens and internal spaces)
            text.split(Regex("[,\\n;]+"))
                .map { 
                    it.trim()
                        .lowercase()
                        .replace(Regex("[^a-z0-9\\s-]"), "")
                        .trim()
                }
                .filter { it.isNotBlank() && it.length in 2..30 }
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
            
            // See generateTags() — inline runBenchmarking removed for the
            // same reason; explicit comparison lives in
            // ModelComparisonScreen.
            validateDescription(runInference(prompt).trim())
        }
    }

    /**
     * Generates a title for a bookmark based on the page content if the provided title is empty.
     * If the page has a native title, returns that; otherwise, uses AI to generate one.
     */
    suspend fun generateTitle(url: String): Result<String> {
        return runCatching {
            val pageContent = fetchPageContent(url)
            val nativeTitle = pageContent?.bestTitle()

            if (!nativeTitle.isNullOrBlank()) {
                return@runCatching nativeTitle
            }

            // Fallback to AI generation — works with or without page content
            val pageSummary = pageContent?.bestSummary(MAX_PAGE_CONTENT_LENGTH) ?: ""
            val prompt = buildString {
                appendLine("Generate a short, descriptive title for this bookmark (max 6 words).")
                appendLine("Return ONLY the title, nothing else.")
                appendLine()
                appendLine("Context data:")
                appendLine("URL: $url")
                if (pageSummary.isNotBlank()) appendLine("Page summary: $pageSummary")
            }
            runInference(prompt).trim().removeSurrounding("\"")
        }
    }

    private suspend fun runInference(prompt: String): String {
        val model = getOrCreateModel()
        // Wrap generation in NonCancellable to prevent internal SDK crashes 
        // (CompletionHandlerException in invokeOnCancellation) when the 
        // calling coroutine is cancelled. The SDK's current beta has 
        // stability issues during rapid cancellation of inference tasks.
        val response = withContext(NonCancellable) {
            model.generateContent(
                generateContentRequest(TextPart(prompt)) {
                    temperature = 0.0f
                    topK = 1
                    maxOutputTokens = 256
                }
            )
        }
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

    private fun Int.toStatusString(): String = when (this) {
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.AVAILABLE -> "AVAILABLE"
        else -> "UNKNOWN ($this)"
    }

    private fun Int.toStageString(): String = when (this) {
        ModelReleaseStage.STABLE -> "STABLE"
        ModelReleaseStage.PREVIEW -> "PREVIEW"
        else -> "UNKNOWN ($this)"
    }

    private fun Int.toPrefString(): String = when (this) {
        ModelPreference.FAST -> "FAST"
        ModelPreference.FULL -> "FULL"
        else -> "UNKNOWN ($this)"
    }

    fun close() {
        generativeModel?.close()
        generativeModel = null
        contentExtractor.close()
    }
}
