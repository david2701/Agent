package app.kairo.auth

import java.time.Duration
import java.time.Instant

/**
 * Contador de fallos en una ventana deslizante (p. ej. "20 códigos de vinculación fallidos en 5 minutos").
 * Sólo guarda los [limit] fallos más recientes: no crece aunque lleguen miles de intentos.
 */
class FailureWindow(private val limit: Int, private val windowMs: Long) {
    private val stamps = ArrayDeque<Long>()

    /** Anota un fallo y devuelve cuántos hay dentro de la ventana (contando éste). */
    @Synchronized
    fun record(now: Long): Int {
        prune(now)
        stamps.addLast(now)
        while (stamps.size > limit) stamps.removeFirst()
        return stamps.size
    }

    /** ¿Se alcanzó el límite dentro de la ventana? */
    @Synchronized
    fun reached(now: Long): Boolean {
        prune(now)
        return stamps.size >= limit
    }

    @Synchronized
    fun clear() = stamps.clear()

    private fun prune(now: Long) {
        while (stamps.isNotEmpty() && stamps.first() <= now - windowMs) stamps.removeFirst()
    }
}

/**
 * Fallos por clave (correo de inicio de sesión). Con [limit] fallos dentro de la ventana la clave queda
 * bloqueada hasta que el más antiguo salga de la ventana. Guarda como mucho [maxKeys] claves: si se llena,
 * descarta la que lleva más tiempo sin fallar.
 */
class KeyedFailureCounter(private val limit: Int, private val windowMs: Long, private val maxKeys: Int = 10_000) {
    private val windows = object : LinkedHashMap<String, FailureWindow>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FailureWindow>?): Boolean = size > maxKeys
    }

    @Synchronized
    fun record(key: String, now: Long): Int = windows.getOrPut(key) { FailureWindow(limit, windowMs) }.record(now)

    @Synchronized
    fun blocked(key: String, now: Long): Boolean = windows[key]?.reached(now) ?: false

    @Synchronized
    fun reset(key: String) {
        windows.remove(key)
    }

    @Synchronized
    fun size(): Int = windows.size
}

object SessionPolicy {
    /** Un dispositivo que no se usa en 90 días pierde la sesión (igual que la cookie del navegador). */
    val MAX_IDLE: Duration = Duration.ofDays(90)

    fun idleExpired(lastSeenAt: Instant, now: Instant, maxIdle: Duration = MAX_IDLE): Boolean =
        lastSeenAt.isBefore(now.minus(maxIdle))

    /**
     * Un refresh token ya rotado que vuelve a aparecer pasada la gracia es una señal de robo:
     * o lo usó otro, o lo usa otro ahora. Dentro de la gracia es un reintento normal (dos pestañas, la red).
     */
    fun reuseIsSuspicious(rotatedAt: Instant?, now: Instant, graceMs: Long): Boolean =
        rotatedAt == null || now.toEpochMilli() - rotatedAt.toEpochMilli() > graceMs
}

/**
 * Clave del límite de peticiones por cliente: la IP tal cual para IPv4 y el prefijo /64 para IPv6
 * (cada conexión doméstica recibe un /64 entero y podría rotar direcciones dentro de él).
 * No resuelve nombres: si no es una IP literal, devuelve el texto en minúsculas.
 */
fun rateLimitKey(address: String): String {
    var a = address.trim().lowercase()
    if (a.startsWith("[")) a = a.substringAfter('[').substringBefore(']')
    a = a.substringBefore('%')
    if (!a.contains(':')) return a
    val hextets = expandIpv6(a) ?: return a
    // ::ffff:a.b.c.d (IPv4 mapeada) es una IPv4
    if (hextets.take(5).all { it == 0 } && hextets[5] == 0xffff) {
        return "${hextets[6] shr 8}.${hextets[6] and 0xff}.${hextets[7] shr 8}.${hextets[7] and 0xff}"
    }
    return hextets.take(4).joinToString(":") { Integer.toHexString(it) } + "::/64"
}

/** Expande una IPv6 literal a sus 8 grupos de 16 bits, o null si no es válida. */
internal fun expandIpv6(raw: String): List<Int>? {
    fun isHex(g: String) = g.length in 1..4 && g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    fun groups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        return part.split(':').map { g -> if (isHex(g)) g.toInt(16) else return null }
    }

    var s = raw
    var tail = emptyList<Int>()
    val lastColon = s.lastIndexOf(':')
    if (lastColon < 0) return null
    // IPv4 incrustada al final (::ffff:1.2.3.4, 64:ff9b::1.2.3.4): ocupa los dos últimos grupos
    val last = s.substring(lastColon + 1)
    if ('.' in last) {
        val b = parseIpv4(last) ?: return null
        tail = listOf((b[0] shl 8) or b[1], (b[2] shl 8) or b[3])
        s = s.substring(0, lastColon + 1)
        if (!s.endsWith("::")) s = s.dropLast(1)
    }
    val needed = 8 - tail.size
    val parts = s.split("::")
    val expanded = when (parts.size) {
        1 -> groups(parts[0])?.takeIf { it.size == needed } ?: return null
        2 -> {
            val head = groups(parts[0]) ?: return null
            val body = groups(parts[1]) ?: return null
            // "::" vale al menos un grupo de ceros
            if (head.size + body.size > needed - 1) return null
            head + List(needed - head.size - body.size) { 0 } + body
        }
        else -> return null
    }
    return expanded + tail
}

private fun parseIpv4(s: String): List<Int>? {
    val p = s.split('.')
    if (p.size != 4) return null
    return p.map { o -> if (o.length in 1..3 && o.all(Char::isDigit)) o.toInt().takeIf { it <= 255 } ?: return null else return null }
}
