package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.Tx
import app.kairo.db.instant
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.db.uuid
import app.kairo.services.Notifier
import java.sql.ResultSet
import java.util.UUID

/** Listas con casillas (súper, pendientes, viaje...). Las familiares las ven y editan todos en el hogar. */
class ListsRepo(private val db: Db, private val activity: ActivityRepo, private val notifier: Notifier) {

    suspend fun all(userId: UUID): List<ShoppingList> = db.read {
        query("select * from lists where ${visibleTo()} and deleted_at is null order by shared desc, updated_at desc", userId, userId) { mapList(it) }
            .map { it.copy(items = itemsTx(it.id)) }
    }

    suspend fun get(userId: UUID, id: UUID): ShoppingList? = db.read { getTx(userId, id) }

    private fun Tx.getTx(userId: UUID, id: UUID): ShoppingList? =
        one("select * from lists where ${visibleTo()} and id = ? and deleted_at is null", userId, userId, id) { mapList(it) }
            ?.let { it.copy(items = itemsTx(it.id)) }

    private fun Tx.itemsTx(listId: UUID): List<ListItem> =
        query("select * from list_items where list_id = ? order by checked, sort, created_at", listId) { mapItem(it) }

    /**
     * Busca por nombre tolerando variaciones ("súper", "lista del super", "supermercado").
     * Si hay una personal y una familiar con el mismo nombre, [preferShared] decide (por defecto, la familiar).
     */
    suspend fun findByName(userId: UUID, name: String, preferShared: Boolean = true): ShoppingList? {
        val lists = all(userId).sortedBy { if (it.shared == preferShared) 0 else 1 }
        val n = Text.normalize(name)
        return lists.firstOrNull { Text.normalize(it.name) == n } ?: lists.firstOrNull { Text.similar(it.name, name) }
    }

    suspend fun create(userId: UUID, name: String, icon: String? = null, source: String, shared: Boolean = false): ShoppingList {
        val list = db.tx {
            val id = one(
                "insert into lists(user_id, household_id, shared, name, icon) values (?, $HOUSEHOLD_OF, ?, ?, ?) returning id",
                userId, userId, shared, prettyName(name), icon,
            ) { it.uuid("id") }!!
            getTx(userId, id)!!
        }
        activity.record(userId, Kinds.CREATED, EntityTypes.LIST, list.id, "Lista: ${list.name}", source, shared = list.shared)
        if (list.shared) announce(userId, list)
        return list
    }

    /** Usa la lista que ya existe; si piden una familiar y sólo hay una personal propia con ese nombre, la comparte. */
    suspend fun findOrCreate(userId: UUID, name: String, source: String, shared: Boolean = false): ShoppingList {
        val found = findByName(userId, name, preferShared = true)
        return when {
            found == null -> create(userId, name, source = source, shared = shared)
            shared && !found.shared && found.ownerId == userId -> rename(userId, found.id, null, null, shared = true) ?: found
            else -> found
        }
    }

    suspend fun rename(userId: UUID, id: UUID, name: String?, icon: String?, shared: Boolean? = null): ShoppingList? {
        val current = get(userId, id) ?: return null
        if (shared != null && shared != current.shared && current.ownerId != userId) {
            throw FamilyException("Solo quien creó la lista puede cambiar si se comparte")
        }
        val list = db.tx {
            exec(
                "update lists set name = coalesce(?, name), icon = coalesce(?, icon), shared = coalesce(?, shared), updated_at = now() where ${visibleTo()} and id = ?",
                name?.let { Text.cleanName(it) }?.takeIf { it.isNotEmpty() }, icon, shared, userId, userId, id,
            )
            getTx(userId, id)
        } ?: return null
        activity.notify(userId, EntityTypes.LIST, id, Kinds.UPDATED, shared = list.shared || current.shared)
        if (list.shared && !current.shared) announce(userId, list)
        return list
    }

    suspend fun addItems(userId: UUID, listId: UUID, texts: List<String>, source: String): ShoppingList? {
        val clean = texts.map { it.trim() }.filter { it.isNotEmpty() }
        val list = db.tx {
            getTx(userId, listId) ?: return@tx null
            val existing = itemsTx(listId)
            var sort = (existing.maxOfOrNull { it.sort } ?: 0.0) + 1
            clean.forEach { text ->
                val dup = existing.firstOrNull { Text.normalize(it.text) == Text.normalize(text) }
                if (dup != null) {
                    if (dup.checked) exec("update list_items set checked = false, updated_at = now() where id = ?", dup.id)
                } else {
                    exec("insert into list_items(list_id, text, sort) values (?, ?, ?)", listId, text.replaceFirstChar { it.uppercase() }, sort++)
                }
            }
            exec("update lists set updated_at = now() where id = ?", listId)
            getTx(userId, listId)
        } ?: return null
        activity.record(userId, Kinds.UPDATED, EntityTypes.LIST, listId, "${list.name}: + ${clean.joinToString(", ")}", source, shared = list.shared)
        return list
    }

    /** Marca/desmarca elementos por texto aproximado. Devuelve los que no encontró. */
    suspend fun setChecked(userId: UUID, listId: UUID, texts: List<String>, checked: Boolean, source: String): Pair<ShoppingList?, List<String>> {
        val missing = ArrayList<String>()
        val list = db.tx {
            getTx(userId, listId) ?: return@tx null
            val items = itemsTx(listId)
            texts.forEach { t ->
                val match = items.firstOrNull { Text.normalize(it.text) == Text.normalize(t) } ?: items.firstOrNull { Text.similar(it.text, t) }
                if (match == null) missing += t else exec("update list_items set checked = ?, updated_at = now() where id = ?", checked, match.id)
            }
            exec("update lists set updated_at = now() where id = ?", listId)
            getTx(userId, listId)
        }
        list?.let { activity.record(userId, Kinds.UPDATED, EntityTypes.LIST, listId, "${it.name}: ${if (checked) "marcado" else "desmarcado"} ${texts.joinToString(", ")}", source, shared = it.shared) }
        return list to missing
    }

    suspend fun removeItems(userId: UUID, listId: UUID, texts: List<String>, source: String): Pair<ShoppingList?, List<String>> {
        val missing = ArrayList<String>()
        val list = db.tx {
            getTx(userId, listId) ?: return@tx null
            val items = itemsTx(listId)
            texts.forEach { t ->
                val match = items.firstOrNull { Text.normalize(it.text) == Text.normalize(t) } ?: items.firstOrNull { Text.similar(it.text, t) }
                if (match == null) missing += t else exec("delete from list_items where id = ?", match.id)
            }
            exec("update lists set updated_at = now() where id = ?", listId)
            getTx(userId, listId)
        }
        list?.let { activity.record(userId, Kinds.UPDATED, EntityTypes.LIST, listId, "${it.name}: quitado ${texts.joinToString(", ")}", source, shared = it.shared) }
        return list to missing
    }

    suspend fun clearChecked(userId: UUID, listId: UUID, source: String): ShoppingList? {
        val list = db.tx {
            getTx(userId, listId) ?: return@tx null
            exec("delete from list_items where list_id = ? and checked", listId)
            exec("update lists set updated_at = now() where id = ?", listId)
            getTx(userId, listId)
        }
        list?.let { activity.record(userId, Kinds.UPDATED, EntityTypes.LIST, listId, "${it.name}: limpiados los marcados", source, shared = it.shared) }
        return list
    }

    suspend fun updateItem(userId: UUID, listId: UUID, itemId: UUID, text: String?, checked: Boolean?, sort: Double?): ShoppingList? = db.tx {
        getTx(userId, listId) ?: return@tx null
        exec(
            "update list_items set text = coalesce(?, text), checked = coalesce(?, checked), sort = coalesce(?, sort), updated_at = now() where id = ? and list_id = ?",
            text?.trim(), checked, sort, itemId, listId,
        )
        exec("update lists set updated_at = now() where id = ?", listId)
        getTx(userId, listId)
    }.also { if (it != null) activity.record(userId, Kinds.UPDATED, EntityTypes.LIST, listId, "${it.name}: elemento actualizado", "dashboard", shared = it.shared) }

    suspend fun deleteItem(userId: UUID, listId: UUID, itemId: UUID): ShoppingList? = db.tx {
        getTx(userId, listId) ?: return@tx null
        exec("delete from list_items where id = ? and list_id = ?", itemId, listId)
        getTx(userId, listId)
    }.also { if (it != null) activity.notify(userId, EntityTypes.LIST, listId, Kinds.UPDATED, shared = it.shared) }

    suspend fun delete(userId: UUID, id: UUID, source: String): ShoppingList? {
        val list = get(userId, id) ?: return null
        db.tx { exec("update lists set deleted_at = now() where ${visibleTo()} and id = ?", userId, userId, id) }
        activity.record(userId, Kinds.DELETED, EntityTypes.LIST, id, "Lista eliminada: ${list.name}", source, shared = list.shared)
        return list
    }

    suspend fun restore(userId: UUID, id: UUID): Boolean =
        db.tx { exec("update lists set deleted_at = null where ${visibleTo()} and id = ?", userId, userId, id) } > 0

    private suspend fun announce(userId: UUID, list: ShoppingList) =
        notifier.everyone(userId, NoticeKinds.SHARED, "${notifier.firstName(userId)} compartió una lista", list.name, EntityTypes.LIST, list.id)

    private fun mapList(rs: ResultSet) = ShoppingList(
        id = rs.uuid("id"),
        name = rs.str("name"),
        icon = rs.strOrNull("icon"),
        items = emptyList(),
        createdAt = rs.instant("created_at"),
        updatedAt = rs.instant("updated_at"),
        shared = rs.getBoolean("shared"),
        ownerId = rs.uuid("user_id"),
    )

    private fun mapItem(rs: ResultSet) = ListItem(rs.uuid("id"), rs.str("text"), rs.getBoolean("checked"), rs.getDouble("sort"), rs.instant("created_at"))

    companion object {
        /** Nombre para mostrar: una línea, máximo [Text.NAME_MAX] caracteres ("lista del súper" -> "Supermercado"). */
        fun prettyName(raw: String): String {
            val n = Text.cleanName(raw, Int.MAX_VALUE).removePrefix("lista de ").removePrefix("Lista de ").removePrefix("lista del ").removePrefix("Lista del ")
            val pretty = when (Text.normalize(n)) {
                "super", "supermercado", "mercado", "compras" -> "Supermercado"
                else -> n.trim().replaceFirstChar { it.uppercase() }
            }
            return Text.cleanName(pretty).ifEmpty { "Lista" }
        }
    }
}
