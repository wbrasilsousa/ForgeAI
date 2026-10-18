@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.forgeai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.forgeai.data.HfResolver
import dev.forgeai.data.MemoryEstimate
import dev.forgeai.data.ModelDownloader
import dev.forgeai.data.ModelFit
import dev.forgeai.data.ModelInfo
import dev.forgeai.data.SuggestedModel
import dev.forgeai.data.SuggestedModels
import dev.forgeai.ui.AppViewModel
import dev.forgeai.ui.UrlState
import dev.forgeai.ui.components.Chip
import dev.forgeai.ui.components.SectionCard
import dev.forgeai.ui.components.StatChip
import dev.forgeai.ui.components.StatusPill
import dev.forgeai.ui.components.VSpace
import dev.forgeai.ui.theme.BrandGradient
import kotlinx.coroutines.delay

@Composable
fun ModelsScreen(vm: AppViewModel) {
    val models = vm.models
    val tasks = vm.downloadTasks.collectAsState().value
    var showAddSheet by remember { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf<ModelConfirm?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
    ) {
        VSpace(10)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Modelos", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Biblioteca local · GGUF",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (models.isNotEmpty()) {
                Surface(
                    onClick = { showAddSheet = true },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Adicionar", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        VSpace(12)
        StorageCard(vm)

        val active = tasks.values.filter { it.status == ModelDownloader.Status.DOWNLOADING || it.status == ModelDownloader.Status.QUEUED }
        val finished = tasks.values.filter { it.status == ModelDownloader.Status.DONE || it.status == ModelDownloader.Status.ERROR || it.status == ModelDownloader.Status.CANCELLED }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (active.isNotEmpty()) {
                item {
                    Text("Downloads", style = MaterialTheme.typography.titleMedium)
                }
                items(active, key = { it.id }) { task ->
                    DownloadCard(task, onCancel = { vm.cancelDownload(task.id) })
                }
            }
            if (finished.isNotEmpty()) {
                item {
                    Text("Concluídos", style = MaterialTheme.typography.titleMedium)
                }
                items(finished, key = { it.id }) { task ->
                    FinishedCard(
                        task = task,
                        onRemove = { vm.removeDownload(task.id) },
                        onRetry = { vm.retryDownload(task.id) },
                    )
                }
            }

            if (models.isEmpty()) {
                item {
                    VSpace(16)
                    EmptyModels(onAdd = { showAddSheet = true })
                }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Instalados (${models.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (models.any { !it.metaLoaded }) {
                            IconButton(onClick = {
                                vm.refreshModels(forceInspect = true)
                                vm.showSnack("Relendo metadados…")
                            }) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "Reler metadados dos modelos",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                items(models, key = { it.path }) { model ->
                    ModelCard(
                        model = model,
                        loading = vm.engineStatus is dev.forgeai.ui.EngineStatus.Loading,
                        availableBytes = vm.ram.availableBytes,
                        nCtx = vm.settingsFor(model.path).nCtx,
                        visionBytes = model.mmprojPath
                            ?.let { runCatching { java.io.File(it).length() }.getOrDefault(0L) }
                            ?: 0L,
                        onLoad = { if (model.isLoaded) vm.unloadModel() else pendingConfirm = ModelConfirm.Load(model) },
                        onDelete = { pendingConfirm = ModelConfirm.Delete(model) },
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        AddModelSheet(vm) { showAddSheet = false }
    }

    // Confirmações antes de carregar / excluir um modelo
    when (val confirm = pendingConfirm) {
        is ModelConfirm.Load -> ConfirmLoadDialog(
            model = confirm.model,
            onConfirm = {
                pendingConfirm = null
                vm.loadModel(confirm.model.path)
            },
            onDismiss = { pendingConfirm = null },
        )
        is ModelConfirm.Delete -> ConfirmDeleteDialog(
            model = confirm.model,
            onConfirm = {
                pendingConfirm = null
                vm.deleteModel(confirm.model)
            },
            onDismiss = { pendingConfirm = null },
        )
        null -> {}
    }
}

/** Ação do cartão de modelo que aguarda confirmação do usuário. */
private sealed interface ModelConfirm {
    data class Load(val model: ModelInfo) : ModelConfirm
    data class Delete(val model: ModelInfo) : ModelConfirm
}

@Composable
private fun ConfirmLoadDialog(
    model: ModelInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text("Carregar modelo?") },
        text = {
            Column {
                Text(model.displayName, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "O arquivo de ${model.sizeLabel} será carregado na memória. " +
                        "Isso pode levar alguns segundos e consumir bastante RAM no aparelho."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Carregar", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
private fun ConfirmDeleteDialog(
    model: ModelInfo,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Excluir modelo?") },
        text = {
            Column {
                Text(model.displayName, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "O arquivo de ${model.sizeLabel} será removido do dispositivo." +
                        (if (model.isLoaded) " O modelo será descarregado." else "") +
                        " Essa ação não pode ser desfeita."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

@Composable
private fun StorageCard(vm: AppViewModel) {
    val (free, used, total) = vm.storageInfo()
    val pct = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
    SectionCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Armazenamento", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    ModelInfo.formatBytes(used) + " usados",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Livres: ${ModelInfo.formatBytes(free)} de ${ModelInfo.formatBytes(total)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val ram = vm.ram
            if (ram.totalBytes > 0L) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "RAM livre: ${ModelInfo.formatBytes(ram.availableBytes)} de ${ModelInfo.formatBytes(ram.totalBytes)}" +
                        if (ram.lowMemory) " · memória baixa" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ram.lowMemory) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Model card
// ---------------------------------------------------------------------------

/** Badge "cabe / aperta / não cabe" com a estimativa de memória (TD-25). */
@Composable
private fun FitBadge(estimate: MemoryEstimate) {
    val color = when (estimate.fit) {
        MemoryEstimate.Fit.GOOD -> Color(0xFF4ADE80)
        MemoryEstimate.Fit.TIGHT -> Color(0xFFFBBF24)
        MemoryEstimate.Fit.NO_FIT -> MaterialTheme.colorScheme.error
    }
    StatusPill(
        text = "${estimate.shortLabel} · ~${estimate.totalLabel} de RAM",
        color = color,
    )
}
@Composable
private fun ModelCard(
    model: ModelInfo,
    loading: Boolean,
    availableBytes: Long,
    nCtx: Int,
    visionBytes: Long,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val estimate = ModelFit.estimate(
        model = model,
        nCtx = nCtx,
        availableBytes = availableBytes,
        visionBytes = visionBytes,
    )
    SectionCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (model.isLoaded) Brush.linearGradient(BrandGradient)
                            else SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Memory,
                        contentDescription = null,
                        tint = if (model.isLoaded) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Badges: parâmetros, quantização e arquitetura — só
                        // aparecem quando o valor é realmente conhecido (nada
                        // de placeholders como "—", "GGUF" ou "gguf").
                        model.params?.let { StatChip(ModelInfo.formatParams(it)) }
                        val quant = model.quant ?: HfResolver.quantFromName(model.fileName)
                        if (quant != null) StatChip(quant, accent = true)
                        model.arch?.let { StatChip(it) }
                        if (model.supportsTools) {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Build,
                                    contentDescription = "Suporta ferramentas (web access)",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                        if (model.supportsVision) {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Visibility,
                                    contentDescription = "Suporta imagens (visão)",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                    }
                    if (!model.metaLoaded) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "metadados não lidos — toque em ↻ para tentar de novo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (model.isLoaded) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = "carregado", tint = Color(0xFF4ADE80), modifier = Modifier.size(20.dp))
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        model.sizeLabel + (model.nCtxTrain?.let { " · ctx ${it}" } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Fit badge (TD-25): pesos + KV (contexto salvo do modelo)
                    // + buffer + mmproj vs RAM livre real.
                    if (model.metaLoaded) {
                        Spacer(Modifier.height(4.dp))
                        FitBadge(estimate)
                    }
                }
                if (!model.isLoaded) {
                    Surface(
                        onClick = onLoad,
                        shape = RoundedCornerShape(12.dp),
                        color = if (loading) MaterialTheme.colorScheme.surfaceContainerHigh else primary.copy(alpha = 0.14f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = primary,
                                )
                            } else {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = primary, modifier = Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Carregar",
                                color = primary,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                } else {
                    Surface(
                        onClick = onLoad,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Descarregar",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Excluir", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(19.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Downloads
// ---------------------------------------------------------------------------
@Composable
private fun DownloadCard(task: ModelDownloader.Task, onCancel: () -> Unit) {
    SectionCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.fileName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (task.partCount > 1) {
                        Text(
                            "parte ${task.partIndex} de ${task.partCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancelar", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    "${(task.progress * 100).toInt()}% · ${ModelInfo.formatBytes(task.downloadedBytes)} de ${ModelInfo.formatBytes(task.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (task.speedBps > 0) {
                    Text(
                        ModelInfo.formatBytes(task.speedBps) + "/s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun FinishedCard(
    task: ModelDownloader.Task,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val ok = task.status == ModelDownloader.Status.DONE
    SectionCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = if (ok) Color(0xFF4ADE80) else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    task.fileName,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (task.status == ModelDownloader.Status.ERROR) {
                    TextButton(onClick = onRetry) {
                        Text("tentar de novo", style = MaterialTheme.typography.labelMedium)
                    }
                }
                TextButton(onClick = onRemove) {
                    Text("remover", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (task.status == ModelDownloader.Status.ERROR) {
                Spacer(Modifier.height(4.dp))
                Text(
                    task.error ?: "Erro de rede",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------
@Composable
private fun EmptyModels(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(BrandGradient)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Nenhum modelo instalado", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Cole a URL de um modelo GGUF do HuggingFace ou escolha uma sugestão.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 30.dp),
        )
        Spacer(Modifier.height(14.dp))
        Surface(
            onClick = onAdd,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primary,
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Adicionar modelo", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Add model bottom sheet
// ---------------------------------------------------------------------------
@Composable
private fun AddModelSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var pastedOnce by remember { mutableStateOf(false) }
    val state = vm.urlState

    // Pre-fill from clipboard when the sheet opens
    LaunchedEffect(Unit) {
        val clip = clipboard.getText()?.text?.trim().orEmpty()
        if (clip.isNotEmpty() && (clip.startsWith("http") || clip.startsWith("hf://") || clip.contains("/"))) {
            url = clip
            pastedOnce = true
            vm.resolveUrl(clip)
        }
    }

    // Auto-detect when the URL stops changing (silent while typing)
    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        delay(700)
        vm.resolveUrl(url, silent = true)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).navigationBarsPadding()) {
            Text("Adicionar modelo", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Cole a URL do HuggingFace — o app identifica sozinho os arquivos GGUF disponíveis.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // URL input
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank()) vm.resolveUrl(url) }),
                        decorationBox = { inner ->
                            Box {
                                if (url.isEmpty()) {
                                    Text(
                                        "huggingface.co/org/repo…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    IconButton(onClick = {
                        clipboard.getText()?.text?.let {
                            url = it
                            vm.resolveUrl(it)
                        }
                    }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = "Colar", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            when (val st = state) {
                is UrlState.Idle -> {
                    if (pastedOnce && url.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Identificando arquivos GGUF…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                is UrlState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Identificando arquivos GGUF…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is UrlState.Error -> {
                    Text(st.message, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
                is UrlState.Ready -> {
                    val sorted = st.files.sortedWith(
                        compareByDescending<HfResolver.HfFile> { HfResolver.isRecommendedQuant(HfResolver.quantFromName(it.fileName)) }
                            .thenBy { it.sizeBytes ?: Long.MAX_VALUE }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Repositório: ${st.repo}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            Text("${sorted.size} arquivo(s) GGUF encontrados", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        Modifier.fillMaxWidth().height(260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sorted) { file ->
                            val isMmproj = ModelInfo.isMmprojName(file.fileName)
                            FileRow(
                                file = file,
                                hasVision = !isMmproj && HfResolver.findMmproj(st.files, file.fileName) != null,
                                onClick = {
                                    val mmproj = if (isMmproj) null else HfResolver.findMmproj(st.files, file.fileName)
                                    vm.downloadModel(st.repo, file.fileName, file.sizeBytes, file.partCount, mmproj)
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Sugestões para celular", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                Modifier.fillMaxWidth().height(210.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SuggestedModels.all) { s ->
                    SuggestionRow(
                        s = s,
                        availableBytes = vm.ram.availableBytes,
                        onDownload = { vm.downloadModel(s.repo, s.file, s.sizeBytes, 1, s.mmprojFile) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One row of a GGUF file found in a repository, with quant badge. */
@Composable
private fun FileRow(file: HfResolver.HfFile, hasVision: Boolean = false, onClick: () -> Unit) {
    val quant = HfResolver.quantFromName(file.fileName)
    val recommended = HfResolver.isRecommendedQuant(quant)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (recommended) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
        else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(file.fileName, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (quant != null) StatChip(quant, accent = true)
                    if (recommended) StatChip("recomendado")
                    if (file.partCount > 1) StatChip("${file.partCount} partes")
                    if (hasVision) StatChip("📷 visão")
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                file.sizeBytes?.let { ModelInfo.formatBytes(it) } ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SuggestionRow(s: SuggestedModel, availableBytes: Long, onDownload: () -> Unit) {
    // Sem metadados do GGUF ainda: compara só o peso dos arquivos com a RAM
    // livre (o KV cache entra depois, no cartão do modelo instalado).
    val weightRatio = if (availableBytes > 0L) s.sizeBytes.toFloat() / availableBytes else 0f
    val hint = when {
        availableBytes <= 0L -> null
        weightRatio <= 0.25f -> "leve"
        weightRatio <= 0.50f -> "ok"
        else -> "pesado"
    }
    Surface(
        onClick = onDownload,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatChip(s.paramsLabel, accent = true)
                    StatChip(s.sizeLabel)
                    if (s.supportsVision) StatChip("📷 visão")
                    hint?.let { StatChip(it) }
                }
                Spacer(Modifier.height(3.dp))
                Text(s.repo.split("/").last(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(s.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Chip("Baixar", icon = Icons.Filled.Download) { onDownload() }
        }
    }
}

// (helper removed — StateFlow collected via collectAsState)

