package app.kairo.android.ui.lists

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kairo.android.AppGraph
import app.kairo.android.ui.common.ResourceViewModel
import app.kairo.android.ui.components.EmptyState
import app.kairo.android.ui.components.ErrorBanner
import app.kairo.android.ui.components.SharedBadge
import app.kairo.android.ui.components.ShareRow
import app.kairo.android.ui.components.SkeletonList
import app.kairo.android.ui.components.firstNameOf
import app.kairo.android.ui.components.hasOthers
import app.kairo.android.ui.components.owns
import app.kairo.android.ui.components.rememberFamily
import app.kairo.android.ui.components.sharedLabel
import app.kairo.android.ui.theme.MonoStyle
import app.kairo.core.model.FamilyInfo
import app.kairo.core.model.ShoppingList
import app.kairo.core.net.KairoApi.Companion.obj
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

class ListsViewModel(graph: AppGraph) : ResourceViewModel<List<ShoppingList>>(graph, setOf("list")) {
    override suspend fun load() = graph.api.lists()

    init { refresh() }

    private fun replace(list: ShoppingList) = updateData { all -> all.map { if (it.id == list.id) list else it } }

    fun toggle(list: ShoppingList, itemId: String, checked: Boolean) {
        replace(list.copy(items = list.items.map { if (it.id == itemId) it.copy(checked = checked) else it }))
        mutate { replace(graph.api.setItem(list.id, itemId, checked)) }
    }
    fun add(list: ShoppingList, text: String) = mutate { replace(graph.api.addItems(list.id, text.split(',').map { it.trim() }.filter { it.isNotEmpty() })) }
    fun remove(list: ShoppingList, itemId: String) = mutate { replace(graph.api.deleteItem(list.id, itemId)) }
    fun clearChecked(list: ShoppingList) = mutate("Marcados eliminados") { replace(graph.api.clearChecked(list.id)) }
    fun create(name: String, shared: Boolean) = mutate(if (shared) "Lista familiar creada" else "Lista creada") { graph.api.createList(name, shared) }
    fun share(list: ShoppingList, shared: Boolean) {
        replace(list.copy(shared = shared))
        mutate(if (shared) "Lista compartida con la familia" else "La lista vuelve a ser solo tuya") { replace(graph.api.updateList(list.id, obj { put("shared", shared) })) }
    }
    fun delete(list: ShoppingList) = mutate("Lista eliminada. Puedes deshacerlo en Actividad.") { graph.api.deleteList(list.id) }
}

@Composable
fun ListsScreen(graph: AppGraph) {
    val vm: ListsViewModel = viewModel { ListsViewModel(graph) }
    val state by vm.state.collectAsState()
    val navigator = rememberListDetailPaneScaffoldNavigator<String>(scaffoldDirective = calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(currentWindowAdaptiveInfo()))
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var creating by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    BackHandler(navigator.canNavigateBack()) { scope.launch { navigator.navigateBack() } }
    val lists = state.data.orEmpty()
    val selectedId = navigator.currentDestination?.contentKey
    val family = rememberFamily(graph)

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { inner ->
        ListDetailPaneScaffold(
            modifier = Modifier.padding(inner),
            directive = navigator.scaffoldDirective,
            value = navigator.scaffoldValue,
            listPane = {
                AnimatedPane {
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { Text("Listas", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }
                            state.error?.let { item { ErrorBanner(it, onRetry = { vm.refresh() }) } }
                            if (state.loading && state.data == null) item { SkeletonList(4) }
                            else if (lists.isEmpty()) item { EmptyState(Icons.Rounded.ShoppingBasket, "Sin listas", "Dile a Kairo \"agrega leche y huevos a la lista del súper\".") }
                            items(lists, key = { it.id }) { l ->
                                val pending = l.items.count { !it.checked }
                                Surface(onClick = { scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, l.id) } }, shape = MaterialTheme.shapes.medium,
                                    color = if (selectedId == l.id) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(l.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                                                sharedLabel(l.shared, l.ownerId, family)?.let { SharedBadge(it) }
                                            }
                                            Text(l.items.filter { !it.checked }.take(4).joinToString(", ") { it.text }.ifBlank { "Todo listo" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        }
                                        Text("$pending", style = MonoStyle, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Nueva lista") },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                            containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            },
            detailPane = {
                AnimatedPane {
                    val list = lists.firstOrNull { it.id == selectedId }
                    if (list == null) EmptyState(Icons.Rounded.ShoppingBasket, "Elige una lista", "Verás sus elementos aquí.", Modifier.fillMaxSize())
                    else ListDetail(list, vm, family, navigator.canNavigateBack()) { scope.launch { navigator.navigateBack() } }
                }
            },
        )
    }

    if (creating) {
        var name by rememberSaveable { mutableStateOf("") }
        var shared by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Nueva lista") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                    if (family.hasOthers) ShareRow(shared, { shared = it }, title = "Familiar", body = "Todos pueden agregar y marcar.")
                }
            },
            confirmButton = { TextButton(onClick = { if (name.isNotBlank()) { vm.create(name.trim(), shared); creating = false } }) { Text("Crear") } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ListDetail(list: ShoppingList, vm: ListsViewModel, family: FamilyInfo?, showBack: Boolean, onBack: () -> Unit) {
    var text by rememberSaveable(list.id) { mutableStateOf("") }
    val pending = list.items.filter { !it.checked }
    val checked = list.items.filter { it.checked }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver") }
            Text(list.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
            if (checked.isNotEmpty()) IconButton(onClick = { vm.clearChecked(list) }) { Icon(Icons.Rounded.DeleteSweep, "Quitar marcados") }
            IconButton(onClick = { vm.delete(list); onBack() }) { Icon(Icons.Rounded.DeleteOutline, "Eliminar lista") }
        }
        when {
            !family.owns(list.ownerId) -> Text(
                "Lista de ${family.firstNameOf(list.ownerId) ?: "otro miembro"}, compartida con la familia.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 10.dp),
            )
            family.hasOthers || list.shared -> ShareRow(
                list.shared, { vm.share(list, it) }, Modifier.padding(start = 24.dp, end = 16.dp, bottom = 6.dp),
                title = "Familiar", body = if (list.shared) "Todos en la familia pueden agregar y marcar." else "Solo tú la ves.",
            )
        }
        TextField(
            value = text, onValueChange = { text = it },
            placeholder = { Text("Agregar (separa con comas)") },
            singleLine = true, shape = CircleShape,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { vm.add(list, text); text = "" } }),
            trailingIcon = { IconButton(onClick = { if (text.isNotBlank()) { vm.add(list, text); text = "" } }) { Icon(Icons.Rounded.Add, "Agregar") } },
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        LazyColumn(contentPadding = PaddingValues(12.dp)) {
            items(pending, key = { it.id }) { i -> ItemRow(i.text, false, onToggle = { vm.toggle(list, i.id, true) }, onRemove = { vm.remove(list, i.id) }) }
            if (checked.isNotEmpty()) item { Text("Marcados", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp)) }
            items(checked, key = { it.id }) { i -> ItemRow(i.text, true, onToggle = { vm.toggle(list, i.id, false) }, onRemove = { vm.remove(list, i.id) }) }
        }
    }
}

@Composable
private fun ItemRow(text: String, checked: Boolean, onToggle: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { onToggle() })
        Text(
            text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
            textDecoration = if (checked) TextDecoration.LineThrough else null,
            color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Quitar", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.width(4.dp))
    }
}
