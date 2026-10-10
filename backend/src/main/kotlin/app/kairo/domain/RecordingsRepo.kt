package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.Jsonb
import app.kairo.db.instant
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.db.uuid
import app.kairo.db.uuidOrNull
import app.kairo.util.KairoJson
import app.kairo.util.M
import app.kairo.util.say
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.sql.ResultSet
import java.util.UUID

/** Recordings (meetings, long voice notes) transcribed in segments. */
class RecordingsRepo(private val db: Db, private val activity: ActivityRepo) {

    private val select = "select r.*, (select count(*) from recording_segments s where s.recording_id = r.id) as segs from recordings r"

    suspend fun list(userId: UUID): List<Recording> = db.read {
        query("$select where r.user_id = ? and r.deleted_at is null order by r.created_at desc limit 200", userId) { map(it, withTranscript = false) }
    }

    suspend fun get(userId: UUID, id: UUID): Recording? = db.read {
        one("$select where r.user_id = ? and r.id = ? and r.deleted_at is null", userId, id) { map(it, withTranscript = true) }
    }

    suspend fun create(userId: UUID, title: String, source: String, defaultTitle: String = M.DEFAULT_RECORDING_TITLE.en): Recording {
        val id = db.tx { one("insert into recordings(user_id, title) values (?, ?) returning id", userId, title.ifBlank { defaultTitle }) { it.uuid("id") }!! }
        activity.record(userId, Kinds.CREATED, EntityTypes.RECORDING, id, say(M.ACT_RECORDING_STARTED), source)
        return get(userId, id)!!
    }

    suspend fun addSegment(recordingId: UUID, seq: Int, durationS: Int, transcript: String) = db.tx {
        exec(
            """insert into recording_segments(recording_id, seq, duration_s, transcript) values (?, ?, ?, ?)
               on conflict (recording_id, seq) do update set transcript = excluded.transcript, duration_s = excluded.duration_s""",
            recordingId, seq, durationS, transcript,
        )
        exec(
            """update recordings set
               duration_s = (select coalesce(sum(duration_s), 0) from recording_segments where recording_id = ?),
               transcript = (select string_agg(transcript, ' ' order by seq) from recording_segments where recording_id = ?),
               updated_at = now() where id = ?""",
            recordingId, recordingId, recordingId,
        )
    }

    suspend fun setStatus(id: UUID, status: String, error: String? = null) = db.tx {
        exec("update recordings set status = ?, error = ?, updated_at = now() where id = ?", status, error, id)
    }

    suspend fun setResult(id: UUID, title: String, summary: String, keyPoints: List<String>, actionItems: List<String>, noteId: UUID?) = db.tx {
        exec(
            """update recordings set title = ?, summary = ?, key_points = ?, action_items = ?, note_id = ?, status = 'done', error = null,
               updated_at = now() where id = ?""",
            title, summary,
            Jsonb(KairoJson.encodeToString(ListSerializer(String.serializer()), keyPoints)),
            Jsonb(KairoJson.encodeToString(ListSerializer(String.serializer()), actionItems)),
            noteId, id,
        )
    }

    suspend fun delete(userId: UUID, id: UUID): Boolean = db.tx {
        exec("update recordings set deleted_at = now() where user_id = ? and id = ?", userId, id) > 0
    }

    private fun map(rs: ResultSet, withTranscript: Boolean): Recording {
        fun list(col: String): List<String> = rs.strOrNull(col)?.let { KairoJson.decodeFromString(ListSerializer(String.serializer()), it) } ?: emptyList()
        return Recording(
            id = rs.uuid("id"),
            title = rs.str("title"),
            status = rs.str("status"),
            durationS = rs.getInt("duration_s"),
            transcript = if (withTranscript) rs.str("transcript") else rs.str("transcript").take(280),
            summary = rs.str("summary"),
            keyPoints = list("key_points"),
            actionItems = list("action_items"),
            noteId = rs.uuidOrNull("note_id"),
            error = rs.strOrNull("error"),
            segments = rs.getInt("segs"),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.instant("updated_at"),
        )
    }
}
