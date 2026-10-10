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
import app.kairo.services.Notifier
import java.sql.ResultSet
import java.util.UUID

data class NotePatch(
    val title: String? = null,
    val content: String? = null,
    val tags: List<String>? = null,
    val pinned: Boolean? = null,
    val attachments: List<UUID>? = null,
    val shared: Boolean? = null,
)

class NotesRepo(
    private val db: Db,
    private val activity: ActivityRepo,
    private val embedder: Embedder,
    private val files: FilesRepo,
    private val nan: NanClient,
    private val notifier: Notifier,
) {
    private val cols = "id, user_id, shared, title, content, tags, pinned, source, attachments, created_at, updated_at"

    suspend fun list(userId: UUID, q: String? = null, tag: String? = null, limit: Int = 100, offset: Int = 0): List<Note> = db.read {
        val like = q?.takeIf { it.isNotBlank() }?.let { "%${it.trim()}%" }
        query(
            """select $cols from notes
               where ${visibleTo()} and deleted_at is null
                 and (?::text is null or title ilike ? or content ilike ?)
                 and (?::text is null or ? = any(tags))
               order by pinned desc, updated_at desc limit ? offset ?""",
            userId, userId, like, like, like, tag, tag, limit.coerceIn(1, 500), offset.coerceAtLeast(0),
        ) { map(it) }
    }

    suspend fun get(userId: UUID, id: UUID): Note? = db.read {
        one("select $cols from notes where ${visibleTo()} and id = ? and deleted_at is null", userId, userId, id) { map(it) }
    }

    suspend fun create(
        userId: UUID,
        title: String,
        content: String,
        tags: List<String> = emptyList(),
        pinned: Boolean = false,
        source: String,
        attachments: List<UUID> = emptyList(),
        shared: Boolean = false,
    ): Note {
        val finalTitle = title.ifBlank { deriveTitle(content) }
        val note = db.tx {
            one(
                """insert into notes(user_id, household_id, shared, title, content, tags, pinned, source, attachments)
                   values (?, $HOUSEHOLD_OF, ?, ?, ?, ?, ?, ?, ?) returning $cols""",
                userId, userId, shared, finalTitle, content, TextArray(normalizeTags(tags)), pinned, source, UuidArray(attachments),
            ) { map(it) }!!
        }
        embedder.scheduleNote(note.id, note.title, note.content)
        activity.record(userId, Kinds.CREATED, EntityTypes.NOTE, note.id, "Nota: ${note.title}", source, shared = note.shared)
        if (note.shared) announce(userId, note)
        return note
    }

    suspend fun update(userId: UUID, id: UUID, patch: NotePatch, source: String): Note? {
        val current = get(userId, id) ?: return null
        if (patch.shared != null && patch.shared != current.shared && current.ownerId != userId) {
            throw FamilyException("Solo quien creó la nota puede cambiar si se comparte")
        }
        val note = db.tx {
            one(
                """update notes set title = ?, content = ?, tags = ?, pinned = ?, attachments = ?, shared = ?, updated_at = now()
                   where ${visibleTo()} and id = ? returning $cols""",
                patch.title ?: current.title,
                patch.content ?: current.content,
                TextArray(patch.tags?.let(::normalizeTags) ?: current.tags),
                patch.pinned ?: current.pinned,
                UuidArray(patch.attachments ?: current.attachments.map { it.id }),
                patch.shared ?: current.shared,
                userId, userId, id,
            ) { map(it) }
        } ?: return null
        if (patch.title != null || patch.content != null) embedder.scheduleNote(note.id, note.title, note.content)
        // Si deja de compartirse, también tiene que desaparecer de las pantallas de los demás
        activity.record(userId, Kinds.UPDATED, EntityTypes.NOTE, id, "Nota editada: ${note.title}", source, shared = note.shared || current.shared)
        if (note.shared && !current.shared) announce(userId, note)
        return note
    }

    suspend fun delete(userId: UUID, id: UUID, source: String): Note? {
        val note = get(userId, id) ?: return null
        db.tx { exec("update notes set deleted_at = now() where ${visibleTo()} and id = ?", userId, userId, id) }
        activity.record(userId, Kinds.DELETED, EntityTypes.NOTE, id, "Nota eliminada: ${note.title}", source, shared = note.shared)
        return note
    }

    suspend fun restore(userId: UUID, id: UUID): Boolean =
        db.tx { exec("update notes set deleted_at = null where ${visibleTo()} and id = ?", userId, userId, id) } > 0

    private suspend fun announce(userId: UUID, note: Note) =
        notifier.everyone(userId, NoticeKinds.SHARED, "${notifier.firstName(userId)} compartió una nota", note.title, EntityTypes.NOTE, note.id)

    /**
     * Búsqueda híbrida: similitud semántica (pgvector) + texto (full-text en español y trigramas),
     * reordenada con el modelo `rerank` de Nan. Incluye las notas que la familia compartió.
     */
    suspend fun search(userId: UUID, query: String, limit: Int = 5): List<Note> {
        val q = query.trim()
        if (q.isEmpty()) return list(userId, limit = limit)
        val vec = embedder.embedQuery(q)
        val candidates = LinkedHashMap<UUID, Note>()
        db.read {
            if (vec != null) {
                query(
                    """select $cols, 1 - (embedding <=> ?) as score from notes
                       where ${visibleTo()} and deleted_at is null and embedding is not null
                       order by embedding <=> ? limit 15""",
                    Vec(vec), userId, userId, Vec(vec),
                ) { map(it) }.forEach { candidates.putIfAbsent(it.id, it) }
            }
            query(
                """select $cols from notes
                   where ${visibleTo()} and deleted_at is null
                     and (to_tsvector('spanish', title || ' ' || content) @@ plainto_tsquery('spanish', ?)
                          or (title || ' ' || content) ilike ?
                          or similarity(title || ' ' || content, ?) > 0.15)
                   order by updated_at desc limit 15""",
                userId, userId, q, "%$q%", q,
            ) { map(it) }.forEach { candidates.putIfAbsent(it.id, it) }
        }
        if (candidates.isEmpty()) return emptyList()
        val docs = candidates.values.toList()
        val hits = runCatching { nan.rerank(q, docs.map { "${it.title}\n${it.content}" }) }.getOrNull()
            ?: return docs.take(limit)
        // El reranker es muy estricto con sinónimos ("impuestos" frente a "declaración de renta"):
        // también se conservan las notas muy cercanas en significado y las que contienen el texto literal.
        return hits.sortedByDescending { it.score }
            .filter { h ->
                val d = docs[h.index]
                h.score > 0.01 || hits.size <= limit || (d.score ?: 0.0) >= 0.4 ||
                    "${d.title}\n${d.content}".contains(q, ignoreCase = true)
            }
            .take(limit)
            .map { docs[it.index].copy(score = it.score) }
    }

    suspend fun allTags(userId: UUID): List<String> = db.read {
        query(
            "select distinct unnest(tags) as t from notes where ${visibleTo()} and deleted_at is null order by t",
            userId, userId,
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
        shared = rs.getBoolean("shared"),
        ownerId = rs.uuid("user_id"),
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
