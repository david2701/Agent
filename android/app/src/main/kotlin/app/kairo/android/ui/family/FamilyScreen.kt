package app.kairo.android.ui.family

import app.kairo.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import app.kairo.android.AppGraph
import app.kairo.android.family.FamilyStore
import app.kairo.android.sync.ServerEvents
import app.kairo.android.ui.Destination
import app.kairo.android.ui.common.ResourceViewModel
import app.kairo.android.ui.common.friendly
import app.kairo.android.ui.components.EmptyState
import app.kairo.android.ui.components.ErrorBanner
import app.kairo.android.ui.components.MemberAvatar
import app.kairo.android.ui.components.Skeleton
import app.kairo.android.ui.components.SkeletonList
import app.kairo.android.ui.components.entityIcon
import app.kairo.android.ui.tasks.Labeled
import app.kairo.android.ui.theme.Kairo
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.core.model.FamilyInfo
import app.kairo.core.model.InviteCreated
import app.kairo.core.model.Invitation
import app.kairo.core.model.Member
import app.kairo.core.model.Notice
import app.kairo.core.model.NoticesResponse
import app.kairo.core.session.Session
import app.kairo.android.ui.common.dayLabel
import app.kairo.android.ui.common.relativeLabel
import app.kairo.core.util.relativeDay
import app.kairo.core.util.toInstantOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

data class FamilyData(val info: FamilyInfo, val invites: List<Invitation>, val notices: NoticesResponse)

/** Newly created code to share: a new invitation or the access code of [memberName]. */
data class CreatedCode(val created: InviteCreated, val memberName: String?)

class FamilyViewModel(graph: AppGraph) : ResourceViewModel<FamilyData>(graph, setOf("family", "notice")) {
    val created = MutableStateFlow<CreatedCode?>(null)
    val sending = MutableStateFlow(false)

    override suspend fun load(): FamilyData {
        val info = graph.api.family().also { graph.family.set(it) }
        val invites = if (info.isAdmin) runCatching { graph.api.invites() }.getOrDefault(emptyList()) else emptyList()
        return FamilyData(info, invites, graph.api.notices(limit = 50))
    }

    init { refresh() }

    fun rename(name: String) = mutate(str(R.string.fam_name_saved)) { graph.api.renameFamily(name) }

    fun invite(name: String, role: String) = viewModelScope.launch {
        runCatching { graph.api.createInvite(name, role) }
            .onSuccess { created.value = CreatedCode(it, null); refresh(silent = true) }
            .onFailure { say(it.friendly()) }
    }

    fun accessCode(m: Member) = viewModelScope.launch {
        runCatching { graph.api.accessCode(m.id) }
            .onSuccess { created.value = CreatedCode(it, m.displayName); refresh(silent = true) }
            .onFailure { say(it.friendly()) }
    }

    fun revoke(inv: Invitation) = mutate(str(R.string.fam_invite_revoked)) { graph.api.revokeInvite(inv.id) }

    fun setRole(m: Member, admin: Boolean) {
        val name = FamilyStore.firstNameOf(m.displayName)
        mutate(if (admin) str(R.string.fam_now_admin, name) else str(R.string.fam_no_longer_admin, name)) { graph.api.setMemberRole(m.id, if (admin) "admin" else "member") }
    }

    fun remove(m: Member) = mutate(str(R.string.fam_removed, FamilyStore.firstNameOf(m.displayName))) { graph.api.removeMember(m.id) }

    fun send(text: String, to: List<String>?, onSent: () -> Unit) = viewModelScope.launch {
        sending.value = true
        runCatching { graph.api.familyMessage(text, to) }
            .onSuccess { n ->
                val single = to?.singleOrNull() ?: state.value.data?.info?.others?.singleOrNull()?.id
                val who = if (n == 1) graph.family.firstName(single) else null
                say(if (who != null) str(R.string.fam_notice_sent_one, who) else graph.app.resources.getQuantityString(R.plurals.fam_notice_sent_many, n, n))
                onSent()
            }
            .onFailure { say(it.friendly()) }
        sending.value = false
    }

    fun markAllRead() {
        val now = Instant.now().toString()
        updateData { d -> d.copy(notices = NoticesResponse(d.notices.notices.map { it.copy(readAt = it.readAt ?: now) }, 0)) }
        mutate { graph.api.markNoticesRead() }
    }

    fun markRead(n: Notice) {
        if (n.readAt != null) return
        val now = Instant.now().toString()
        updateData { d -> d.copy(notices = NoticesResponse(d.notices.notices.map { if (it.id == n.id) it.copy(readAt = now) else it }, (d.notices.unread - 1).coerceAtLeast(0))) }
        mutate { graph.api.markNoticesRead(listOf(n.id)) }
    }
}

@Composable
fun FamilyScreen(graph: AppGraph, session: Session, navigate: (Destination) -> Unit) {
    val vm: FamilyViewModel = viewModel { FamilyViewModel(graph) }
    val state by vm.state.collectAsState()
    val created by vm.created.collectAsState()
    val sending by vm.sending.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val wide = currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)

    var inviting by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Member?>(null) }

    val data = state.data
    val info = data?.info
    val me = info?.me ?: session.user.id
    val actions = MemberActions(
        onRole = vm::setRole,
        onAccessCode = { vm.accessCode(it) },
        onRemove = { removing = it },
    )
    val openNotice: (Notice) -> Unit = { n ->
        vm.markRead(n)
        Destination.fromRoute(ServerEvents.destinationFor(n.entityType))?.takeIf { it != Destination.Family }?.let(navigate)
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { inner ->
        val padding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp)
        if (wide) {
            // Unfolded foldable or tablet: the family on the left, notices and news on the right
            Row(Modifier.fillMaxSize().padding(inner).statusBarsPadding()) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    header(state.error, vm)
                    if (info == null) loading(state.loading) else {
                        householdSection(info, me, data.invites, actions, onRename = { renaming = true }, onInvite = { inviting = true }, onRevoke = vm::revoke)
                        privacyNote()
                    }
                }
                // Starts level with the content on the left (below the title)
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 56.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (info != null) {
                        composerSection(info, sending, vm)
                        noticesSection(data.notices, info, vm, openNotice)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(inner).statusBarsPadding(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                header(state.error, vm)
                if (info == null) loading(state.loading) else {
                    householdSection(info, me, data.invites, actions, onRename = { renaming = true }, onInvite = { inviting = true }, onRevoke = vm::revoke)
                    composerSection(info, sending, vm)
                    noticesSection(data.notices, info, vm, openNotice)
                    privacyNote()
                }
            }
        }
    }

    if (inviting) InviteSheet(onDismiss = { inviting = false }) { name, role -> vm.invite(name, role); inviting = false }
    created?.let { c -> CodeSheet(c, info?.household?.name ?: stringResource(R.string.notes_your_family)) { vm.created.value = null } }
    if (renaming && info != null) {
        var name by rememberSaveable { mutableStateOf(info.household.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.fam_rename_title)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { vm.rename(name.trim()); renaming = false } }, enabled = name.isNotBlank()) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    removing?.let { m ->
        val first = FamilyStore.firstNameOf(m.displayName)
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.fam_remove_title, first)) },
            text = {
                Text(stringResource(R.string.fam_remove_body))
            },
            confirmButton = {
                TextButton(onClick = { vm.remove(m); removing = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.common_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private class MemberActions(val onRole: (Member, Boolean) -> Unit, val onAccessCode: (Member) -> Unit, val onRemove: (Member) -> Unit)

private fun LazyListScope.header(error: String?, vm: FamilyViewModel) {
    item { Text(stringResource(R.string.nav_family), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp)) }
    error?.let { item { ErrorBanner(it, onRetry = { vm.refresh() }) } }
}

private fun LazyListScope.loading(loading: Boolean) {
    if (loading) {
        item { Skeleton(height = 168.dp) }
        item { SkeletonList(3) }
    }
}

@Composable
private fun SectionTitle(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** Household, members and pending invitations (these as faint rows, below the members). */
private fun LazyListScope.householdSection(
    info: FamilyInfo,
    me: String,
    invites: List<Invitation>,
    actions: MemberActions,
    onRename: () -> Unit,
    onInvite: () -> Unit,
    onRevoke: (Invitation) -> Unit,
) {
    item(key = "household") {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(info.household.name, style = MaterialTheme.typography.titleLarge)
                    val count = pluralStringResource(R.plurals.family_members, info.members.size, info.members.size)
                    Text(if (info.isAdmin) count + " \u00B7 " + stringResource(R.string.family_admin_note) else count, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (info.isAdmin) IconButton(onClick = onRename) { Icon(Icons.Rounded.Edit, stringResource(R.string.fam_rename_cd)) }
            }
            info.members.forEach { m ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MemberRow(m, isMe = m.id == me, canManage = info.isAdmin && m.id != me, actions)
            }
            if (info.isAdmin) {
                val now = Instant.now()
                invites.filter { it.usedAt == null && (it.expiresAt.toInstantOrNull()?.isAfter(now) ?: true) }.forEach { inv ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InviteRow(inv, info, onRevoke)
                }
            }
            if (info.others.isEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    if (info.isAdmin) stringResource(R.string.fam_empty_admin)
                    else stringResource(R.string.fam_empty_member),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (info.isAdmin) {
                Button(
                    onClick = onInvite, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                ) {
                    Icon(Icons.Rounded.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.fam_invite_button))
                }
            }
        }
    }
}

/** Unused invitation: shown as a member to come, with its expiry. */
@Composable
private fun InviteRow(inv: Invitation, info: FamilyInfo, onRevoke: (Invitation) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(if (inv.kind == "reset") Icons.Rounded.Key else Icons.Rounded.Schedule, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val who = if (inv.kind == "reset") stringResource(R.string.fam_access_code_of, info.firstNameOfMember(inv.targetUserId) ?: stringResource(R.string.fam_member_fallback)) else inv.name.ifBlank { stringResource(R.string.fam_unnamed_invite) }
            Text(who, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val what = if (inv.kind == "reset") stringResource(R.string.fam_unused) else stringResource(if (inv.role == "admin") R.string.fam_pending_admin else R.string.fam_pending)
            Text(stringResource(R.string.fam_status_expires, what, expiresLabel(inv.expiresAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { onRevoke(inv) }) { Text(stringResource(R.string.fam_revoke)) }
    }
}

private fun FamilyInfo.firstNameOfMember(id: String?): String? = member(id)?.displayName?.let(FamilyStore::firstNameOf)

@Composable
private fun MemberRow(m: Member, isMe: Boolean, canManage: Boolean, actions: MemberActions) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        MemberAvatar(m.displayName, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(m.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (isMe) Tag(stringResource(R.string.fam_tag_you), strong = true)
                Tag(if (m.role == "admin") stringResource(R.string.fam_tag_admin) else stringResource(R.string.fam_tag_member))
            }
            Text(m.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (canManage) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.fam_options_cd, m.displayName)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (m.role == "admin") stringResource(R.string.fam_remove_as_admin) else stringResource(R.string.fam_make_admin)) },
                        onClick = { menu = false; actions.onRole(m, m.role != "admin") },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.fam_access_code)) }, onClick = { menu = false; actions.onAccessCode(m) })
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.fam_remove_from), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; actions.onRemove(m) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, strong: Boolean = false) {
    Surface(color = if (strong) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest, shape = CircleShape) {
        Text(
            text, style = MaterialTheme.typography.labelSmall,
            color = if (strong) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun expiresLabel(iso: String): String {
    val date = iso.toInstantOrNull()?.atZone(ZoneId.systemDefault())?.toLocalDate() ?: return stringResource(R.string.fam_expires_valid)
    val day = date.dayLabel()
    return if (date.relativeDay() != null) stringResource(R.string.fam_expires_relative, day) else stringResource(R.string.fam_expires_on, day)
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.composerSection(info: FamilyInfo, sending: Boolean, vm: FamilyViewModel) {
    if (info.others.isEmpty()) return
    item { SectionTitle(stringResource(R.string.fam_notify_title)) }
    item(key = "composer") {
        var text by rememberSaveable { mutableStateOf("") }
        var to by rememberSaveable { mutableStateOf(emptyList<String>()) }
        val others = info.others
        fun send() { if (text.isNotBlank() && !sending) vm.send(text.trim(), to.takeIf { it.isNotEmpty() }) { text = ""; to = emptyList() } }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = text, onValueChange = { text = it.take(500) },
                    placeholder = { Text(stringResource(R.string.fam_composer_placeholder)) },
                    modifier = Modifier.weight(1f), shape = CircleShape, maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = ::send, enabled = text.isNotBlank() && !sending, modifier = Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                ) { Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.fam_send_cd)) }
            }
            if (others.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = to.isEmpty(), onClick = { to = emptyList() }, label = { Text(stringResource(R.string.fam_everyone)) })
                    others.forEach { m ->
                        FilterChip(
                            selected = m.id in to,
                            onClick = { to = if (m.id in to) to - m.id else to + m.id },
                            label = { Text(FamilyStore.firstNameOf(m.displayName)) },
                        )
                    }
                }
            } else {
                Text(stringResource(R.string.fam_arrives_instantly, FamilyStore.firstNameOf(others.first().displayName)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun LazyListScope.noticesSection(notices: NoticesResponse, info: FamilyInfo, vm: FamilyViewModel, onOpen: (Notice) -> Unit) {
    item { SectionTitle(stringResource(R.string.fam_news), action = if (notices.unread > 0) stringResource(R.string.fam_mark_all) else null, onAction = vm::markAllRead) }
    if (notices.notices.isEmpty()) {
        item { EmptyState(Icons.Rounded.NotificationsNone, stringResource(R.string.fam_news_empty_title), stringResource(R.string.fam_news_empty_body)) }
        return
    }
    items(notices.notices, key = { "n" + it.id }) { n -> NoticeRow(n, info, onOpen) }
}

/** Icon of a notice according to its kind. */
fun noticeIcon(n: Notice) = when (n.kind) {
    "message" -> if (n.entityType == "family") Icons.Rounded.PersonAdd else Icons.Rounded.ChatBubbleOutline
    "assigned" -> Icons.Rounded.TaskAlt
    "reminder" -> Icons.Rounded.NotificationsActive
    "test" -> Icons.Rounded.Bolt
    else -> n.entityType?.let(::entityIcon) ?: Icons.Rounded.Groups
}

@Composable
private fun NoticeRow(n: Notice, info: FamilyInfo, onOpen: (Notice) -> Unit) {
    val unread = n.readAt == null
    Surface(
        onClick = { onOpen(n) },
        color = if (unread) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
            val actor = info.member(n.actorId)
            if (actor != null && n.kind == "message" && n.entityType == null) MemberAvatar(actor.displayName, size = 36.dp)
            else Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(noticeIcon(n), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(n.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (n.body.isNotBlank()) Text(n.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(n.createdAt.toInstantOrNull()?.relativeLabel() ?: "", style = MaterialTheme.typography.labelSmall, color = Kairo.colors.faint)
            }
            if (unread) Box(Modifier.padding(start = 8.dp, top = 6.dp).size(8.dp).clip(CircleShape).background(Kairo.colors.accentFill))
        }
    }
}

private fun LazyListScope.privacyNote() {
    item {
        Text(
            stringResource(R.string.fam_privacy),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun InviteSheet(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf("member") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.fam_invite_title), style = MaterialTheme.typography.titleLarge)
            Labeled(stringResource(R.string.lists_f_name)) { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("invite-name"), singleLine = true, placeholder = { Text(stringResource(R.string.fam_name_hint)) }, shape = MaterialTheme.shapes.small) }
            Labeled(stringResource(R.string.fam_f_role)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(role == "member", { role = "member" }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.fam_tag_member)) }
                    SegmentedButton(role == "admin", { role = "admin" }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.fam_role_admin)) }
                }
                Text(
                    if (role == "admin") stringResource(R.string.fam_role_admin_note)
                    else stringResource(R.string.fam_role_member_note),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(stringResource(R.string.fam_one_time_code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    onClick = { onCreate(name.trim(), role) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
                ) { Text(stringResource(R.string.fam_create_invite)) }
            }
        }
    }
}

@Composable
private fun CodeSheet(c: CreatedCode, household: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val inv = c.created.invitation
    val reset = c.memberName != null
    val first = c.memberName?.let(FamilyStore::firstNameOf)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (reset) stringResource(R.string.fam_code_title_reset, first ?: "") else stringResource(R.string.fam_code_title_invite, inv.name.ifBlank { stringResource(R.string.fam_new_member) }), style = MaterialTheme.typography.titleLarge)
            Text(
                if (reset) stringResource(R.string.fam_code_body_reset, first ?: "", expiresLabel(inv.expiresAt))
                else stringResource(R.string.fam_code_body_invite, household, expiresLabel(inv.expiresAt)),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Text(
                    c.created.code, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = MonoStyle.fontFamily, letterSpacing = 4.sp),
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
            Button(
                onClick = { share(context, c, household) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
            ) {
                Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(8.dp))
                Text(if (reset) stringResource(R.string.fam_share_code) else stringResource(R.string.fam_share_invite))
            }
            OutlinedButton(onClick = { copy(context, c.created.code) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.fam_copy_code))
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) }
            }
        }
    }
}

/** Android share sheet with the link and the code. */
private fun share(context: Context, c: CreatedCode, household: String) {
    val code = c.created.code
    val text = if (c.memberName != null) {
        context.getString(R.string.fam_share_text_reset, code, c.created.link)
    } else {
        context.getString(R.string.fam_share_text_invite, household, c.created.link, code)
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.fam_share_subject, household))
    runCatching { context.startActivity(Intent.createChooser(send, context.getString(if (c.memberName != null) R.string.fam_share_code else R.string.fam_share_invite)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun copy(context: Context, code: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.fam_clip_label), code))
}
