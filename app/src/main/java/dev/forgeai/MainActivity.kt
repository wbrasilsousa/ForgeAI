package dev.forgeai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.forgeai.ui.AppViewModel
import dev.forgeai.ui.ChatSummary
import dev.forgeai.ui.screens.ChatScreen
import dev.forgeai.ui.screens.ModelsScreen
import dev.forgeai.ui.screens.SettingsScreen
import dev.forgeai.ui.theme.BrandGradient2
import dev.forgeai.ui.theme.ForgeAITheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )

        setContent {
            ForgeAppThemeRoot()
        }
    }
}

@Composable
private fun ForgeAppThemeRoot() {
    val vm: AppViewModel = viewModel()
    ForgeAITheme(
        darkTheme = vm.settings.darkTheme,
        dynamicColor = vm.settings.dynamicColor,
    ) {
        AppScaffold(vm)
    }
}

private enum class Tab(val label: String) {
    CHAT("Chat"),
    MODELS("Modelos"),
    SETTINGS("Ajustes"),
}

@Composable
private fun AppScaffold(vm: AppViewModel) {
    var current by remember { mutableIntStateOf(0) }
    val tabs = listOf(Tab.CHAT, Tab.MODELS, Tab.SETTINGS)
    val icons = listOf(
        Icons.Filled.ChatBubble,
        Icons.Filled.Download,
        Icons.Filled.Settings,
    )
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // immersive fullscreen (per settings)
    val context = LocalContext.current
    val activity = context as? MainActivity
    val immersive = vm.settings.immersive
    val dark = vm.settings.darkTheme
    DisposableEffect(immersive, dark) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // snackbar from VM
    LaunchedEffect(Unit) {
        vm.snack.collect { pair ->
            pair?.let { (_, msg) ->
                snackbarHostState.showSnackbar(msg)
                vm.consumeSnack()
            }
        }
    }

    // gesto de voltar: fecha o drawer → volta ao Chat (home) → comportamento padrão (sair)
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = !drawerState.isOpen && current != 0) {
        current = 0
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                vm = vm,
                currentTab = current,
                onSelectTab = { i ->
                    current = i
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    vm.newChat()
                    current = 0
                    scope.launch { drawerState.close() }
                },
                onOpenChat = { id ->
                    vm.openChat(id)
                    current = 0
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(padding)
            ) {
                when (tabs[current]) {
                    Tab.CHAT -> ChatScreen(
                        vm = vm,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onGoToModels = { current = 1 },
                        onGoToSettings = { current = 2 },
                    )
                    Tab.MODELS -> ModelsScreen(vm)
                    Tab.SETTINGS -> SettingsScreen(vm, onGoToModels = { current = 1 })
                }

                // snackbar no topo do app
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp, start = 16.dp, end = 16.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Drawer
// ---------------------------------------------------------------------------
@Composable
private fun AppDrawer(
    vm: AppViewModel,
    currentTab: Int,
    onSelectTab: (Int) -> Unit,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        // header
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Brush.linearGradient(BrandGradient2)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "ForgeAI",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "IA 100% local",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // new chat
        Surface(
            onClick = onNewChat,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Novo chat",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // navigation
        NavigationDrawerItem(
            label = { Text(tabsLabel(0)) },
            icon = { Icon(iconsFor(0), contentDescription = null) },
            selected = currentTab == 0,
            onClick = { onSelectTab(0) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text(tabsLabel(1)) },
            icon = { Icon(iconsFor(1), contentDescription = null) },
            selected = currentTab == 1,
            onClick = { onSelectTab(1) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text(tabsLabel(2)) },
            icon = { Icon(iconsFor(2), contentDescription = null) },
            selected = currentTab == 2,
            onClick = { onSelectTab(2) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        HorizontalDivider(
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        )

        // history header
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "Histórico",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(4.dp))

        if (vm.chats.isEmpty()) {
            Text(
                "Nenhuma conversa ainda",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(vm.chats, key = { it.id }) { chat ->
                    ChatHistoryRow(
                        chat = chat,
                        selected = chat.id == vm.currentChatId,
                        onClick = { onOpenChat(chat.id) },
                        onDelete = { vm.deleteChat(chat.id) },
                    )
                }
            }
        }
    }
}

private fun tabsLabel(i: Int): String = when (i) {
    1 -> "Modelos"
    2 -> "Ajustes"
    else -> "Chat"
}

@Composable
private fun iconsFor(i: Int) = when (i) {
    1 -> Icons.Filled.Download
    2 -> Icons.Filled.Settings
    else -> Icons.Filled.ChatBubble
}

@Composable
private fun ChatHistoryRow(
    chat: ChatSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                chat.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                relativeTime(chat.updatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Excluir conversa",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

private fun relativeTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    val min = diff / 60_000
    return when {
        diff < 60_000 -> "agora"
        min < 60 -> "$min min"
        min < 1440 -> "${min / 60} h"
        min < 10080 -> "${min / 1440} d"
        else -> SimpleDateFormat("dd/MM", Locale.getDefault()).format(Date(ts))
    }
}
