package app.kairo.android.ui.components

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

/** Familia en caché; null mientras carga o sin sesión. */
@Composable
fun rememberFamily(graph: AppGraph): FamilyInfo? = graph.family.info.collectAsState().value

/** Hay alguien más en la familia: solo entonces tiene sentido compartir o asignar. */
val FamilyInfo?.hasOthers: Boolean get() = this != null && members.any { it.id != me }

/**
 * Etiqueta de algo familiar: "Familiar" si lo compartiste tú (o no se sabe de quién es),
 * "de Valentina" si lo compartió otro miembro; null si es personal.
 */
fun sharedLabel(shared: Boolean, ownerId: String?, family: FamilyInfo?): String? {
    if (!shared) return null
    if (family == null || ownerId == null || ownerId == family.me) return "Familiar"
    return family.member(ownerId)?.displayName?.let { "de ${FamilyStore.firstNameOf(it)}" } ?: "Familiar"
}

/** Lo puedes dejar de compartir: eres quien lo creó (el servidor responde 400 a los demás). */
fun FamilyInfo?.owns(ownerId: String?): Boolean = ownerId == null || this == null || ownerId == me

fun FamilyInfo?.firstNameOf(userId: String?): String? = this?.member(userId)?.displayName?.let(FamilyStore::firstNameOf)

/** "D", "VR": iniciales para el avatar de un miembro. */
fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }

/** Insignia neutra de lo compartido (sin colores nuevos: el ámbar queda para lo activo). */
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

/** Círculo con las iniciales de un miembro; [description] lo anuncia a TalkBack ("Responsable: Valentina"). */
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

/** Fila "Compartir con la familia" con interruptor, para hojas de edición y detalles. */
@Composable
fun ShareRow(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, title: String = "Compartir con la familia", body: String? = null, enabled: Boolean = true) {
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
 * Familiar que no se puede cambiar desde aquí (asignado a otra persona o compartido por otro miembro):
 * se explica en lugar de mostrar un interruptor bloqueado, que se lee como apagado.
 */
@Composable
fun SharedInfoRow(body: String, modifier: Modifier = Modifier, title: String = "Familiar") {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Groups, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.Check, "Compartido con la familia", tint = MaterialTheme.colorScheme.primary)
    }
}
