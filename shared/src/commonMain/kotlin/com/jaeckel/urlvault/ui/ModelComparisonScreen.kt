package com.jaeckel.urlvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jaeckel.urlvault.ai.ModelComparisonRunner
import com.jaeckel.urlvault.ai.ModelRuntime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelComparisonScreen(
    runner: ModelComparisonRunner,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<ModelComparisonRunner.ProviderResult>>(emptyList()) }
    var noProvidersMessage by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<ModelComparisonRunner.RunProgress?>(null) }

    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Model comparison") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Text(
                            text = "←",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Run all installed local models against the same input and compare tags, descriptions, titles and latency side-by-side.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description / notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                enabled = url.isNotBlank() && !running,
                onClick = {
                    running = true
                    noProvidersMessage = null
                    results = emptyList()
                    progress = null
                    scope.launch {
                        try {
                            val out = runner.runAll(
                                url = url.trim(),
                                title = title,
                                userDescription = description,
                                onProgress = { progress = it },
                                onResult = { result -> results = results + result },
                            )
                            results = out
                            if (out.isEmpty()) {
                                noProvidersMessage = "No ready local models. Download one in Settings → Local AI Models."
                            }
                        } finally {
                            running = false
                            progress = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Text("  Running...", style = MaterialTheme.typography.labelLarge)
                } else {
                    Text("Run comparison")
                }
            }

            if (running) {
                val currentProgress = progress
                val progressText = when {
                    currentProgress == null -> "Preparing comparison..."
                    currentProgress.totalProviders == 0 -> "Looking for ready local models..."
                    currentProgress.phase == ModelComparisonRunner.RunPhase.PRELOADING &&
                        currentProgress.activeProviderDisplayName != null ->
                        "Completed ${currentProgress.completedProviders}/${currentProgress.totalProviders} - Loading ${currentProgress.activeProviderDisplayName} into memory..."
                    currentProgress.phase == ModelComparisonRunner.RunPhase.RUNNING &&
                        currentProgress.activeProviderDisplayName != null ->
                        "Completed ${currentProgress.completedProviders}/${currentProgress.totalProviders} - Running ${currentProgress.activeProviderDisplayName}"
                    currentProgress.activeProviderDisplayName != null ->
                        "Completed ${currentProgress.completedProviders}/${currentProgress.totalProviders} - Working on ${currentProgress.activeProviderDisplayName}"
                    else ->
                        "Completed ${currentProgress.completedProviders}/${currentProgress.totalProviders}"
                }
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            noProvidersMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            results.forEach { result ->
                ProviderResultCard(result)
            }
        }
    }
}

@Composable
private fun ProviderResultCard(result: ModelComparisonRunner.ProviderResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = result.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = runtimeLabel(result.runtime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (result.error != null) {
                Text(
                    text = "Error: ${result.error}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            ResultLine(
                label = "Title",
                value = result.title.ifBlank { "(empty)" },
                ms = result.titleMs,
            )
            ResultLine(
                label = "Description",
                value = result.description.ifBlank { "(empty)" },
                ms = result.descriptionMs,
            )
            ResultLine(
                label = "Tags",
                value = if (result.tags.isEmpty()) "(empty)" else result.tags.joinToString(", "),
                ms = result.tagsMs,
            )
        }
    }
}

@Composable
private fun ResultLine(label: String, value: String, ms: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${ms}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Human-friendly label for a runtime. The enum name `MEDIAPIPE` is a
 * historical leftover from when the LiteRT-LM bundle was loaded via
 * MediaPipe-LLM; the actual runtime today is LiteRT-LM, so render it that
 * way in the UI rather than leaking the enum constant.
 */
private fun runtimeLabel(runtime: ModelRuntime): String = when (runtime) {
    ModelRuntime.ML_KIT -> "AICore"
    ModelRuntime.LLAMA_CPP -> "llama.cpp"
    ModelRuntime.LEAP -> "Leap"
    ModelRuntime.MEDIAPIPE -> "LiteRT-LM"
}
