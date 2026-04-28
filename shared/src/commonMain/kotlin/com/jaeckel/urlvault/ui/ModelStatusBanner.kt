package com.jaeckel.urlvault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jaeckel.urlvault.ai.ModelCatalogEntry
import com.jaeckel.urlvault.ai.ModelDownloadState

/**
 * Thin status banner shown above the screen content. Surfaces two
 * background events that would otherwise be silent:
 *
 *  - "Loading <model> into memory…" while a provider is being warmed
 *    up (LeapSDK / LiteRT-LM open + native init can take 5–15s on the
 *    big bundles).
 *  - "Downloading <model> XX%" while a model file is streaming from
 *    Hugging Face — useful even when the user is on AddEditBookmark
 *    instead of Settings.
 *
 * Auto-hides when nothing is in flight. Active model takes priority
 * over background work so the banner reflects the thing the user is
 * actually waiting on.
 */
@Composable
fun ModelStatusBanner(
    warmingIds: Set<String>,
    downloadStates: Map<String, ModelDownloadState>,
    activeIds: Set<String>,
    catalog: List<ModelCatalogEntry>,
    aiCoreDisplayName: String = "Google Gemini Nano (AICore)",
    aiCoreId: String? = null,
    modifier: Modifier = Modifier,
) {
    val displayNameById = remember(catalog, aiCoreId, aiCoreDisplayName) {
        buildMap {
            catalog.forEach { put(it.id, it.displayName) }
            if (aiCoreId != null) put(aiCoreId, aiCoreDisplayName)
        }
    }

    val warmingId = activeIds.firstOrNull { it in warmingIds }
        ?: warmingIds.firstOrNull()

    val downloadingEntry = activeIds
        .firstOrNull { downloadStates[it] is ModelDownloadState.Downloading }
        ?.let { it to downloadStates.getValue(it) as ModelDownloadState.Downloading }
        ?: downloadStates.entries
            .firstOrNull { it.value is ModelDownloadState.Downloading }
            ?.let { it.key to it.value as ModelDownloadState.Downloading }

    val mode: BannerMode? = when {
        warmingId != null -> BannerMode.Warming(
            id = warmingId,
            displayName = displayNameById[warmingId] ?: warmingId,
        )
        downloadingEntry != null -> BannerMode.Downloading(
            id = downloadingEntry.first,
            displayName = displayNameById[downloadingEntry.first] ?: downloadingEntry.first,
            state = downloadingEntry.second,
        )
        else -> null
    }

    AnimatedVisibility(
        visible = mode != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        if (mode != null) BannerContent(mode)
    }
}

private sealed class BannerMode {
    abstract val id: String
    abstract val displayName: String

    data class Warming(
        override val id: String,
        override val displayName: String,
    ) : BannerMode()

    data class Downloading(
        override val id: String,
        override val displayName: String,
        val state: ModelDownloadState.Downloading,
    ) : BannerMode()
}

@Composable
private fun BannerContent(mode: BannerMode) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.size(8.dp))
            val (verb, suffix) = when (mode) {
                is BannerMode.Warming -> "Loading model into memory" to ""
                is BannerMode.Downloading -> {
                    val pct = (mode.state.progress * 100).toInt().coerceIn(0, 100)
                    "Downloading model" to "  $pct%"
                }
            }
            Text(
                text = "$verb$suffix",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(
            text = mode.displayName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (mode is BannerMode.Downloading) {
            LinearProgressIndicator(
                progress = { mode.state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
