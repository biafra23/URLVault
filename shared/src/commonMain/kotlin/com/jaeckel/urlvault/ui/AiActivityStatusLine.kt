package com.jaeckel.urlvault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * State for the bottom AI-activity strip. Replaces the debug Toast that
 * used to surface router decisions on every `generateXxx` call. Auto-hide
 * is a presentation concern and lives in the caller — this composable just
 * renders whatever it's told to.
 */
sealed class AiActivityState {
    data object Hidden : AiActivityState()

    /** A provider was picked; inference is in flight. Shows a spinner. */
    data class Running(
        val action: String,
        val providerName: String,
    ) : AiActivityState()

    /** Inference finished. Shows the wall-clock duration. */
    data class Completed(
        val action: String,
        val providerName: String,
        val durationMs: Long,
        val success: Boolean,
    ) : AiActivityState()

    /** Router could not pick a provider — UI surfaces the reason. */
    data class NoProvider(
        val action: String,
        val reason: String,
    ) : AiActivityState()
}

/**
 * Slim auto-hiding strip rendered at the bottom of the app. Designed as the
 * non-obstructive replacement for the debug Toast spam: a single line that
 * slides up while AI work is in flight, then briefly shows the timing, then
 * slides away.
 *
 * Add it as the **last child of your screen's Column** (with the screen
 * content above it given `Modifier.weight(1f)`) so it claims real layout
 * space when visible and pushes content up. Putting it in an overlaying
 * `Box` will reintroduce the obscuring behaviour the original Toast had —
 * the whole point of this strip is that buttons stay reachable while it's
 * showing.
 *
 * Auto-hide of [AiActivityState.Completed] / [AiActivityState.NoProvider] is
 * the caller's responsibility — use a `LaunchedEffect(state)` with a `delay`
 * and reset to [AiActivityState.Hidden].
 */
@Composable
fun AiActivityStatusLine(
    state: AiActivityState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state !is AiActivityState.Hidden,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        val (text, isRunning, isError) = when (state) {
            is AiActivityState.Running -> Triple(
                "${state.action}: ${state.providerName}…",
                true,
                false,
            )
            is AiActivityState.Completed -> Triple(
                buildString {
                    append(state.action)
                    append(" via ")
                    append(state.providerName)
                    append(" — ")
                    append(formatMs(state.durationMs))
                    if (!state.success) append(" (failed)")
                },
                false,
                !state.success,
            )
            is AiActivityState.NoProvider -> Triple(
                "${state.action}: no model ready (${state.reason})",
                false,
                true,
            )
            // Hidden never reached here — AnimatedVisibility hides the slot.
            AiActivityState.Hidden -> Triple("", false, false)
        }

        Surface(
            color = if (isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer
                           else MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color.Unspecified,
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms < 1000) return "$ms ms"
    // Two decimal places without depending on String.format (not in commonMain).
    val whole = ms / 1000
    val hundredths = (ms % 1000) / 10
    val padded = if (hundredths < 10) "0$hundredths" else "$hundredths"
    return "$whole.$padded s"
}
