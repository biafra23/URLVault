package com.jaeckel.urlvault.android.ai

import com.jaeckel.urlvault.ai.LocalModelProvider
import com.jaeckel.urlvault.ai.LocalModelRegistry

/**
 * Selects which `LocalModelProvider` runs the bookmark AI calls. The selection
 * is the first provider whose id is in the user's `activeIds` set and is ready;
 * if none, falls back to the first ready provider.
 *
 * Wired into `BookmarkViewModel` in place of the previous direct `AICoreService`
 * lambdas, so the selection is observable and changes the moment the user
 * toggles a model in Settings.
 */
class LocalModelRouter(
    private val registry: LocalModelRegistry,
    private val activeIdsProvider: () -> Set<String>,
) {
    private suspend fun pick(): LocalModelProvider? {
        val active = activeIdsProvider()
        val all = registry.snapshot()
        val preferred = all.firstOrNull { it.id in active && runCatching { it.isReady() }.getOrDefault(false) }
        if (preferred != null) return preferred
        return all.firstOrNull { runCatching { it.isReady() }.getOrDefault(false) }
    }

    suspend fun generateTags(url: String, title: String, content: String): Result<List<String>> {
        val provider = pick() ?: return Result.failure(IllegalStateException("No ready local AI model"))
        return provider.generateTags(url, title, content)
    }

    suspend fun generateDescription(url: String, title: String): Result<String> {
        val provider = pick() ?: return Result.failure(IllegalStateException("No ready local AI model"))
        return provider.generateDescription(url, title)
    }

    suspend fun generateTitle(url: String): Result<String> {
        val provider = pick() ?: return Result.failure(IllegalStateException("No ready local AI model"))
        return provider.generateTitle(url)
    }
}
