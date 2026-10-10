package app.kairo.android.ui.components

import app.kairo.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kairo.android.AppGraph
import app.kairo.android.family.FamilyStore
import app.kairo.core.model.FamilyInfo

/** Cached family; null while loading or signed out. */
@Composable
fun rememberFamily(graph: AppGraph): FamilyInfo? = graph.family.info.collectAsState().value

/** There is someone else in the family: only then does sharing or assigning make sense. */
val FamilyInfo?.hasOthers: Boolean get() = this != null && members.any { it.id != me }

/** Who shared a family item: you (or unknown) or another member. */
sealed interface SharedBy {
    data object Family : SharedBy
    data class Member(val firstName: String) : SharedBy
}

/**
 * Who shared something: [SharedBy.Family] if you shared it (or it is unknown whose it is),
 * [SharedBy.Member] if another member shared it; null if it is personal.
 */
fun sharedBy(shared: Boolean, ownerId: String?, family: FamilyInfo?): SharedBy? {
    if (!shared) return null
    if (family == null || ownerId == null || ownerId == family.me) return SharedBy.Family
    return family.member(ownerId)?.displayName?.let { SharedBy.Member(FamilyStore.firstNameOf(it)) } ?: SharedBy.Family
}

/** Label of a family item: "Family" or "from Sam"; null if it is personal. */
@Composable
fun sharedLabel(shared: Boolean, ownerId: String?, family: FamilyInfo?): String? = when (val by = sharedBy(shared, ownerId, family)) {
    null -> null
    SharedBy.Family -> stringResource(R.string.shared_family)
    is SharedBy.Member -> stringResource(R.string.shared_by, by.firstName)
}

/** You can stop sharing it: you created it (the server answers 400 to everyone else). */
fun FamilyInfo?.owns(ownerId: String?): Boolean = ownerId == null || this == null || ownerId == me

fun FamilyInfo?.firstNameOf(userId: String?): String? = this?.member(userId)?.displayName?.let(FamilyStore::firstNameOf)

/** "D", "VR": initials for a member's avatar. */
fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/** Neutral badge for shared items (no new colors: amber is kept for active things). */
@Composable
fun SharedBadge(label: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = CircleShape) {
        Row(Modifier.padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Groups, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Circle with a member's initials; [description] announces it to TalkBack ("Assignee: Sam"). */
@Composable
fun MemberAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 32.dp, description: String? = null) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initialsOf(name),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.4f).sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** "Share with family" row with a switch, for edit sheets and details. */
@Composable
fun ShareRow(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, title: String = stringResource(R.string.share_with_family), body: String? = null, enabled: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Groups, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

/**
 * Family item that cannot be changed from here (assigned to someone else or shared by another member):
 * it is explained instead of showing a locked switch, which reads as off.
 */
@Composable
fun SharedInfoRow(body: String, modifier: Modifier = Modifier, title: String = stringResource(R.string.shared_family)) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Groups, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.Check, stringResource(R.string.shared_with_family_cd), tint = MaterialTheme.colorScheme.primary)
    }
}
