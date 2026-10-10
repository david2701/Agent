package app.kairo.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** jsonb value. */
@JvmInline value class Jsonb(val json: String)

/** pgvector `vector` value. */
@JvmInline value class Vec(val values: FloatArray) {
    fun literal(): String = values.joinToString(",", "[", "]")
}

/** `text[]` array. */
@JvmInline value class TextArray(val values: List<String>)

/** `uuid[]` array. */
@JvmInline value class UuidArray(val values: List<UUID>)

/** `integer[]` array. */
@JvmInline value class IntArrayParam(val values: List<Int>)

/**
 * Postgres access with plain JDBC. All operations run on Dispatchers.IO.
 * We chose direct JDBC (instead of an ORM) to use pgvector, jsonb and arrays without friction.
 */
class Db(val dataSource: HikariDataSource) {

    suspend fun <T> tx(block: Tx.() -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                val r = Tx(c).block()
                c.commit()
                r
            } catch (e: Throwable) {
                c.rollback()
                throw e
            }
        }
    }

    suspend fun <T> read(block: Tx.() -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { c ->
            c.autoCommit = true
            Tx(c).block()
        }
    }

    fun close() = dataSource.close()

    companion object {
        fun connect(url: String, user: String, password: String, poolSize: Int = 10): Db {
            val cfg = HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = poolSize
                minimumIdle = 1
                poolName = "kairo"
                connectionTimeout = 10_000
            }
            val ds = HikariDataSource(cfg)
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
            return Db(ds)
        }
    }
}

class Tx(val connection: Connection) {

    fun <T> query(sql: String, vararg params: Any?, map: (ResultSet) -> T): List<T> =
        connection.prepareStatement(sql).use { ps ->
            bind(ps, params)
            ps.executeQuery().use { rs ->
                val out = ArrayList<T>()
                while (rs.next()) out += map(rs)
                out
            }
        }

    fun <T> one(sql: String, vararg params: Any?, map: (ResultSet) -> T): T? = query(sql, *params, map = map).firstOrNull()

    fun exec(sql: String, vararg params: Any?): Int =
        connection.prepareStatement(sql).use { ps ->
            bind(ps, params)
            ps.executeUpdate()
        }

    private fun bind(ps: PreparedStatement, params: Array<out Any?>) {
        params.forEachIndexed { i, p ->
            val idx = i + 1
            when (p) {
                null -> ps.setNull(idx, Types.OTHER)
                is String -> ps.setString(idx, p)
                is Int -> ps.setInt(idx, p)
                is Long -> ps.setLong(idx, p)
                is Double -> ps.setDouble(idx, p)
                is Float -> ps.setFloat(idx, p)
                is Boolean -> ps.setBoolean(idx, p)
                is UUID -> ps.setObject(idx, p)
                is Instant -> ps.setObject(idx, OffsetDateTime.ofInstant(p, ZoneOffset.UTC))
                is LocalDate -> ps.setObject(idx, p)
                is ByteArray -> ps.setBytes(idx, p)
                is Jsonb -> ps.setObject(idx, PGobject().apply { type = "jsonb"; value = p.json })
                is Vec -> ps.setObject(idx, PGobject().apply { type = "vector"; value = p.literal() })
                is TextArray -> ps.setArray(idx, connection.createArrayOf("text", p.values.toTypedArray()))
                is UuidArray -> ps.setArray(idx, connection.createArrayOf("uuid", p.values.toTypedArray()))
                is IntArrayParam -> ps.setArray(idx, connection.createArrayOf("integer", p.values.toTypedArray()))
                else -> error("Unsupported parameter type: ${p::class}")
            }
        }
    }
}

// --- Column reading ---

fun ResultSet.uuid(col: String): UUID = getObject(col, UUID::class.java)
fun ResultSet.uuidOrNull(col: String): UUID? = getObject(col)?.let { it as UUID }
fun ResultSet.instant(col: String): Instant = getObject(col, OffsetDateTime::class.java).toInstant()
fun ResultSet.instantOrNull(col: String): Instant? = getObject(col, OffsetDateTime::class.java)?.toInstant()
fun ResultSet.localDateOrNull(col: String): LocalDate? = getObject(col, LocalDate::class.java)
fun ResultSet.str(col: String): String = getString(col) ?: ""
fun ResultSet.strOrNull(col: String): String? = getString(col)
fun ResultSet.textList(col: String): List<String> =
    (getArray(col)?.array as? Array<*>)?.mapNotNull { it as? String } ?: emptyList()
fun ResultSet.uuidList(col: String): List<UUID> =
    (getArray(col)?.array as? Array<*>)?.mapNotNull { it as? UUID } ?: emptyList()
fun ResultSet.intList(col: String): List<Int> =
    (getArray(col)?.array as? Array<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
fun ResultSet.jsonOrNull(col: String): String? = getString(col)
