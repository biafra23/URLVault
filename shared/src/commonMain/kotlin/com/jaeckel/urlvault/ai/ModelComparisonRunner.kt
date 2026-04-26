package com.jaeckel.urlvault.ai

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Iterates every registered, ready provider against the same input and
 * returns one row per provider. Used by both the DEBUG logcat benchmark
 * (in `AICoreService`) and the user-visible `ModelComparisonScreen`.
 */
class ModelComparisonRunner(private val registry: LocalModelRegistry) {

    data class ProviderResult(
        val providerId: String,
        val displayName: String,
        val runtime: ModelRuntime,
        val tags: List<String>,
        val description: String,
        val title: String,
        val tagsMs: Long,
        val descriptionMs: Long,
        val titleMs: Long,
        val error: String? = null,
    )

    /**
     * Runs all three generation calls (tags, description, title) for every
     * ready provider. A per-call timeout prevents one stuck provider from
     * blocking the comparison.
     */
    suspend fun runAll(
        url: String,
        title: String,
        userDescription: String,
        perCallTimeoutMs: Long = 60_000,
    ): List<ProviderResult> {
        val ready = registry.snapshot().filter { runCatching { it.isReady() }.getOrDefault(false) }
        return ready.map { provider ->
            try {
                val tagsStart = currentTimeMillis()
                val tagsResult = withTimeoutOrNull(perCallTimeoutMs) {
                    provider.generateTags(url, title, userDescription)
                }
                val tagsMs = currentTimeMillis() - tagsStart
                val tags = tagsResult?.getOrNull() ?: emptyList()

                val descStart = currentTimeMillis()
                val descResult = withTimeoutOrNull(perCallTimeoutMs) {
                    provider.generateDescription(url, title)
                }
                val descMs = currentTimeMillis() - descStart
                val desc = descResult?.getOrNull() ?: ""

                val titleStart = currentTimeMillis()
                val titleResult = withTimeoutOrNull(perCallTimeoutMs) {
                    provider.generateTitle(url)
                }
                val titleMs = currentTimeMillis() - titleStart
                val titleOut = titleResult?.getOrNull() ?: ""

                ProviderResult(
                    providerId = provider.id,
                    displayName = provider.displayName,
                    runtime = provider.runtime,
                    tags = tags,
                    description = desc,
                    title = titleOut,
                    tagsMs = tagsMs,
                    descriptionMs = descMs,
                    titleMs = titleMs,
                    error = listOfNotNull(
                        tagsResult?.exceptionOrNull()?.message?.let { "tags: $it" },
                        descResult?.exceptionOrNull()?.message?.let { "desc: $it" },
                        titleResult?.exceptionOrNull()?.message?.let { "title: $it" },
                    ).joinToString("; ").ifBlank { null },
                )
            } catch (e: TimeoutCancellationException) {
                ProviderResult(
                    providerId = provider.id,
                    displayName = provider.displayName,
                    runtime = provider.runtime,
                    tags = emptyList(),
                    description = "",
                    title = "",
                    tagsMs = 0,
                    descriptionMs = 0,
                    titleMs = 0,
                    error = "Timed out after ${perCallTimeoutMs}ms",
                )
            } catch (e: Exception) {
                ProviderResult(
                    providerId = provider.id,
                    displayName = provider.displayName,
                    runtime = provider.runtime,
                    tags = emptyList(),
                    description = "",
                    title = "",
                    tagsMs = 0,
                    descriptionMs = 0,
                    titleMs = 0,
                    error = e.message ?: "Unknown error",
                )
            }
        }
    }
}

internal expect fun currentTimeMillis(): Long
