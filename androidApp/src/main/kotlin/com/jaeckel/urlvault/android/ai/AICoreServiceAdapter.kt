package com.jaeckel.urlvault.android.ai

import com.jaeckel.urlvault.ai.AiProviderIds
import com.jaeckel.urlvault.ai.LocalModelProvider
import com.jaeckel.urlvault.ai.ModelRuntime

/**
 * Exposes the existing `AICoreService` (Gemini Nano via ML Kit GenAI) as a
 * `LocalModelProvider` so it shows up alongside downloaded GGUFs in the
 * registry, the user-visible comparison screen, and the bookmark AI router.
 *
 * The adapter wraps a single Gemini Nano variant — the one `AICoreService`
 * picked during `initialize()`. The DEBUG-only 4-variant benchmark continues
 * to live inside `AICoreService.runBenchmarking()`.
 */
class AICoreServiceAdapter(
    private val service: AICoreService,
) : LocalModelProvider {
    override val id: String = AiProviderIds.AICORE
    override val displayName: String = "Google Gemini Nano (AICore)"
    override val runtime: ModelRuntime = ModelRuntime.ML_KIT

    override suspend fun isReady(): Boolean = service.status.value is AICoreStatus.Available

    override suspend fun generateTags(url: String, title: String, content: String): Result<List<String>> =
        service.generateTags(url, title, content)

    override suspend fun generateDescription(url: String, title: String): Result<String> =
        service.generateDescription(url, title)

    override suspend fun generateTitle(url: String): Result<String> =
        service.generateTitle(url)
}
