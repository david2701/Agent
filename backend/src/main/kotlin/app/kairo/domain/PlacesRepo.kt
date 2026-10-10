package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.instant
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.db.uuid
import java.sql.ResultSet
import java.util.UUID

/** Lugares guardados (Casa, Trabajo, Súper) para recordatorios por ubicación. Casa suele ser familiar. */
class PlacesRepo(private val db: Db, private val activity: ActivityRepo) {

    suspend fun list(userId: UUID): List<Place> = db.read {
        query("select * from places where ${visibleTo()} and deleted_at is null order by name", userId, userId) { map(it) }
    }

    suspend fun get(userId: UUID, id: UUID): Place? = db.read {
        one("select * from places where ${visibleTo()} and id = ? and deleted_at is null", userId, userId, id) { map(it) }
    }

    /** Por nombre; ante un empate, primero los propios. */
    suspend fun findByName(userId: UUID, name: String): Place? {
        val target = Text.normalize(name)
        val all = list(userId).sortedBy { if (it.ownerId == userId) 0 else 1 }
        return all.firstOrNull { Text.normalize(it.name) == target }
            ?: all.firstOrNull { Text.normalize(it.name).contains(target) || target.contains(Text.normalize(it.name)) }
    }

    suspend fun upsert(userId: UUID, rawName: String, lat: Double, lon: Double, radiusM: Int, address: String?, source: String, shared: Boolean? = null): Place {
        val name = Text.cleanName(rawName).ifEmpty { "Lugar" }
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
            userId, if (existing != null) Kinds.UPDATED else Kinds.CREATED, EntityTypes.PLACE, place.id, "Lugar: ${place.name}", source,
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
        activity.record(userId, Kinds.DELETED, EntityTypes.PLACE, id, "Lugar eliminado: ${p.name}", source, shared = p.shared)
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
    /** Largo máximo del nombre de una lista o un lugar (lo ven otros miembros y llega al prompt del agente). */
    const val NAME_MAX = 60

    /** Nombre en una sola línea: sin saltos ni caracteres de control o invisibles, espacios colapsados y recortado a [max]. */
    fun cleanName(raw: String, max: Int = NAME_MAX): String =
        raw.replace(Regex("\\p{Cf}"), "").replace(Regex("[\\p{Cc}\\p{Zl}\\p{Zp}]"), " ").replace(Regex("\\s+"), " ").trim().take(max).trim()

    /** Minúsculas sin tildes ni artículos/prefijos comunes ("la lista del súper" -> "super"). */
    fun normalize(s: String): String {
        val noAccents = java.text.Normalizer.normalize(s.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccents
            .replace(Regex("^(la |el |los |las |mi |mis )"), "")
            .replace(Regex("^lista (de |del |de la |de los |de las )?"), "")
            .replace(Regex("^(el |la )"), "")
            .replace(Regex("[^a-z0-9ñ ]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun similar(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y || x.contains(y) || y.contains(x)) return true
        // "super" ~ "supermercado", "mercado" ~ "mercado del mes"
        val alias = mapOf("super" to "supermercado", "mercado" to "supermercado", "compras" to "supermercado")
        return alias[x] == y || alias[y] == x || alias[x] != null && alias[x] == alias[y]
    }
}
