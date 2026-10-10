package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.instant
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.db.uuid
import java.sql.ResultSet
import java.util.UUID

/** Lugares guardados (Casa, Trabajo, Súper) para recordatorios por ubicación. */
class PlacesRepo(private val db: Db, private val activity: ActivityRepo) {

    suspend fun list(userId: UUID): List<Place> = db.read {
        query("select * from places where user_id = ? and deleted_at is null order by name", userId) { map(it) }
    }

    suspend fun get(userId: UUID, id: UUID): Place? = db.read {
        one("select * from places where user_id = ? and id = ? and deleted_at is null", userId, id) { map(it) }
    }

    suspend fun findByName(userId: UUID, name: String): Place? {
        val target = Text.normalize(name)
        return list(userId).firstOrNull { Text.normalize(it.name) == target }
            ?: list(userId).firstOrNull { Text.normalize(it.name).contains(target) || target.contains(Text.normalize(it.name)) }
    }

    suspend fun upsert(userId: UUID, name: String, lat: Double, lon: Double, radiusM: Int, address: String?, source: String): Place {
        val existing = findByName(userId, name)?.takeIf { Text.normalize(it.name) == Text.normalize(name) }
        val place = db.tx {
            if (existing != null) {
                one(
                    "update places set latitude = ?, longitude = ?, radius_m = ?, address = coalesce(?, address) where id = ? returning *",
                    lat, lon, radiusM, address, existing.id,
                ) { map(it) }!!
            } else {
                one(
                    "insert into places(user_id, name, latitude, longitude, radius_m, address) values (?, ?, ?, ?, ?, ?) returning *",
                    userId, name.trim(), lat, lon, radiusM, address,
                ) { map(it) }!!
            }
        }
        activity.record(userId, if (existing != null) Kinds.UPDATED else Kinds.CREATED, EntityTypes.PLACE, place.id, "Lugar: ${place.name}", source)
        return place
    }

    suspend fun delete(userId: UUID, id: UUID, source: String): Boolean {
        val p = get(userId, id) ?: return false
        db.tx { exec("update places set deleted_at = now() where id = ?", id) }
        activity.record(userId, Kinds.DELETED, EntityTypes.PLACE, id, "Lugar eliminado: ${p.name}", source)
        return true
    }

    suspend fun restore(userId: UUID, id: UUID): Boolean =
        db.tx { exec("update places set deleted_at = null where user_id = ? and id = ?", userId, id) } > 0

    private fun map(rs: ResultSet) = Place(
        id = rs.uuid("id"),
        name = rs.str("name"),
        latitude = rs.getDouble("latitude"),
        longitude = rs.getDouble("longitude"),
        radiusM = rs.getInt("radius_m"),
        address = rs.strOrNull("address"),
        createdAt = rs.instant("created_at"),
    )
}

object Text {
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
