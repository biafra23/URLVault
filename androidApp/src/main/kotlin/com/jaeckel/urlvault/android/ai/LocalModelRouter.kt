package com.jaeckel.urlvault.android.ai

import android.util.Log
import com.jaeckel.urlvault.ai.LocalModelProvider
import com.jaeckel.urlvault.ai.LocalModelRegistry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "LocalModelRouter"

/**
 * Selects which `LocalModelProvider` runs the bookmark AI calls. The selection
 * is the first provider whose id is in the user's `activeIds` set and is ready;
 * if none, falls back to the first ready provider.
 *
 * Wired into `BookmarkViewModel` in place of the previous direct `AICoreService`
 * lambdas, so the selection is observable and changes the moment the user
 * toggles a model in Settings.
 *
 * Emits a [RouteEvent] to [events] every time an entry-point method is called,
 * so the UI can surface (in DEBUG) which provider actually ran — useful for
 * confirming an "activated" model is what's being used vs. silently falling
 * back to AICore.
 */
class LocalModelRouter(
    private val registry: LocalModelRegistry,
    private val activeIdsProvider: () -> Set<String>,
) {
    sealed class RouteEvent {
        abstract val action: String
        abstract val activeIds: Set<String>
        abstract val readiness: List<Pair<String, Boolean>>

        data class Picked(
            override val action: String,
            override val activeIds: Set<String>,
            override val readiness: List<Pair<String, Boolean>>,
            val providerId: String,
            val providerName: String,
            val reason: String,
        ) : RouteEvent()

        data class None(
            override val action: String,
            override val activeIds: Set<String>,
            override val readiness: List<Pair<String, Boolean>>,
            val reason: String,
        ) : RouteEvent()
    }

    private val _events = MutableSharedFlow<RouteEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<RouteEvent> = _events.asSharedFlow()

    /**
     * Set of provider IDs whose weights are currently being paged into memory
     * via [warmUpActive]. The Settings UI observes this to render a per-row
     * "Warming up…" indicator so multi-second LiteRT-LM / LeapSDK loads aren't
     * silent.
     */
    private val _warmingIds = MutableStateFlow<Set<String>>(emptySet())
    val warmingIds: StateFlow<Set<String>> = _warmingIds.asStateFlow()

    private data class PickResult(
        val provider: LocalModelProvider?,
        val reason: String,
        val activeIds: Set<String>,
        val readiness: List<Pair<String, Boolean>>,
    )

    private suspend fun pickWithReason(): PickResult {
        val active = activeIdsProvider()
        val all = registry.snapshot()

        // Snapshot every provider's readiness once so we can both decide and log.
        val readiness = all.map { p ->
            p to runCatching { p.isReady() }.getOrElse { e ->
                Log.w(TAG, "isReady() threw for ${p.id}: ${e.message}")
                false
            }
        }
        Log.i(
            TAG,
            "pick: activeIds=$active registered=${all.size} -> " +
                readiness.joinToString { (p, r) -> "${p.id}(ready=$r)" },
        )

        val readinessSummary = readiness.map { (p, r) -> p.id to r }
        val preferred = readiness.firstOrNull { (p, ready) -> ready && p.id in active }?.first
        if (preferred != null) {
            return PickResult(preferred, "active+ready", active, readinessSummary)
        }

        val fallback = readiness.firstOrNull { (_, ready) -> ready }?.first
        val reason = when {
            active.isEmpty() -> "no active set, fallback"
            all.none { it.id in active } -> "active id not registered, fallback"
            else -> "active not ready, fallback"
        }
        return PickResult(fallback, reason, active, readinessSummary)
    }

    private suspend fun pickAndEmit(action: String): LocalModelProvider? {
        val result = pickWithReason()
        val provider = result.provider
        if (provider != null) {
            _events.tryEmit(
                RouteEvent.Picked(
                    action = action,
                    activeIds = result.activeIds,
                    readiness = result.readiness,
                    providerId = provider.id,
                    providerName = provider.displayName,
                    reason = result.reason,
                ),
            )
        } else {
            _events.tryEmit(
                RouteEvent.None(
                    action = action,
                    activeIds = result.activeIds,
                    readiness = result.readiness,
                    reason = result.reason,
                ),
            )
        }
        return provider
    }

    /**
     * Whether at least one registered provider can serve a request right now.
     * Used by the UI to decide whether to drive bookmark generation through
     * AI or fall back to the legacy non-AI extractor. Does not emit an event.
     */
    suspend fun hasReadyProvider(): Boolean = pickWithReason().provider != null

    /**
     * Loads the weights for every active+registered provider into memory now,
     * so the first generate() call doesn't pay model-load cost (which would
     * skew the user-visible time-to-answer in comparisons). Called from app
     * startup after rehydration and from the model-activation toggle in
     * Settings. Cheap if the provider is already loaded — the bridges are
     * idempotent on the same path.
     */
    suspend fun warmUpActive() {
        val active = activeIdsProvider()
        if (active.isEmpty()) {
            Log.i(TAG, "warmUpActive: no active models, nothing to do")
            return
        }
        registry.snapshot()
            .filter { it.id in active }
            .forEach { provider ->
                val t0 = System.currentTimeMillis()
                Log.i(TAG, "warmUpActive: preloading ${provider.id}")
                _warmingIds.update { it + provider.id }
                try {
                    runCatching { provider.preload() }
                        .onSuccess {
                            Log.i(
                                TAG,
                                "warmUpActive: ${provider.id} ready in ${System.currentTimeMillis() - t0}ms",
                            )
                        }
                        .onFailure { e ->
                            Log.w(TAG, "warmUpActive: preload failed for ${provider.id}: ${e.message}", e)
                        }
                } finally {
                    _warmingIds.update { it - provider.id }
                }
            }
    }

    suspend fun generateTags(url: String, title: String, content: String): Result<List<String>> {
        val provider = pickAndEmit("tags")
            ?: return Result.failure(IllegalStateException("No ready local AI model"))
        return provider.generateTags(url, title, content)
    }

    suspend fun generateDescription(url: String, title: String): Result<String> {
        val provider = pickAndEmit("description")
            ?: return Result.failure(IllegalStateException("No ready local AI model"))
        return provider.generateDescription(url, title)
    }

    suspend fun generateTitle(url: String): Result<String> {
        val provider = pickAndEmit("title")
            ?: return Result.failure(IllegalStateException("No ready local AI model"))
        return provider.generateTitle(url)
    }
}
