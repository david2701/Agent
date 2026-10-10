package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.Vec
import app.kairo.db.instant
import app.kairo.db.str
import app.kairo.db.uuid
import java.sql.ResultSet
import java.util.UUID

/** Memoria a largo plazo: datos duraderos sobre el usuario que Kairo usa como contexto. */
class MemoriesRepo(private val db: Db, private val activity: ActivityRepo, private val embedder: Embedder) {

    suspend fun list(userId: UUID): List<Memory> = db.read {
        query("select * from memories where user_id = ? and deleted_at is null order by category, updated_at desc", userId) { map(it) }
    }

    suspend fun get(userId: UUID, id: UUID): Memory? = db.read {
        one("select * from memories where user_id = ? and id = ? and deleted_at is null", userId, id) { map(it) }
    }

    suspend fun create(userId: UUID, content: String, category: String, source: String): Memory {
        // Evita duplicados casi idénticos
        val vec = embedder.embed(content)
        if (vec != null) {
            val dup = db.read {
                one(
                    """select *, 1 - (embedding <=> ?) as score from memories
                       where user_id = ? and deleted_at is null and embedding is not null
                       order by embedding <=> ? limit 1""",
                    Vec(vec), userId, Vec(vec),
                ) { map(it) }
            }
            if (dup != null && (dup.score ?: 0.0) > 0.93) {
                return update(userId, dup.id, content, category, source) ?: dup
            }
        }
        val m = db.tx {
            one(
                "insert into memories(user_id, content, category, embedding) values (?, ?, ?, ?) returning *",
                userId, content.trim(), normalizeCategory(category), vec?.let { Vec(it) },
            ) { map(it) }!!
        }
        activity.record(userId, Kinds.CREATED, EntityTypes.MEMORY, m.id, "Recordado: ${m.content}", source)
        return m
    }

    suspend fun update(userId: UUID, id: UUID, content: String?, category: String?, source: String): Memory? {
        val m = db.tx {
            one(
                "update memories set content = coalesce(?, content), category = coalesce(?, category), updated_at = now() where user_id = ? and id = ? and deleted_at is null returning *",
                content?.trim(), category?.let(::normalizeCategory), userId, id,
            ) { map(it) }
        } ?: return null
        if (content != null) embedder.scheduleMemory(m.id, m.content)
        activity.record(userId, Kinds.UPDATED, EntityTypes.MEMORY, id, "Memoria actualizada: ${m.content}", source)
        return m
    }

    suspend fun delete(userId: UUID, id: UUID, source: String): Memory? {
        val m = get(userId, id) ?: return null
        db.tx { exec("update memories set deleted_at = now() where id = ?", id) }
        activity.record(userId, Kinds.DELETED, EntityTypes.MEMORY, id, "Olvidado: ${m.content}", source)
        return m
    }

    suspend fun restore(userId: UUID, id: UUID): Boolean =
        db.tx { exec("update memories set deleted_at = null where user_id = ? and id = ?", userId, id) } > 0

    /** Memorias relevantes para el mensaje actual + las de perfil, para el prompt del sistema. */
    suspend fun relevant(userId: UUID, query: String?, k: Int = 6): List<Memory> {
        val profile = db.read {
            query("select * from memories where user_id = ? and deleted_at is null and category = 'perfil' order by updated_at desc limit 8", userId) { map(it) }
        }
        val vec = query?.takeIf { it.isNotBlank() }?.let { embedder.embed(it) }
        val similar = if (vec == null) {
            db.read { query("select * from memories where user_id = ? and deleted_at is null order by updated_at desc limit ?", userId, k) { map(it) } }
        } else {
            db.read {
                query(
                    """select *, 1 - (embedding <=> ?) as score from memories
                       where user_id = ? and deleted_at is null and embedding is not null
                       order by embedding <=> ? limit ?""",
                    Vec(vec), userId, Vec(vec), k,
                ) { map(it) }
            }.filter { (it.score ?: 0.0) > 0.35 }
        }
        return (profile + similar).distinctBy { it.id }
    }

    suspend fun search(userId: UUID, query: String, k: Int = 8): List<Memory> {
        val vec = embedder.embed(query) ?: return list(userId).filter { it.content.contains(query, ignoreCase = true) }.take(k)
        return db.read {
            query(
                """select *, 1 - (embedding <=> ?) as score from memories
                   where user_id = ? and deleted_at is null and embedding is not null
                   order by embedding <=> ? limit ?""",
                Vec(vec), userId, Vec(vec), k,
            ) { map(it) }
        }
    }

    private fun map(rs: ResultSet) = Memory(
        id = rs.uuid("id"),
        content = rs.str("content"),
        category = rs.str("category"),
        createdAt = rs.instant("created_at"),
        updatedAt = rs.instant("updated_at"),
        score = runCatching { rs.getDouble("score").takeIf { !rs.wasNull() } }.getOrNull(),
    )

    companion object {
        val CATEGORIES = listOf("perfil", "preferencias", "personas", "salud", "trabajo", "hogar", "general")

        fun normalizeCategory(c: String): String {
            val n = Text.normalize(c)
            return CATEGORIES.firstOrNull { Text.normalize(it) == n } ?: when (n) {
                "profile", "yo", "personal" -> "perfil"
                "preference", "preferences", "gustos" -> "preferencias"
                "people", "familia", "contactos" -> "personas"
                "health" -> "salud"
                "work", "trabajo" -> "trabajo"
                "home", "casa" -> "hogar"
                else -> "general"
            }
        }
    }
}
