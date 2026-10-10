package app.kairo.android.ui.family

import app.kairo.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kairo.android.ui.components.MemberAvatar
import app.kairo.android.ui.components.SectionHeader
import app.kairo.android.ui.theme.Kairo
import app.kairo.core.model.FamilyInfo
import app.kairo.core.model.Notice
import app.kairo.core.model.NoticesResponse
import app.kairo.android.ui.common.relativeLabel
import app.kairo.core.util.toInstantOrNull

/**
 * Compact Today card with the latest unread family notices. It is hidden if nobody else is in the
 * family; for the admin it shows a line to invite someone.
 */
@Composable
fun FamilyHomeCard(family: FamilyInfo?, notices: NoticesResponse?, onOpen: () -> Unit, onNotice: (Notice) -> Unit, modifier: Modifier = Modifier) {
    if (family == null) return
    if (family.others.isEmpty()) {
        if (!family.isAdmin) return
        Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(Modifier.padding(start = 14.dp, end = 4.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Groups, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.family_home_invite_prompt), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpen) { Text(stringResource(R.string.family_home_invite_action)) }
            }
        }
        return
    }
    val unread = notices?.notices.orEmpty().filter { it.readAt == null }.take(3)
    Column(modifier) {
        SectionHeader(stringResource(R.string.nav_family), action = stringResource(R.string.family_see_all), onAction = onOpen)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column {
                if (unread.isEmpty()) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AvatarStack(family)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.family_all_caught_up, family.household.name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    unread.forEachIndexed { i, n ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 58.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Surface(onClick = { onNotice(n) }, color = androidx.compose.ui.graphics.Color.Transparent) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                val actor = family.member(n.actorId)
                                if (actor != null && n.kind == "message" && n.entityType == null) MemberAvatar(actor.displayName, size = 32.dp)
                                else Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                                    Icon(noticeIcon(n), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(12.dp))
                                // The title uses the full width; the time goes at the end of the second line
                                Column(Modifier.weight(1f)) {
                                    Text(n.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (n.body.isNotBlank()) {
                                            Text(n.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                            Text(" \u00B7 ", style = MaterialTheme.typography.bodySmall, color = Kairo.colors.faint)
                                        }
                                        Text(n.createdAt.toInstantOrNull()?.relativeLabel() ?: "", style = MaterialTheme.typography.labelSmall, color = Kairo.colors.faint, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                    val more = (notices?.unread ?: 0) - unread.size
                    if (more > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TextButton(onClick = onOpen, modifier = Modifier.padding(horizontal = 4.dp)) { Text(pluralStringResource(R.plurals.family_more_notices, more, more)) }
                    }
                }
            }
        }
    }
}

/** Overlapping member avatars (at most four). */
@Composable
private fun AvatarStack(family: FamilyInfo) {
    val shown = family.members.take(4)
    Box(Modifier.width((28 + (shown.size - 1) * 18).dp)) {
        shown.forEachIndexed { i, m ->
            Box(Modifier.offset(x = (i * 18).dp).size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(2.dp)) {
                MemberAvatar(m.displayName, size = 24.dp)
            }
        }
    }
}
