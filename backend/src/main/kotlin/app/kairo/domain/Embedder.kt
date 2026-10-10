package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.Vec
import app.kairo.nan.NanClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID

/** Computes embeddings (qwen3-embedding, 1024 dims) in the background for notes and memories. */
class Embedder(private val nan: NanClient, private val db: Db, private val scope: CoroutineScope) {
    private val log = LoggerFactory.getLogger(Embedder::class.java)

    suspend fun embed(text: String): FloatArray? = runCatching { nan.embed(listOf(text)).firstOrNull() }
        .onFailure { log.warn("Failed to compute embedding: {}", it.message) }
        .getOrNull()

    /**
     * Embedding of a query. qwen3-embedding separates what is relevant from what is not much better
     * when the query carries an instruction (documents are stored without it).
     */
    suspend fun embedQuery(query: String, instruction: String = NOTES_INSTRUCTION): FloatArray? = embed("Instruct: $instruction\nQuery: $query")

    fun scheduleNote(id: UUID, title: String, content: String) = schedule("notes", id, "$title\n$content")

    fun scheduleMemory(id: UUID, content: String) = schedule("memories", id, content)

    private fun schedule(table: String, id: UUID, text: String) {
        if (text.isBlank()) return
        scope.launch {
            val v = embed(text) ?: return@launch
            db.tx { exec("update $table set embedding = ? where id = ?", Vec(v), id) }
        }
    }

    /** Fills in missing embeddings (for example if Nan did not respond when the note was created). */
    suspend fun backfill() {
        for (table in listOf("notes", "memories")) {
            val rows = db.read {
                val textExpr = if (table == "notes") "title || E'\\n' || content" else "content"
                query("select id, $textExpr as t from $table where embedding is null and deleted_at is null limit 200") {
                    it.getObject("id", UUID::class.java) to (it.getString("t") ?: "")
                }
            }
            rows.chunked(16).forEach { chunk ->
                val vectors = runCatching { nan.embed(chunk.map { it.second.ifBlank { " " } }) }.getOrNull() ?: return
                db.tx { chunk.zip(vectors).forEach { (row, v) -> exec("update $table set embedding = ? where id = ?", Vec(v), row.first) } }
            }
        }
    }

    companion object {
        // Instructions are in English (what qwen3-embedding was trained on); the model is multilingual.
        const val NOTES_INSTRUCTION = "Given a query, find the personal notes that answer it or are about the topic"
        const val MEMORY_INSTRUCTION = "Given a user query, find the relevant personal facts about the user"
    }
}
