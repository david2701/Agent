package app.kairo.domain

import app.kairo.db.Db
import app.kairo.db.str
import app.kairo.db.strOrNull
import app.kairo.util.Crypto
import java.time.Instant
import java.util.UUID

class StoredFile(val id: UUID, val userId: UUID, val contentType: String, val name: String?, val bytes: ByteArray)

/** Small files (shared or generated images) stored in Postgres and served with a signed URL. */
class FilesRepo(private val db: Db, private val crypto: Crypto) {

    suspend fun store(userId: UUID, bytes: ByteArray, contentType: String, name: String? = null): UUID = db.tx {
        one(
            "insert into files(user_id, content_type, name, bytes) values (?, ?, ?, ?) returning id",
            userId, contentType, name, bytes,
        ) { it.getObject("id", UUID::class.java) }!!
    }

    suspend fun get(id: UUID): StoredFile? = db.read {
        one("select id, user_id, content_type, name, bytes from files where id = ?", id) {
            StoredFile(it.getObject("id", UUID::class.java), it.getObject("user_id", UUID::class.java), it.str("content_type"), it.strOrNull("name"), it.getBytes("bytes"))
        }
    }

    /** Relative URL valid for 30 days; clients prepend the server's base URL. */
    fun signedUrl(id: UUID, ttlSeconds: Long = 30L * 24 * 3600): String {
        val exp = Instant.now().epochSecond + ttlSeconds
        return "/api/files/$id?exp=$exp&sig=${crypto.sign("$id:$exp")}"
    }

    fun verify(id: UUID, exp: Long, sig: String): Boolean =
        exp >= Instant.now().epochSecond && crypto.verify("$id:$exp", sig)

    fun ref(id: UUID, contentType: String? = null) = FileRef(id, signedUrl(id), contentType)
}
