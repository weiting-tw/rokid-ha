package tw.rokidha.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import tw.rokidha.app.RokidHaApplication
import tw.rokidha.homeassistant.model.HaEntity
import tw.rokidha.homeassistant.selection.GlassesSelection
import tw.rokidha.homeassistant.selection.ServiceCatalog

class EntitiesViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as RokidHaApplication).container
    val entities = c.haManager.entities
    val areas = c.haManager.areas
    val selection = c.selection

    fun toggleFavorite(id: String, on: Boolean) = c.updateSelection(c.selection.value.withFavorite(id, on))

    fun toggleAction(entityId: String, service: String, label: String, on: Boolean) {
        val s = c.selection.value
        c.updateSelection(if (on) s.withQuickAction(entityId, service, label) else s.withoutQuickAction("$entityId/$service"))
    }

    fun setNotificationEntity(id: String) {
        if (HaEntity.ENTITY_ID.matches(id)) c.updateSelection(c.selection.value.copy(notificationEntityId = id))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntitiesScreen(vm: EntitiesViewModel = viewModel()) {
    val entities by vm.entities.collectAsStateWithLifecycle()
    val areas by vm.areas.collectAsStateWithLifecycle()
    val sel by vm.selection.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("") }
    var byArea by remember { mutableStateOf(true) }
    var notifEntity by remember(sel.notificationEntityId) { mutableStateOf(sel.notificationEntityId) }

    val q = filter.trim().lowercase()
    val visible = entities.values
        .filter { q.isEmpty() || it.entityId.contains(q) || it.friendlyName.lowercase().contains(q) || areas[it.entityId]?.lowercase()?.contains(q) == true }
        .sortedBy { it.friendlyName.lowercase() }
    val groups = visible.groupBy { if (byArea) areas[it.entityId] ?: "No area" else it.domain }.toSortedMap()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Glasses entities", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
            Text(
                "Only selected entities are shown on the glasses (max ${GlassesSelection.MAX_FAVORITES}). " +
                    "Quick actions are the ONLY services this app may call.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (entities.isEmpty()) Text("Connect to Home Assistant first.", color = MaterialTheme.colorScheme.error)
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("Filter (name, entity id, area)") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(byArea, { byArea = true }, label = { Text("By area") })
                FilterChip(!byArea, { byArea = false }, label = { Text("By domain") })
                Text("${sel.favorites.size}/${GlassesSelection.MAX_FAVORITES} selected", Modifier.align(Alignment.CenterVertically))
            }
            OutlinedTextField(
                notifEntity, { notifEntity = it; vm.setNotificationEntity(it.trim()) }, Modifier.fillMaxWidth(),
                label = { Text("Notification entity (set by your HA automations)") }, singleLine = true,
            )
        }
        groups.forEach { (group, list) ->
            item { Text(group, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            items(list, key = { it.entityId }) { e ->
                val fav = e.entityId in sel.favorites
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = fav,
                            onCheckedChange = { vm.toggleFavorite(e.entityId, it) },
                            enabled = fav || sel.favorites.size < GlassesSelection.MAX_FAVORITES,
                        )
                        Column {
                            Text(e.friendlyName)
                            Text("${e.entityId} · ${e.state}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (fav) {
                        val options = ServiceCatalog.byDomain[e.domain].orEmpty()
                        if (options.isEmpty()) {
                            Text("Display only (no quick actions for ${e.domain})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 48.dp))
                        } else {
                            FlowRow(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                options.forEach { opt ->
                                    val on = sel.quickActions.any { it.entityId == e.entityId && it.service == opt.service }
                                    FilterChip(
                                        selected = on,
                                        onClick = { vm.toggleAction(e.entityId, opt.service, opt.label, !on) },
                                        label = { Text(opt.label + if (opt.sensitive) " (confirm)" else "") },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
