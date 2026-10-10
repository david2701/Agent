package app.kairo.android.ui.chat

import app.kairo.android.R
import androidx.compose.ui.res.stringResource
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kairo.android.AppGraph
import app.kairo.android.ui.Destination
import app.kairo.android.ui.common.friendly
import app.kairo.android.ui.components.ActionCard
import app.kairo.android.ui.components.EmptyState
import app.kairo.android.ui.components.ErrorBanner
import app.kairo.android.ui.components.KairoOrb
import app.kairo.android.ui.components.MarkdownText
import app.kairo.android.ui.components.OrbState
import app.kairo.android.ui.components.SkeletonList
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.android.voice.VoicePhase
import app.kairo.core.model.AgentEvent
import app.kairo.core.model.Conversation
import app.kairo.core.model.EntityRef
import app.kairo.core.model.StoredMessage
import app.kairo.android.ui.common.relativeLabel
import app.kairo.core.util.toInstantOrNull
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Turn in progress, shown while the stream arrives. */
data class LiveTurn(
    val userText: String,
    val partial: String = "",
    val working: String? = null,
    val actions: List<AgentEvent.ToolEnd> = emptyList(),
    val images: List<String> = emptyList(),
    val error: String? = null,
)

data class ChatUi(
    val conversations: List<Conversation> = emptyList(),
    val loadingList: Boolean = true,
    val current: String? = null,
    val messages: List<StoredMessage> = emptyList(),
    val loadingMessages: Boolean = false,
    val live: LiveTurn? = null,
    val error: String? = null,
)

class ChatViewModel(private val graph: AppGraph) : ViewModel() {
    val ui = MutableStateFlow(ChatUi())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    init {
        loadConversations()
        // If voice was used in this conversation, reload when it finishes
        viewModelScope.launch {
            var last = VoicePhase.Off
            graph.voice.state.collect { v ->
                val cur = ui.value.current
                if (last != VoicePhase.Off && last != VoicePhase.Armed && (v.phase == VoicePhase.Off || v.phase == VoicePhase.Armed) && cur != null && v.conversationId == cur) {
                    open(cur)
                    loadConversations()
                }
                last = v.phase
            }
        }
        viewModelScope.launch { graph.realtime.changes.filter { it == "conversation" }.collect { loadConversations() } }
    }

    fun loadConversations() = viewModelScope.launch {
        runCatching { graph.api.conversations() }
            .onSuccess { list -> ui.update { it.copy(conversations = list, loadingList = false, error = null) } }
            .onFailure { e -> ui.update { it.copy(loadingList = false, error = e.friendly(graph.app)) } }
    }

    fun open(id: String?) {
        ui.update { it.copy(current = id, messages = if (id == it.current) it.messages else emptyList(), loadingMessages = id != null, live = null) }
        if (id == null) return
        viewModelScope.launch {
            runCatching { graph.api.messages(id) }
                .onSuccess { m -> ui.update { if (it.current == id) it.copy(messages = m, loadingMessages = false) else it } }
                .onFailure { e -> ui.update { it.copy(loadingMessages = false, error = e.friendly(graph.app)) } }
        }
    }

    fun delete(id: String) = viewModelScope.launch {
        runCatching { graph.api.deleteConversation(id) }.onSuccess {
            if (ui.value.current == id) open(null)
            loadConversations()
        }.onFailure { messages.tryEmit(it.friendly(graph.app)) }
    }

    fun send(text: String) {
        val conv = ui.value.current
        ui.update { it.copy(live = LiveTurn(text)) }
        viewModelScope.launch {
            var spoken: String? = null
            try {
                graph.api.chat(text, conv).collect { ev ->
                    when (ev) {
                        is AgentEvent.Start -> ui.update { it.copy(current = ev.conversationId) }
                        is AgentEvent.Delta -> ui.update { s -> s.copy(live = s.live?.copy(partial = s.live.partial + ev.text)) }
                        is AgentEvent.ToolStart -> ui.update { s -> s.copy(live = s.live?.copy(working = ev.label)) }
                        is AgentEvent.ToolEnd -> ui.update { s -> s.copy(live = s.live?.copy(working = null, actions = s.live.actions + ev)) }
                        is AgentEvent.Attachment -> ui.update { s -> s.copy(live = s.live?.copy(images = s.live.images + ev.file.url)) }
                        is AgentEvent.DeviceAction -> graph.deviceActions.handle(ev.action)
                        is AgentEvent.Done -> spoken = ev.spoken
                        is AgentEvent.Error -> ui.update { s -> s.copy(live = s.live?.copy(error = ev.message, working = null)) }
                        is AgentEvent.Transcript -> Unit
                    }
                }
            } catch (e: Exception) {
                ui.update { s -> s.copy(live = s.live?.copy(error = e.friendly(graph.app))) }
            }
            val id = ui.value.current
            if (ui.value.live?.error == null && id != null) {
                runCatching { graph.api.messages(id) }.onSuccess { m -> ui.update { it.copy(messages = m, live = null) } }
            }
            loadConversations()
            if (spoken != null && graph.prefs.current().speakChatReplies) graph.voice.say(spoken!!)
        }
    }

    fun talk() = graph.voice.talk(ui.value.current)
}

@Composable
fun ChatScreen(graph: AppGraph, navigate: (Destination) -> Unit) {
    val vm: ChatViewModel = viewModel { ChatViewModel(graph) }
    val ui by vm.ui.collectAsState()
    val navigator = rememberListDetailPaneScaffoldNavigator<String>(scaffoldDirective = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfo()))
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val baseUrl by graph.session.session.collectAsState(initial = null)

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(navigator.canNavigateBack()) { scope.launch { navigator.navigateBack() } }

    fun openConversation(id: String?) {
        vm.open(id)
        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id ?: "new") }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { inner ->
        ListDetailPaneScaffold(
            modifier = Modifier.padding(inner),
            directive = navigator.scaffoldDirective,
            value = navigator.scaffoldValue,
            listPane = {
                AnimatedPane {
                    ConversationList(ui, onOpen = ::openConversation, onDelete = vm::delete, onRetry = { vm.loadConversations() })
                }
            },
            detailPane = {
                AnimatedPane {
                    Thread(
                        graph = graph,
                        ui = ui,
                        baseUrl = baseUrl?.serverUrl ?: "",
                        showBack = navigator.canNavigateBack(),
                        onBack = { scope.launch { navigator.navigateBack() } },
                        onSend = vm::send,
                        onTalk = vm::talk,
                        onOpenEntity = { navigate(Destination.forEntity(it.type)) },
                    )
                }
            },
        )
    }
}

@Composable
private fun ConversationList(ui: ChatUi, onOpen: (String?) -> Unit, onDelete: (String) -> Unit, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text(stringResource(R.string.chat_conversations), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)) }
            ui.error?.let { item { ErrorBanner(it, onRetry = onRetry) } }
            if (ui.loadingList) item { SkeletonList(6) }
            else if (ui.conversations.isEmpty()) item {
                EmptyState(Icons.Rounded.ChatBubbleOutline, stringResource(R.string.chat_empty_title), stringResource(R.string.chat_empty_body))
            }
            items(ui.conversations, key = { it.id }) { c ->
                val selected = ui.current == c.id
                Surface(
                    onClick = { onOpen(c.id) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title.ifBlank { stringResource(R.string.chat_untitled) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val preview = c.preview?.takeIf { p -> !p.startsWith(c.title.removeSuffix("...").take(40)) }
                            if (preview != null) Text(
                                preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(c.updatedAt.toInstantOrNull()?.relativeLabel() ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                        IconButton(onClick = { onDelete(c.id) }) { Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.chat_delete_cd), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { onOpen(null) },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text(stringResource(R.string.chat_new)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun Thread(
    graph: AppGraph,
    ui: ChatUi,
    baseUrl: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onTalk: () -> Unit,
    onOpenEntity: (EntityRef) -> Unit,
) {
    val voice by graph.voice.state.collectAsState()
    val listState = rememberLazyListState()
    var text by rememberSaveable { mutableStateOf("") }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) onTalk() }
    val voiceHere = voice.phase != VoicePhase.Off && voice.phase != VoicePhase.Armed && (ui.current == null || voice.conversationId == null || voice.conversationId == ui.current)
    val itemCount = ui.messages.size + (if (ui.live != null) 1 else 0) + (if (voiceHere) 1 else 0)
    LaunchedEffect(itemCount, ui.live?.partial?.length) { if (itemCount > 0) listState.animateScrollToItem(itemCount - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back)) }
            val title = ui.conversations.firstOrNull { it.id == ui.current }?.title ?: stringResource(R.string.chat_new_conversation)
            Text(title.ifBlank { stringResource(R.string.chat_untitled) }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (ui.messages.isEmpty() && ui.live == null && !ui.loadingMessages && !voiceHere) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    KairoOrb(OrbState.Idle, 0f, size = 120.dp)
                    Text(stringResource(R.string.chat_prompt_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.chat_prompt_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ui.loadingMessages) item { SkeletonList(3) }
                items(ui.messages, key = { it.id }) { m -> MessageItem(m, baseUrl, onOpenEntity) }
                ui.live?.let { live -> item(key = "live") { LiveItem(live, baseUrl, onOpenEntity) } }
                if (voiceHere) item(key = "voice") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        KairoOrb(
                            when (voice.phase) { VoicePhase.Listening -> OrbState.Listening; VoicePhase.Speaking -> OrbState.Speaking; else -> OrbState.Thinking },
                            voice.level, size = 48.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(voice.transcript?.let { "\"$it\"" } ?: if (voice.phase == VoicePhase.Listening) stringResource(R.string.home_status_listening) else voice.working ?: stringResource(R.string.home_status_thinking), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        val busy = ui.live != null && ui.live.error == null
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.home_ask_placeholder)) },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                shape = RoundedCornerShape(26.dp),
                maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = {
                    when {
                        text.isNotBlank() && !busy -> { onSend(text.trim()); text = "" }
                        voiceHere -> graph.voice.cancel()
                        graph.voice.hasMicPermission() -> onTalk()
                        else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) {
                Icon(
                    when { text.isNotBlank() -> Icons.AutoMirrored.Rounded.Send; voiceHere -> Icons.Rounded.Stop; else -> Icons.Rounded.Mic },
                    contentDescription = if (text.isNotBlank()) stringResource(R.string.chat_send) else stringResource(R.string.home_talk),
                )
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), modifier = Modifier.widthIn(max = 520.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun MessageItem(m: StoredMessage, baseUrl: String, onOpenEntity: (EntityRef) -> Unit) {
    when (m.role) {
        "user" -> UserBubble(m.content)
        "assistant" -> MarkdownText(m.content, Modifier.widthIn(max = 640.dp))
        "tool" -> {
            val meta = m.meta ?: return
            val summary = (meta["summary"] as? JsonPrimitive)?.content ?: return
            val ok = (meta["ok"] as? JsonPrimitive)?.booleanOrNull ?: true
            val entity = (meta["entity"] as? JsonObject)?.let {
                EntityRef((it["type"] as? JsonPrimitive)?.content ?: "", (it["id"] as? JsonPrimitive)?.content ?: "", (it["title"] as? JsonPrimitive)?.content ?: "")
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionCard(AgentEvent.ToolEnd(m.id, m.toolName ?: "", ok, summary, entity), Modifier.widthIn(max = 520.dp), onOpen = { entity?.let(onOpenEntity) })
                (meta["attachment"] as? JsonObject)?.get("url")?.let { (it as? JsonPrimitive)?.content }?.let { url -> ChatImage(baseUrl + url) }
            }
        }
    }
}

@Composable
private fun LiveItem(live: LiveTurn, baseUrl: String, onOpenEntity: (EntityRef) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        UserBubble(live.userText)
        live.actions.forEach { ev -> ActionCard(ev, Modifier.widthIn(max = 520.dp), onOpen = { ev.entity?.let(onOpenEntity) }) }
        live.images.forEach { ChatImage(baseUrl + it) }
        if (live.partial.isNotBlank()) MarkdownText(live.partial, Modifier.widthIn(max = 640.dp))
        live.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (live.error == null && live.partial.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(32.dp))
                Spacer(Modifier.width(8.dp))
                Text(live.working ?: stringResource(R.string.home_status_thinking), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChatImage(url: String) {
    AsyncImage(
        model = url,
        contentDescription = stringResource(R.string.chat_image),
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(280.dp).clip(RoundedCornerShape(16.dp)),
    )
}
