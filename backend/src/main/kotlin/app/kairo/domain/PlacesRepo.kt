package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.instant
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.db.uuid
import app.kairo.util.Lang
import app.kairo.util.M
import app.kairo.util.say
import java.sql.ResultSet
import java.util.UUID

/** Saved places (Home, Work, Supermarket) for location reminders. Home is usually a family place. */
class PlacesRepo(
    private val db: Db,
    private val activity: ActivityRepo,
    /** Language of a user, for the default name the server gives to an unnamed place. */
    private val langOf: suspend (UUID) -> Lang = { Lang.DEFAULT },
) {

    suspend fun list(userId: UUID): List<Place> = db.read {
        query("select * from places where ${visibleTo()} and deleted_at is null order by name", userId, userId) { map(it) }
    }

    suspend fun get(userId: UUID, id: UUID): Place? = db.read {
        one("select * from places where ${visibleTo()} and id = ? and deleted_at is null", userId, userId, id) { map(it) }
    }

    /** By name, tolerating English and Spanish ("home" finds "Casa"); on a tie, the user's own come first. */
    suspend fun findByName(userId: UUID, name: String): Place? {
        val target = Text.normalize(name)
        val all = list(userId).sortedBy { if (it.ownerId == userId) 0 else 1 }
        return all.firstOrNull { Text.normalize(it.name) == target }
            ?: all.firstOrNull { Text.normalize(it.name).contains(target) || target.contains(Text.normalize(it.name)) }
            ?: all.firstOrNull { Text.placeKey(it.name) == Text.placeKey(name) }
    }

    suspend fun upsert(userId: UUID, rawName: String, lat: Double, lon: Double, radiusM: Int, address: String?, source: String, shared: Boolean? = null): Place {
        val name = Text.cleanName(rawName).ifEmpty { M.DEFAULT_PLACE_NAME.t(langOf(userId)) }
        val existing = findByName(userId, name)?.takeIf { Text.normalize(it.name) == Text.normalize(name) }
        val place = db.tx {
            if (existing != null) {
                one(
                    """update places set latitude = ?, longitude = ?, radius_m = ?, address = coalesce(?, address),
                       shared = case when user_id = ? then coalesce(?, shared) else shared end where id = ? returning *""",
                    lat, lon, radiusM, address, userId, shared, existing.id,
                ) { map(it) }!!
            } else {
                one(
                    "insert into places(user_id, household_id, shared, name, latitude, longitude, radius_m, address) values (?, $HOUSEHOLD_OF, ?, ?, ?, ?, ?, ?) returning *",
                    userId, userId, shared ?: false, name.trim(), lat, lon, radiusM, address,
                ) { map(it) }!!
            }
        }
        activity.record(
            userId, if (existing != null) Kinds.UPDATED else Kinds.CREATED, EntityTypes.PLACE, place.id, say(M.ACT_PLACE, place.name), source,
            shared = place.shared || existing?.shared == true,
        )
        return place
    }

    suspend fun setShared(userId: UUID, id: UUID, shared: Boolean): Place? {
        val p = db.tx { one("update places set shared = ? where user_id = ? and id = ? and deleted_at is null returning *", shared, userId, id) { map(it) } }
            ?: return null
        activity.notify(userId, EntityTypes.PLACE, id, Kinds.UPDATED, shared = true)
        return p
    }

    suspend fun delete(userId: UUID, id: UUID, source: String): Boolean {
        val p = get(userId, id) ?: return false
        db.tx { exec("update places set deleted_at = now() where id = ?", id) }
        activity.record(userId, Kinds.DELETED, EntityTypes.PLACE, id, say(M.ACT_PLACE_DELETED, p.name), source, shared = p.shared)
        return true
    }

    suspend fun restore(userId: UUID, id: UUID): Boolean =
        db.tx { exec("update places set deleted_at = null where ${visibleTo()} and id = ?", userId, userId, id) } > 0

    private fun map(rs: ResultSet) = Place(
        id = rs.uuid("id"),
        name = rs.str("name"),
        latitude = rs.getDouble("latitude"),
        longitude = rs.getDouble("longitude"),
        radiusM = rs.getInt("radius_m"),
        address = rs.strOrNull("address"),
        createdAt = rs.instant("created_at"),
        shared = rs.getBoolean("shared"),
        ownerId = rs.uuid("user_id"),
    )
}

object Text {
    /** Maximum length of a list or place name (other members see it and it reaches the agent prompt). */
    const val NAME_MAX = 60

    /** One-line name: no breaks or control or invisible characters, collapsed spaces and trimmed to [max]. */
    fun cleanName(raw: String, max: Int = NAME_MAX): String =
        raw.replace(Regex("\\p{Cf}"), "").replace(Regex("[\\p{Cc}\\p{Zl}\\p{Zp}]"), " ").replace(Regex("\\s+"), " ").trim().take(max).trim()

    /**
     * Lowercase without accents or common articles/prefixes, Spanish and English
     * ("la lista del súper" -> "super", "my shopping list" -> "shopping list").
     */
    fun normalize(s: String): String {
        val noAccents = java.text.Normalizer.normalize(s.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccents
            .replace(Regex("^(la |el |los |las |mi |mis |the |my |our )"), "")
            .replace(Regex("^lista (de |del |de la |de los |de las )?"), "")
            .replace(Regex("^list (of |for )"), "")
            .replace(Regex("^(el |la |the )"), "")
            .replace(Regex("[^a-z0-9ñ ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** The usual ways of naming the shopping list, normalized (English and Spanish). */
    private val SHOPPING_ALIASES = setOf(
        "super", "supermercado", "mercado", "compras", "lista de compras",
        "shopping", "shopping list", "groceries", "grocery", "grocery list", "supermarket",
    )

    /** Is this name one of the ways of saying "shopping list"? */
    fun isShoppingAlias(name: String): Boolean = normalize(name) in SHOPPING_ALIASES

    /** English and Spanish names of the common places map to one key, so "home" finds "Casa". */
    private val PLACE_GROUPS = mapOf(
        "home" to "casa", "house" to "casa", "casa" to "casa", "hogar" to "casa",
        "work" to "trabajo", "office" to "trabajo", "trabajo" to "trabajo", "oficina" to "trabajo",
        "school" to "colegio", "colegio" to "colegio", "escuela" to "colegio",
        "gym" to "gimnasio", "gimnasio" to "gimnasio",
        "supermarket" to "super", "grocery store" to "super", "super" to "super", "supermercado" to "super",
    )

    fun placeKey(name: String): String = normalize(name).let { PLACE_GROUPS[it] ?: it }

    fun similar(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y || x.contains(y) || y.contains(x)) return true
        // "super" ~ "supermercado" ~ "groceries" ~ "shopping list"; "mercado" ~ "mercado del mes" is covered by contains
        return x in SHOPPING_ALIASES && y in SHOPPING_ALIASES
    }
}
