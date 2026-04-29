package com.jaeckel.urlvault.android.ai

import android.util.Log
import com.jaeckel.urlvault.ai.LocalModelProvider
import com.jaeckel.urlvault.ai.LocalModelRegistry
import com.jaeckel.urlvault.ai.ModelRuntime
import com.jaeckel.urlvault.android.BuildConfig
import kotlinx.coroutines.channels.BufferOverflow
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

        /**
         * Fired by `generateXxx` *after* the provider call returns or throws.
         * Carries the wall-clock duration so a UI status line can show
         * "tags via Liquid LFM2 Extract — 1247 ms". Note that for `title` on
         * pages with a usable `<title>`/`og:title`, no LLM ran — duration
         * reflects only the page fetch, which is intentional.
         */
        data class Completed(
            override val action: String,
            override val activeIds: Set<String>,
            override val readiness: List<Pair<String, Boolean>>,
            val providerId: String,
            val providerName: String,
            val durationMs: Long,
            val success: Boolean,
        ) : RouteEvent()
    }

    // DROP_OLDEST so a slow / backgrounded collector can never stall the
    // generate path or silently lose the latest event. The UI only cares
    // about *current* state, so dropping older Picked/Completed pairs is
    // safer than letting tryEmit return false for the most recent one.
    private val _events = MutableSharedFlow<RouteEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
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
        // Polled from the UI on every download tick — keep at DEBUG so release
        // builds don't spam logcat. Gated by isLoggable so we don't pay the
        // joinToString cost when the level is filtered out.
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG,
                "pick: activeIds=$active registered=${all.size} -> " +
                    readiness.joinToString { (p, r) -> "${p.id}(ready=$r)" },
            )
        }

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
        val pick = pickWithReason()
        val provider = pick.provider
        if (provider == null) {
            emitNone("tags", pick)
            return Result.failure(IllegalStateException("No ready local AI model"))
        }
        emitPicked("tags", provider, pick)
        val t0 = System.nanoTime()
        val result = runTimed("tags", provider, pick) {
            provider.generateTags(url, title, content)
        }
        val durationMs = (System.nanoTime() - t0) / 1_000_000
        // DEBUG-only: append a synthetic tag identifying which SDK ran and how
        // long it took, so a glance at the saved bookmark tells you both at
        // once. Stripped in release builds so synced Bitwarden entries never
        // carry `dbg:…` tags into production.
        return if (BuildConfig.DEBUG) {
            result.map { it + debugProvenanceTag(provider, durationMs) }
        } else {
            result
        }
    }

    private fun debugProvenanceTag(provider: LocalModelProvider, durationMs: Long): String {
        val sdk = when (provider.runtime) {
            ModelRuntime.ML_KIT -> "aicore"
            ModelRuntime.LLAMA_CPP -> "llama"
            ModelRuntime.LEAP -> "leap"
            ModelRuntime.MEDIAPIPE -> "liteRt"
        }
        // ms below 1s, two-decimal seconds above. Avoids `String.format`
        // (host-locale-dependent) by doing the math directly.
        val duration = if (durationMs < 1000) {
            "${durationMs}ms"
        } else {
            val whole = durationMs / 1000
            val hundredths = (durationMs % 1000) / 10
            val padded = if (hundredths < 10) "0$hundredths" else "$hundredths"
            "$whole.${padded}s"
        }
        return "dbg:$sdk@$duration"
    }

    suspend fun generateDescription(url: String, title: String): Result<String> {
        val pick = pickWithReason()
        val provider = pick.provider
        if (provider == null) {
            emitNone("description", pick)
            return Result.failure(IllegalStateException("No ready local AI model"))
        }
        emitPicked("description", provider, pick)
        return runTimed("description", provider, pick) { provider.generateDescription(url, title) }
    }

    suspend fun generateTitle(url: String): Result<String> {
        val pick = pickWithReason()
        val provider = pick.provider
        if (provider == null) {
            emitNone("title", pick)
            return Result.failure(IllegalStateException("No ready local AI model"))
        }
        emitPicked("title", provider, pick)
        return runTimed("title", provider, pick) { provider.generateTitle(url) }
    }

    private fun emitPicked(action: String, provider: LocalModelProvider, pick: PickResult) {
        _events.tryEmit(
            RouteEvent.Picked(
                action = action,
                activeIds = pick.activeIds,
                readiness = pick.readiness,
                providerId = provider.id,
                providerName = provider.displayName,
                reason = pick.reason,
            ),
        )
    }

    private fun emitNone(action: String, pick: PickResult) {
        _events.tryEmit(
            RouteEvent.None(
                action = action,
                activeIds = pick.activeIds,
                readiness = pick.readiness,
                reason = pick.reason,
            ),
        )
    }

    /**
     * Times [block] and emits a [RouteEvent.Completed] regardless of how it
     * exits — normal `Result` (success or failure), or thrown exception
     * (notably coroutine cancellation, which `runCatching` re-raises). Without
     * the try/finally, a cancellation would leave the UI strip stuck in
     * "Running…" forever.
     *
     * `inline` is what lets the non-suspending `block` parameter actually call
     * suspending provider methods — the lambda body is inlined into this
     * `suspend` function's body, so it runs in a suspending context.
     *
     * `nanoTime` is monotonic; `currentTimeMillis` is wall-clock and can jump
     * backwards on NTP / manual clock changes, producing negative durations.
     */
    private suspend inline fun <T> runTimed(
        action: String,
        provider: LocalModelProvider,
        pick: PickResult,
        block: () -> Result<T>,
    ): Result<T> {
        val t0 = System.nanoTime()
        var success = false
        try {
            val result = block()
            success = result.isSuccess
            return result
        } finally {
            _events.tryEmit(
                RouteEvent.Completed(
                    action = action,
                    activeIds = pick.activeIds,
                    readiness = pick.readiness,
                    providerId = provider.id,
                    providerName = provider.displayName,
                    durationMs = (System.nanoTime() - t0) / 1_000_000,
                    success = success,
                ),
            )
        }
    }
}
