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
import app.kairo.services.NoticeText
import app.kairo.services.Notifier
import app.kairo.util.Lang
import app.kairo.util.M
import app.kairo.util.Plain
import app.kairo.util.say
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
        activity.record(userId, Kinds.CREATED, EntityTypes.NOTE, note.id, say(M.ACT_NOTE, note.title), source, shared = note.shared)
        if (note.shared) announce(userId, note)
        return note
    }

    suspend fun update(userId: UUID, id: UUID, patch: NotePatch, source: String): Note? {
        val current = get(userId, id) ?: return null
        if (patch.shared != null && patch.shared != current.shared && current.ownerId != userId) {
            throw FamilyException(M.ONLY_OWNER_SHARE_NOTE)
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
        // If it stops being shared, it also has to disappear from the others' screens
        activity.record(userId, Kinds.UPDATED, EntityTypes.NOTE, id, say(M.ACT_NOTE_EDITED, note.title), source, shared = note.shared || current.shared)
        if (note.shared && !current.shared) announce(userId, note)
        return note
    }

    suspend fun delete(userId: UUID, id: UUID, source: String): Note? {
        val note = get(userId, id) ?: return null
        db.tx { exec("update notes set deleted_at = now() where ${visibleTo()} and id = ?", userId, userId, id) }
        activity.record(userId, Kinds.DELETED, EntityTypes.NOTE, id, say(M.ACT_NOTE_DELETED, note.title), source, shared = note.shared)
        return note
    }

    suspend fun restore(userId: UUID, id: UUID): Boolean =
        db.tx { exec("update notes set deleted_at = null where ${visibleTo()} and id = ?", userId, userId, id) } > 0

    private suspend fun announce(userId: UUID, note: Note) =
        notifier.everyone(userId, NoticeKinds.SHARED, NoticeText(say(M.NOTICE_NOTE_SHARED, notifier.who(userId)), Plain(note.title)), EntityTypes.NOTE, note.id)

    /**
     * Hybrid search: semantic similarity (pgvector) + text (Spanish full-text and trigrams),
     * reordered with Nan's `rerank` model. It includes the notes the family shared.
     * (The full-text index uses the `spanish` configuration; English notes still match through the
     * trigram, `ilike` and semantic paths.)
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
        // The reranker is very strict with synonyms ("taxes" versus "income tax return"):
        // notes that are very close in meaning and the ones containing the literal text are kept too.
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
        fun deriveTitle(content: String, lang: Lang = Lang.DEFAULT): String {
            val firstLine = content.lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotEmpty() } ?: M.DEFAULT_NOTE_TITLE.t(lang)
            return if (firstLine.length <= 60) firstLine else firstLine.take(57).trimEnd() + "..."
        }

        fun normalizeTags(tags: List<String>): List<String> =
            tags.map { it.trim().trimStart('#').lowercase() }.filter { it.isNotEmpty() }.distinct().take(12)
    }
}
