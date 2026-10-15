@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.forgeai.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.forgeai.core.AppSettings
import dev.forgeai.core.ModelSettings
import dev.forgeai.ui.AppViewModel
import dev.forgeai.ui.components.Chip
import dev.forgeai.ui.components.SectionCard
import dev.forgeai.ui.components.StatChip
import dev.forgeai.ui.components.VSpace

/**
 * Ajustes foi dividido em duas sessões:
 *  - Ajustes do modelo: geração + memória/velocidade, POR MODELO (cada modelo
 *    guarda a própria configuração).
 *  - Ajustes gerais: ferramentas, aparência, prompt do sistema, sistema e zona
 *    de perigo (escopo global).
 */
@Composable
fun SettingsScreen(vm: AppViewModel, onGoToModels: () -> Unit) {
    var section by rememberSaveable { mutableStateOf(0) }
    when (section) {
        1 -> ModelSettingsPage(vm, onGoToModels, onBack = { section = 0 })
        2 -> GlobalSettingsPage(vm, onBack = { section = 0 })
        else -> SettingsHome(
            vm = vm,
            onModel = { section = 1 },
            onGlobal = { section = 2 },
        )
    }
}

// ---------------------------------------------------------------------------
// Home (escolha da sessão)
// ---------------------------------------------------------------------------
@Composable
private fun SettingsHome(
    vm: AppViewModel,
    onModel: () -> Unit,
    onGlobal: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        VSpace(10)
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Escolha uma sessão de configuração",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VSpace(18)

        SettingsEntryCard(
            icon = Icons.Filled.Tune,
            title = "Ajustes do modelo",
            subtitle = "Geração, memória e velocidade — cada modelo guarda a própria configuração.",
            badge = vm.loadedModel?.displayName,
            onClick = onModel,
        )
        VSpace(12)
        SettingsEntryCard(
            icon = Icons.Filled.Settings,
            title = "Ajustes gerais",
            subtitle = "Ferramentas, aparência, prompt do sistema e dados do dispositivo.",
            onClick = onGlobal,
        )
        VSpace(30)
    }
}

@Composable
private fun SettingsEntryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    badge: String? = null,
) {
    SectionCard(Modifier.fillMaxWidth()) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            color = Color.Transparent,
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (badge != null) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Ajustes do modelo (por modelo)
// ---------------------------------------------------------------------------
@Composable
private fun ModelSettingsPage(
    vm: AppViewModel,
    onGoToModels: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val models = vm.models
    val loaded = vm.loadedModel

    var selectedPath by rememberSaveable {
        mutableStateOf(loaded?.path ?: models.firstOrNull()?.path)
    }
    val selModel = models.firstOrNull { it.path == selectedPath } ?: models.firstOrNull()
    val path = selModel?.path
    var ms by remember(path) {
        mutableStateOf(path?.let { vm.settingsFor(it) } ?: ModelSettings())
    }
    fun update(new: ModelSettings) {
        ms = new
        path?.let { vm.updateModelSettings(it, new) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsTopBar("Ajustes do modelo", "Geração, memória e velocidade", onBack)
        VSpace(12)

        if (selModel == null) {
            SectionCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Nenhum modelo instalado", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Baixe um modelo GGUF para configurar a geração e a memória.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        onClick = onGoToModels,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            "Baixar modelo",
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            VSpace(30)
            return@Column
        }

        // seletor de modelo
        var selectorOpen by remember { mutableStateOf(false) }
        val isLoaded = path == loaded?.path
        Box {
            Surface(
                onClick = { selectorOpen = true },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Modelo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            selModel.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                    Text(
                        if (isLoaded) "em uso" else "ao carregar",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = if (isLoaded) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DropdownMenu(expanded = selectorOpen, onDismissRequest = { selectorOpen = false }) {
                models.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.displayName, maxLines = 1) },
                        onClick = {
                            selectorOpen = false
                            selectedPath = m.path
                        },
                    )
                }
            }
        }
        VSpace(16)

        SectionHeader("Geração", Icons.Filled.Tune)
        SectionCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                SettingSlider(
                    title = "Temperatura",
                    subtitle = "Criatividade vs. previsibilidade",
                    value = ms.temperature,
                    range = 0f..2f,
                    format = { "%.2f".format(it) },
                    onChange = { update(ms.copy(temperature = it)) },
                )
                SettingSlider(
                    title = "Top-P",
                    subtitle = "Filtro de massa de probabilidade",
                    value = ms.topP,
                    range = 0.05f..1f,
                    format = { "%.2f".format(it) },
                    onChange = { update(ms.copy(topP = it)) },
                )
                SettingSlider(
                    title = "Top-K",
                    subtitle = "Amostra apenas dos K tokens mais prováveis",
                    value = ms.topK.toFloat(),
                    range = 1f..100f,
                    steps = 98,
                    format = { it.toInt().toString() },
                    onChange = { update(ms.copy(topK = it.toInt())) },
                )
                SettingSlider(
                    title = "Min-P",
                    subtitle = "Descarta tokens improváveis",
                    value = ms.minP,
                    range = 0f..0.2f,
                    format = { "%.2f".format(it) },
                    onChange = { update(ms.copy(minP = it)) },
                )
                SettingSlider(
                    title = "Penalidade de repetição",
                    subtitle = "Evita loops (1.0 = desligado)",
                    value = ms.repeatPenalty,
                    range = 1f..1.5f,
                    format = { "%.2f".format(it) },
                    onChange = { update(ms.copy(repeatPenalty = it)) },
                )
                SettingSlider(
                    title = "Máx. de tokens por resposta",
                    value = ms.maxTokens.toFloat(),
                    range = 64f..2048f,
                    steps = 30,
                    format = { it.toInt().toString() },
                    onChange = { update(ms.copy(maxTokens = it.toInt())) },
                )
                SettingSwitch(
                    title = "Semente aleatória",
                    subtitle = "Respostas diferentes a cada geração",
                    checked = ms.randomSeed,
                    onChange = { update(ms.copy(randomSeed = it)) },
                )
            }
        }
        VSpace(14)

        SectionHeader("Memória e velocidade", Icons.Filled.Speed)
        SectionCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                SettingSlider(
                    title = "Contexto (tokens)",
                    subtitle = "Memória da conversa. Valores altos usam mais RAM.",
                    value = ms.nCtx.toFloat(),
                    range = 512f..8192f,
                    steps = 7,
                    format = { it.toInt().toString() },
                    onChange = { update(ms.copy(nCtx = it.toInt())) },
                )
                SettingSlider(
                    title = "Threads de CPU",
                    subtitle = "Geração usa este valor; o prompt usa até 8 threads automaticamente",
                    value = ms.threads.toFloat(),
                    range = 1f..8f,
                    steps = 6,
                    format = { it.toInt().toString() },
                    onChange = { update(ms.copy(threads = it.toInt())) },
                )
                if (vm.vulkanAvailable) {
                    SettingSlider(
                        title = "Camadas na GPU (Vulkan)",
                        subtitle = "0 = só CPU · 99 = tudo na GPU",
                        value = ms.gpuLayers.toFloat(),
                        range = 0f..99f,
                        steps = 98,
                        format = { if (it.toInt() <= 0) "CPU" else it.toInt().toString() },
                        onChange = { update(ms.copy(gpuLayers = it.toInt())) },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Alterações de contexto/threads/GPU valem ao recarregar o modelo.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isLoaded) {
                    VSpace(8)
                    Surface(
                        onClick = { vm.applyLoadParams() },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Recarregar modelo atual", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        VSpace(30)
    }
}

// ---------------------------------------------------------------------------
// Ajustes gerais (escopo global)
// ---------------------------------------------------------------------------
@Composable
private fun GlobalSettingsPage(vm: AppViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val settings = vm.settings
    var showClearModelsDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsTopBar("Ajustes gerais", "Ferramentas, aparência e sistema", onBack)
        VSpace(12)

        SectionHeader("Ferramentas", Icons.Filled.Build)
        SectionCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                SettingSwitch(
                    title = "Web access (pesquisa + navegação)",
                    subtitle = "Permite o modelo pesquisar na internet (web_search) e abrir páginas (web_access) para responder. Requer internet e envia consultas para a web — os dados saem do aparelho. Só funciona com modelos que suportam ferramentas (ex.: Qwen2.5, Qwen3.5).",
                    checked = settings.webAccess,
                    onChange = { vm.updateSettings(settings.copy(webAccess = it)) },
                )
                if (settings.webAccess && vm.loadedModel != null && !vm.modelSupportsTools) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "O modelo carregado não suporta ferramentas (template sem <tools>). O web access não será usado.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        VSpace(14)

        SectionHeader("Aparência", Icons.Filled.Palette)
        SectionCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(
                        label = "Escuro",
                        icon = Icons.Filled.DarkMode,
                        selected = settings.darkTheme,
                        onClick = { vm.updateSettings(settings.copy(darkTheme = true)) },
                    )
                    Chip(
                        label = "Claro",
                        icon = Icons.Filled.LightMode,
                        selected = !settings.darkTheme,
                        onClick = { vm.updateSettings(settings.copy(darkTheme = false)) },
                    )
                }
                VSpace(10)
                SettingSwitch(
                    title = "Cores dinâmicas (Material You)",
                    subtitle = "Use a paleta do seu papel de parede",
                    checked = settings.dynamicColor,
                    onChange = { vm.updateSettings(settings.copy(dynamicColor = it)) },
                )
                SettingSwitch(
                    title = "Tela cheia imersiva",
                    subtitle = "Esconde a barra de status e navegação",
                    checked = settings.immersive,
                    onChange = { vm.updateSettings(settings.copy(immersive = it)) },
                )
            }
        }
        VSpace(14)

        SectionHeader("Modelo", Icons.Filled.AutoAwesome)
        SectionCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Prompt do sistema", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Personalidade e instruções iniciais do assistente.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    BasicTextField(
                        value = settings.systemPrompt,
                        onValueChange = { vm.updateSettings(settings.copy(systemPrompt = it)) },
                        modifier = Modifier.fillMaxWidth().padding(12.dp).height(90.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        VSpace(14)

        SectionHeader("Sistema", Icons.Filled.Info)
        SectionCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                AboutRow("App", "ForgeAI 1.0.0")
                AboutRow("Motor", "llama.cpp b10333")
                val sysInfo by vm.systemInfo.collectAsState()
                AboutRow("Backend", sysInfo.ifBlank { "…" }, maxLines = 3)
                val gpus by vm.gpuDevices.collectAsState()
                AboutRow("GPU", if (gpus.isEmpty()) "não detectada (build CPU)" else gpus.joinToString())
                val (free, used, total) = vm.storageInfo()
                AboutRow("Modelos", "${vm.models.size} instalados · ${dev.forgeai.data.ModelInfo.formatBytes(used)}")
                AboutRow("Armazenamento", "livre ${dev.forgeai.data.ModelInfo.formatBytes(free)} / ${dev.forgeai.data.ModelInfo.formatBytes(total)}")
            }
        }
        VSpace(14)

        SectionCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Zona de perigo", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Surface(
                    onClick = { showClearModelsDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Apagar todos os modelos", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        VSpace(30)
    }

    if (showClearModelsDialog) {
        AlertDialog(
            onDismissRequest = { showClearModelsDialog = false },
            title = { Text("Apagar todos os modelos?") },
            text = { Text("Os arquivos GGUF serão removidos do dispositivo. Downloads em andamento serão cancelados. Essa ação não pode ser desfeita.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearModelsDialog = false
                    vm.models.forEach { vm.deleteModel(it) }
                }) {
                    Text("Apagar tudo", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearModelsDialog = false }) {
                    Text("Cancelar")
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
@Composable
private fun SettingsTopBar(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Voltar",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Column {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
    subtitle: String? = null,
    steps: Int = 0,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            StatChip(format(value))
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AboutRow(label: String, value: String, maxLines: Int = 1) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
            maxLines = maxLines,
        )
    }
}
