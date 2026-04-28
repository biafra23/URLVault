package com.jaeckel.urlvault.ai

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Iterates every registered, ready provider against the same input and
 * returns one row per provider. Used by both the DEBUG logcat benchmark
 * (in `AICoreService`) and the user-visible `ModelComparisonScreen`.
 */
class ModelComparisonRunner(private val registry: LocalModelRegistry) {

    data class RunProgress(
        val completedProviders: Int,
        val totalProviders: Int,
        val activeProviderDisplayName: String? = null,
    )

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
     * blocking the comparison. Progress and individual provider results are
     * reported incrementally so the UI can update while the batch is running.
     */
    suspend fun runAll(
        url: String,
        title: String,
        userDescription: String,
        perCallTimeoutMs: Long = 60_000,
        onProgress: (RunProgress) -> Unit = {},
        onResult: (ProviderResult) -> Unit = {},
    ): List<ProviderResult> {
        val ready = registry.snapshot().filter { runCatching { it.isReady() }.getOrDefault(false) }
        if (ready.isEmpty()) {
            onProgress(RunProgress(completedProviders = 0, totalProviders = 0))
            return emptyList()
        }

        val results = mutableListOf<ProviderResult>()
        onProgress(
            RunProgress(
                completedProviders = 0,
                totalProviders = ready.size,
                activeProviderDisplayName = ready.first().displayName,
            ),
        )

        ready.forEachIndexed { index, provider ->
            val result = try {
                val tagsStart = currentTimeMillis()
                val tagsResult = withTimeoutOrNull(perCallTimeoutMs) {
                    provider.generateTags(url, title, userDescription)
                }
                val tagsMs = currentTimeMillis() - tagsStart
                val tagsTimedOut = tagsResult == null
                val tags = tagsResult?.getOrNull() ?: emptyList()

                val descStart = currentTimeMillis()
                val descResult = withTimeoutOrNull(perCallTimeoutMs) {
                    provider.generateDescription(url, title)
                }
                val descMs = currentTimeMillis() - descStart
                val descTimedOut = descResult == null
                val desc = descResult?.getOrNull() ?: ""

                val titleStart = currentTimeMillis()
                val titleResult = withTimeoutOrNull(perCallTimeoutMs) {
                    provider.generateTitle(url)
                }
                val titleMs = currentTimeMillis() - titleStart
                val titleTimedOut = titleResult == null
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
                        if (tagsTimedOut) "tags: timed out after ${perCallTimeoutMs}ms"
                        else tagsResult.exceptionOrNull()?.message?.let { "tags: $it" },
                        if (descTimedOut) "desc: timed out after ${perCallTimeoutMs}ms"
                        else descResult.exceptionOrNull()?.message?.let { "desc: $it" },
                        if (titleTimedOut) "title: timed out after ${perCallTimeoutMs}ms"
                        else titleResult.exceptionOrNull()?.message?.let { "title: $it" },
                    ).joinToString("; ").ifBlank { null },
                )
            } catch (e: TimeoutCancellationException) {
                // Catches only outer-scope cancellation, not per-call timeouts
                // (those are already handled by withTimeoutOrNull null detection above).
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
            results += result
            onResult(result)
            onProgress(
                RunProgress(
                    completedProviders = results.size,
                    totalProviders = ready.size,
                    activeProviderDisplayName = ready.getOrNull(index + 1)?.displayName,
                ),
            )
        }
        return results
    }
}

internal expect fun currentTimeMillis(): Long
