@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.forgeai.ui.screens
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.forgeai.LlamaEngine
import dev.forgeai.ui.AppViewModel
import dev.forgeai.ui.ChatMessage
import dev.forgeai.ui.ChatRole
import dev.forgeai.ui.EngineStatus
import dev.forgeai.ui.components.Chip
import dev.forgeai.ui.components.MarkdownContent
import dev.forgeai.ui.components.StatChip
import dev.forgeai.ui.components.StatusPill
import dev.forgeai.ui.components.TypingIndicator
import dev.forgeai.data.ToolSupport
import dev.forgeai.ui.theme.BrandGradient
import dev.forgeai.ui.theme.BrandGradient2
import dev.forgeai.ui.theme.UserBubbleGradient
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

@Composable
fun ChatScreen(
    vm: AppViewModel,
    onOpenDrawer: () -> Unit,
    onGoToModels: () -> Unit,
    onGoToSettings: () -> Unit,
) {
    val messages = vm.messages
    val isGenerating = vm.isGenerating
    val status = vm.engineStatus
    val listState = rememberLazyListState()
    val context = LocalContext.current

    var input by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    // ------------------------------------------------------------------
    // Image attachment (vision models only)
    // ------------------------------------------------------------------
    var pendingImagePath by rememberSaveable { mutableStateOf<String?>(null) }
    val visionAvailable = vm.modelSupportsVision

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val path = vm.importImage(uri)
            if (path != null) pendingImagePath = path
            else vm.showSnack("Não foi possível abrir a imagem")
        }
    }

    // ------------------------------------------------------------------
    // Voice input (speech-to-text via the system recognizer)
    // ------------------------------------------------------------------
    // Only offered when the device has a speech recognizer and a model is
    // loaded — for the (text-only) GGUF chat models this is what "supports
    // voice" means: the prompt is dictated and inserted as text.
    val speechAvailable = remember {
        SpeechRecognizer.isRecognitionAvailable(context)
    }
    var listening by remember { mutableStateOf(false) }

    val recognizer = remember(context) {
        SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    listening = false
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Não entendi — tente de novo"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nenhuma fala detectada"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconhecimento ocupado — tente de novo"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissão de microfone negada"
                        SpeechRecognizer.ERROR_NETWORK -> "Falha de rede no reconhecimento de voz"
                        else -> "Erro no reconhecimento de voz"
                    }
                    vm.showSnack(msg)
                }

                override fun onResults(results: Bundle?) {
                    listening = false
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { input = it }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (!listening) return
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { input = it }
                }
            })
        }
    }
    DisposableEffect(Unit) {
        onDispose { recognizer.destroy() }
    }

    val speechIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            listening = true
            runCatching { recognizer.startListening(speechIntent) }
                .onFailure { listening = false; vm.showSnack("Não foi possível iniciar o microfone") }
        } else {
            vm.showSnack("Permissão de microfone negada — ative nas configurações")
        }
    }

    val startListening: () -> Unit = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            listening = true
            runCatching { recognizer.startListening(speechIntent) }
                .onFailure { listening = false; vm.showSnack("Não foi possível iniciar o microfone") }
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val stopListening: () -> Unit = {
        listening = false
        runCatching { recognizer.stopListening() } // commits current partials -> onResults
    }

    // autoscroll
    LaunchedEffect(messages.size, vm.streamingText) {
        if (messages.isNotEmpty() && !listState.isScrollInProgress) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ChatTopBar(vm, menuOpen, { menuOpen = it }, onGoToModels, onOpenDrawer)

        // status / loading bar
        when (status) {
            is EngineStatus.Loading -> {
                val progress by vm.loadProgress.collectAsState()
                LoadingBar(progress = progress)
            }
            is EngineStatus.Ready -> {
                if (vm.loadedModel != null) {
                    ModelBar(vm, onGoToSettings)
                }
            }
            else -> {}
        }

        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                EmptyChat(onGoToModels = onGoToModels)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 14.dp, vertical = 10.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = { it.id }) { msg ->
                        MessageBubble(
                            msg,
                            isLast = messages.lastOrNull()?.id == msg.id,
                            streamOpenThink = vm.streamOpenThink,
                        )
                    }
                    item {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // stats + actions under the list
        vm.lastStats?.let { stats ->
            if (stats.tokens > 0 || stats.promptTokens > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    StatChip(
                        "⚡ %.1f tok/s".format(stats.tps),
                        accent = true,
                    )
                    Spacer(Modifier.width(6.dp))
                    StatChip("${stats.tokens} tokens · ${stats.promptTokens} prompt")
                }
            }
        }

        ChatInputBar(
            input = input,
            onInput = { input = it },
            isGenerating = isGenerating,
            canSend = status is EngineStatus.Ready && vm.loadedModel != null,
            visionAvailable = visionAvailable,
            imagePath = pendingImagePath,
            onRemoveImage = { pendingImagePath = null },
            onAttach = {
                if (visionAvailable) {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                } else {
                    vm.showSnack("O modelo carregado não suporta imagens. Baixe e carregue um modelo VL com mmproj.")
                }
            },
            holdToTalkEnabled = speechAvailable && (vm.loadedModel != null),
            listening = listening,
            onHoldStart = startListening,
            onHoldEnd = stopListening,
            onSend = {
                if (listening) {
                    listening = false
                    runCatching { recognizer.cancel() } // discard, we keep current input
                }
                val t = input.trim()
                if (t.isNotEmpty() || pendingImagePath != null) {
                    vm.send(t, pendingImagePath)
                    input = ""
                    pendingImagePath = null
                }
            },
            onStop = { vm.stop() },
        )
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------
@Composable
private fun ChatTopBar(
    vm: AppViewModel,
    menuOpen: Boolean,
    onMenuOpen: (Boolean) -> Unit,
    onGoToModels: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val loaded = vm.loadedModel
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(
                Icons.Filled.Menu,
                contentDescription = "Abrir menu",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "ForgeAI",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                loaded?.displayName ?: if (vm.models.isEmpty()) "nenhum modelo baixado" else "nenhum modelo carregado",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        // model selector chip
        Box {
            Chip(
                label = if (loaded != null) "on" else "off",
                icon = Icons.Filled.Bolt,
                selected = loaded != null,
            ) {
                onMenuOpen(!menuOpen)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpen(false) }) {
                vm.models.forEach { m ->
                    val isCurrent = m.isLoaded
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    m.displayName,
                                    maxLines = 1,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "${m.displayParams} · ${m.displayQuant} · ${m.sizeLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingIcon = if (isCurrent) {
                            {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "modelo carregado",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else null,
                        onClick = {
                            onMenuOpen(false)
                            if (m.isLoaded) vm.unloadModel() else vm.loadModel(m.path)
                        },
                    )
                }
                if (vm.models.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Nenhum modelo — toque para baixar") },
                        onClick = {
                            onMenuOpen(false)
                            onGoToModels()
                        },
                    )
                }
            }
        }

        // badge de ferramentas (martelo): só quando o modelo suporta tools
        if (vm.modelSupportsTools) {
            Spacer(Modifier.width(6.dp))
            ToolBadge(enabled = vm.settings.webAccess)
        }
        // badge de visão (olho): só quando o modelo tem encoder de imagens
        if (vm.modelSupportsVision) {
            Spacer(Modifier.width(6.dp))
            VisionBadge()
        }
    }
}

@Composable
private fun LoadingBar(progress: Float) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Carregando modelo…",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ModelBar(vm: AppViewModel, onGoToSettings: () -> Unit) {
    val m = vm.loadedModel ?: return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 6.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill("ativo", Color(0xFF4ADE80))
            if (vm.modelSupportsTools) {
                Spacer(Modifier.width(8.dp))
                WebPill(enabled = vm.settings.webAccess)
            }
            if (vm.modelSupportsVision) {
                Spacer(Modifier.width(8.dp))
                VisionPill()
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    m.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    "${m.displayParams} · ${m.displayQuant} · ctx ${vm.modelSettings.nCtx}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onGoToSettings) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Configurações do modelo",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------
@Composable
private fun EmptyChat(onGoToModels: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(86.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Brush.linearGradient(BrandGradient2)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "IA local, 100% no seu celular",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Seus dados nunca saem do dispositivo. Baixe um modelo GGUF do HuggingFace e comece a conversar — mesmo offline.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Privado", icon = Icons.Filled.PrivacyTip)
                    Chip("Offline", icon = Icons.Outlined.CloudOff)
                    Chip("Grátis", icon = Icons.Filled.AutoAwesome)
                }
                Spacer(Modifier.height(20.dp))
                Surface(
                    onClick = onGoToModels,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Row(
                        Modifier.padding(horizontal = 22.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Baixar modelo",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Messages
// ---------------------------------------------------------------------------
@Composable
private fun MessageBubble(msg: ChatMessage, isLast: Boolean, streamOpenThink: Boolean) {
    if (msg.role == ChatRole.TOOL) {
        ToolResultChip(msg)
        return
    }
    val isUser = msg.role == ChatRole.USER
    // chamada de ferramenta já concluída: mostra compacta em vez do JSON bruto
    val toolCall = if (!isUser && !msg.streaming) ToolSupport.parseToolCall(msg.content) else null
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            if (isUser) {
                Surface(
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    color = Color.Transparent,
                ) {
                    Column(
                        Modifier
                            .background(Brush.linearGradient(UserBubbleGradient), RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        msg.imagePath?.let { path ->
                            ImageAttachment(path)
                            if (msg.content.isNotBlank()) Spacer(Modifier.height(6.dp))
                        }
                        if (msg.content.isNotBlank()) {
                            Text(
                                msg.content,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            } else if (toolCall != null) {
                ToolCallBubble(toolCall, msg.info)
            } else {
                Surface(
                    shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
                    color = if (msg.error) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (msg.streaming && msg.content.isEmpty()) {
                            TypingIndicator()
                        } else {
                            MarkdownContent(
                                text = msg.content,
                                textColor = if (msg.error) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                                streaming = msg.streaming,
                                showCursor = isLast,
                                streamOpenThink = streamOpenThink,
                            )
                        }
                        msg.info?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Balão compacto para uma chamada de ferramenta (em vez do JSON bruto). */
@Composable
private fun ToolCallBubble(call: ToolSupport.ToolCall, info: String?) {
    Surface(
        shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (call.name == ToolSupport.SEARCH_NAME) Icons.Filled.Search else Icons.Filled.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    call.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (call.primaryValue.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    call.primaryValue,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
            info?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

/** Chip centralizado com o resumo do resultado da tool (conteúdo fica no histórico). */
@Composable
private fun ToolResultChip(msg: ChatMessage) {
    val error = msg.info?.startsWith("❌") == true
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            shape = RoundedCornerShape(50),
            color = if (error) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    msg.info ?: "resultado da ferramenta",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Balão compacto para uma chamada de ferramenta quebrada/incompleta (nunca o texto cru). */
@Composable
private fun ToolAttemptBubble(info: String) {
    Surface(
        shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                info,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Badge de martelo na barra superior: modelo com acesso a ferramentas. */
@Composable
private fun ToolBadge(enabled: Boolean) {
    val bg = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Build,
            contentDescription = if (enabled) "Web access ativo"
            else "Modelo com suporte a ferramentas — web access desligado",
            tint = fg,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Pill na barra do modelo: estado do web access (ativo/inativo). */
@Composable
private fun WebPill(enabled: Boolean) {
    val fg = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(fg.copy(alpha = 0.13f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Filled.Build, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        Text(
            if (enabled) "web" else "web off",
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Badge de olho na barra superior: modelo com suporte a visão (mmproj). */
@Composable
private fun VisionBadge() {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.20f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Visibility,
            contentDescription = "Modelo com suporte a imagens (visão)",
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Pill na barra do modelo: indica modelo com visão carregado. */
@Composable
private fun VisionPill() {
    val fg = MaterialTheme.colorScheme.tertiary
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(fg.copy(alpha = 0.13f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Filled.Visibility, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        Text(
            "visão",
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------------
// Input bar — anexo de imagem (botão) + ditado por voz (segurar o campo)
// ---------------------------------------------------------------------------
@Composable
private fun ChatInputBar(
    input: String,
    onInput: (String) -> Unit,
    isGenerating: Boolean,
    canSend: Boolean,
    visionAvailable: Boolean,
    imagePath: String?,
    onRemoveImage: () -> Unit,
    onAttach: () -> Unit,
    holdToTalkEnabled: Boolean,
    listening: Boolean,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(bottom = 12.dp)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // preview da imagem anexada
        if (imagePath != null) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    ImageAttachment(imagePath, maxSize = 56.dp)
                    IconButton(
                        onClick = onRemoveImage,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remover imagem",
                            tint = Color.White,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Imagem anexada — envie para o modelo ver",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // indicador de ditado por voz (segurando o campo)
        if (listening) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                MicPulse()
                Spacer(Modifier.width(8.dp))
                Text(
                    "Ouvindo… solte para enviar",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Surface(
            color = if (listening) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(24.dp),
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = onInput,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (holdToTalkEnabled) Modifier.holdToTalk(holdToTalkEnabled, onHoldStart, onHoldEnd)
                            else Modifier
                        ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { onSend() },
                    ),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (input.isEmpty()) {
                                Text(
                                    if (listening) "Fale agora…"
                                    else if (holdToTalkEnabled) "Mensagem… (segure aqui para falar)"
                                    else "Mensagem para o modelo…",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                // botão de anexo (substituiu o botão do microfone)
                IconButton(
                    onClick = onAttach,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = "Anexar imagem",
                        tint = if (visionAvailable) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                if (isGenerating) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Parar",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                } else {
                    val sendable = canSend && (input.isNotBlank() || imagePath != null)
                    IconButton(
                        onClick = onSend,
                        enabled = sendable,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                if (sendable) Brush.linearGradient(BrandGradient)
                                else SolidColor(MaterialTheme.colorScheme.surfaceContainerHigh)
                            ),
                    ) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = "Enviar",
                            tint = if (sendable) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pressionar e segurar o campo de texto = começa a ditar por voz;
 * soltar = para. Toques rápidos (<400ms) não são consumidos e o campo
 * se comporta normalmente (cursor, seleção, teclado).
 *
 * O long-press roda numa corrotina separada: o gesto principal fica
 * esperando o dedo subir SEM nunca ser cancelado (com withTimeout o
 * waitForUpOrCancellation cancelado fazia o onStop disparar junto do
 * onStart, parando o reconhecimento na mesma hora).
 */
private fun Modifier.holdToTalk(enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit): Modifier =
    if (!enabled) this
    else pointerInput(enabled) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // se o dedo subir antes de 400ms -> toque normal (nada acontece)
            val up = withTimeoutOrNull(400) { waitForUpOrCancellation() }
            if (up == null) {
                // segurou: começa a ditar e espera o dedo subir (sem timeout
                // cancelando o waitForUpOrCancellation duas vezes, o que fazia
                // o onStop disparar junto do onStart em alguns cenários)
                down.consume()
                onStart()
                withTimeoutOrNull(60_000) { waitForUpOrCancellation() }
                onStop()
            }
        }
    }

/** Pontinho pulsante enquanto o ditado está ativo. */
@Composable
private fun MicPulse() {
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "micPulse",
    )
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.error.copy(alpha = pulse)),
    )
}

// helper to collect StateFlow as state (avoids lifecycle dependency)

/**
 * Miniatura de uma imagem anexada, decodificada com amostragem para caber na
 * tela sem estourar a memória (imagens de 12MP+ da galeria).
 */
@Composable
private fun ImageAttachment(path: String, maxSize: Dp = 160.dp) {
    val bmp = remember(path) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 900f)
                .coerceAtLeast(1f)
                .toInt()
                .coerceAtLeast(1)
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Imagem anexada",
            modifier = Modifier
                .widthIn(max = maxSize)
                .heightIn(max = maxSize)
                .clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Fit,
        )
    } else {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

