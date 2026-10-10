package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.TextArray
import app.kairo.db.UuidArray
import app.kairo.db.Vec
import app.kairo.db.instant
import app.kairo.db.str
import app.kairo.db.textList
import app.kairo.db.uuid
import app.kairo.db.uuidList
import app.kairo.nan.NanClient
import java.sql.ResultSet
import java.util.UUID

data class NotePatch(
    val title: String? = null,
    val content: String? = null,
    val tags: List<String>? = null,
    val pinned: Boolean? = null,
    val attachments: List<UUID>? = null,
)

class NotesRepo(
    private val db: Db,
    private val activity: ActivityRepo,
    private val embedder: Embedder,
    private val files: FilesRepo,
    private val nan: NanClient,
) {
    private val cols = "id, title, content, tags, pinned, source, attachments, created_at, updated_at"

    suspend fun list(userId: UUID, q: String? = null, tag: String? = null, limit: Int = 100, offset: Int = 0): List<Note> = db.read {
        val like = q?.takeIf { it.isNotBlank() }?.let { "%${it.trim()}%" }
        query(
            """select $cols from notes
               where user_id = ? and deleted_at is null
                 and (?::text is null or title ilike ? or content ilike ?)
                 and (?::text is null or ? = any(tags))
               order by pinned desc, updated_at desc limit ? offset ?""",
            userId, like, like, like, tag, tag, limit.coerceIn(1, 500), offset.coerceAtLeast(0),
        ) { map(it) }
    }

    suspend fun get(userId: UUID, id: UUID): Note? = db.read {
        one("select $cols from notes where user_id = ? and id = ? and deleted_at is null", userId, id) { map(it) }
    }

    suspend fun create(
        userId: UUID,
        title: String,
        content: String,
        tags: List<String> = emptyList(),
        pinned: Boolean = false,
        source: String,
        attachments: List<UUID> = emptyList(),
    ): Note {
        val finalTitle = title.ifBlank { deriveTitle(content) }
        val note = db.tx {
            one(
                """insert into notes(user_id, title, content, tags, pinned, source, attachments)
                   values (?, ?, ?, ?, ?, ?, ?) returning $cols""",
                userId, finalTitle, content, TextArray(normalizeTags(tags)), pinned, source, UuidArray(attachments),
            ) { map(it) }!!
        }
        embedder.scheduleNote(note.id, note.title, note.content)
        activity.record(userId, Kinds.CREATED, EntityTypes.NOTE, note.id, "Nota: ${note.title}", source)
        return note
    }

    suspend fun update(userId: UUID, id: UUID, patch: NotePatch, source: String): Note? {
        val current = get(userId, id) ?: return null
        val note = db.tx {
            one(
                """update notes set title = ?, content = ?, tags = ?, pinned = ?, attachments = ?, updated_at = now()
                   where user_id = ? and id = ? returning $cols""",
                patch.title ?: current.title,
                patch.content ?: current.content,
                TextArray(patch.tags?.let(::normalizeTags) ?: current.tags),
                patch.pinned ?: current.pinned,
                UuidArray(patch.attachments ?: current.attachments.map { it.id }),
                userId, id,
            ) { map(it) }
        } ?: return null
        if (patch.title != null || patch.content != null) embedder.scheduleNote(note.id, note.title, note.content)
        activity.record(userId, Kinds.UPDATED, EntityTypes.NOTE, id, "Nota editada: ${note.title}", source)
        return note
    }

    suspend fun delete(userId: UUID, id: UUID, source: String): Note? {
        val note = get(userId, id) ?: return null
        db.tx { exec("update notes set deleted_at = now() where user_id = ? and id = ?", userId, id) }
        activity.record(userId, Kinds.DELETED, EntityTypes.NOTE, id, "Nota eliminada: ${note.title}", source)
        return note
    }

    suspend fun restore(userId: UUID, id: UUID): Boolean =
        db.tx { exec("update notes set deleted_at = null where user_id = ? and id = ?", userId, id) } > 0

    /**
     * Búsqueda híbrida: similitud semántica (pgvector) + texto (full-text en español y trigramas),
     * reordenada con el modelo `rerank` de Nan.
     */
    suspend fun search(userId: UUID, query: String, limit: Int = 5): List<Note> {
        val q = query.trim()
        if (q.isEmpty()) return list(userId, limit = limit)
        val vec = embedder.embed(q)
        val candidates = LinkedHashMap<UUID, Note>()
        db.read {
            if (vec != null) {
                query(
                    """select $cols, 1 - (embedding <=> ?) as score from notes
                       where user_id = ? and deleted_at is null and embedding is not null
                       order by embedding <=> ? limit 15""",
                    Vec(vec), userId, Vec(vec),
                ) { map(it) }.forEach { candidates.putIfAbsent(it.id, it) }
            }
            query(
                """select $cols from notes
                   where user_id = ? and deleted_at is null
                     and (to_tsvector('spanish', title || ' ' || content) @@ plainto_tsquery('spanish', ?)
                          or (title || ' ' || content) ilike ?
                          or similarity(title || ' ' || content, ?) > 0.15)
                   order by updated_at desc limit 15""",
                userId, q, "%$q%", q,
            ) { map(it) }.forEach { candidates.putIfAbsent(it.id, it) }
        }
        if (candidates.isEmpty()) return emptyList()
        val docs = candidates.values.toList()
        val hits = runCatching { nan.rerank(q, docs.map { "${it.title}\n${it.content}" }) }.getOrNull()
            ?: return docs.take(limit)
        return hits.sortedByDescending { it.score }
            .filter { it.score > 0.01 || hits.size <= limit }
            .take(limit)
            .map { docs[it.index].copy(score = it.score) }
    }

    suspend fun allTags(userId: UUID): List<String> = db.read {
        query(
            "select distinct unnest(tags) as t from notes where user_id = ? and deleted_at is null order by t",
            userId,
        ) { it.getString("t") }
    }

    private fun map(rs: ResultSet): Note = Note(
        id = rs.uuid("id"),
        title = rs.str("title"),
        content = rs.str("content"),
        tags = rs.textList("tags"),
        pinned = rs.getBoolean("pinned"),
        source = rs.str("source"),
        attachments = rs.uuidList("attachments").map { files.ref(it) },
        createdAt = rs.instant("created_at"),
        updatedAt = rs.instant("updated_at"),
        score = runCatching { rs.getDouble("score").takeIf { !rs.wasNull() } }.getOrNull(),
    )

    companion object {
        fun deriveTitle(content: String): String {
            val firstLine = content.lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotEmpty() } ?: "Nota"
            return if (firstLine.length <= 60) firstLine else firstLine.take(57).trimEnd() + "..."
        }

        fun normalizeTags(tags: List<String>): List<String> =
            tags.map { it.trim().trimStart('#').lowercase() }.filter { it.isNotEmpty() }.distinct().take(12)
    }
}
