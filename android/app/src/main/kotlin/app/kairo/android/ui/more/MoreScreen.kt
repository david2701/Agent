package app.kairo.android.ui.more

import app.kairo.android.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kairo.android.ui.Destination

private val DESCRIPTIONS = mapOf(
    Destination.Reminders to R.string.more_reminders,
    Destination.Calendar to R.string.more_calendar,
    Destination.Lists to R.string.more_lists,
    Destination.Family to R.string.more_family,
    Destination.Memory to R.string.more_memory,
    Destination.Recordings to R.string.more_recordings,
    Destination.Activity to R.string.more_activity,
    Destination.Settings to R.string.more_settings,
)

@Composable
fun MoreScreen(navigate: (Destination) -> Unit, destinations: List<Destination> = Destination.inMore) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.nav_more), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) }
        items(destinations) { d ->
            Surface(onClick = { navigate(d) }, color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(d.icon, null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(stringResource(d.label), style = MaterialTheme.typography.titleSmall)
                        Text(DESCRIPTIONS[d]?.let { stringResource(it) } ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
