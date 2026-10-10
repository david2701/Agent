package app.kairo.auth

import java.time.Duration
import java.time.Instant

/**
 * Failure counter over a sliding window (e.g. "20 failed pairing codes in 5 minutes").
 * It only keeps the [limit] most recent failures: it does not grow even if thousands of attempts arrive.
 */
class FailureWindow(private val limit: Int, private val windowMs: Long) {
    private val stamps = ArrayDeque<Long>()

    /** Records a failure and returns how many are inside the window (counting this one). */
    @Synchronized
    fun record(now: Long): Int {
        prune(now)
        stamps.addLast(now)
        while (stamps.size > limit) stamps.removeFirst()
        return stamps.size
    }

    /** Was the limit reached inside the window? */
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
 * Failures per key (login email). With [limit] failures inside the window the key stays blocked until the
 * oldest one leaves the window. It keeps at most [maxKeys] keys: when full, it drops the one that has gone
 * longest without failing.
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
    /** A device that is not used for 90 days loses its session (same as the browser cookie). */
    val MAX_IDLE: Duration = Duration.ofDays(90)

    fun idleExpired(lastSeenAt: Instant, now: Instant, maxIdle: Duration = MAX_IDLE): Boolean =
        lastSeenAt.isBefore(now.minus(maxIdle))

    /**
     * An already rotated refresh token that shows up again after the grace period is a sign of theft:
     * either someone else used it, or someone else is using it now. Within the grace period it is a normal
     * retry (two tabs, the network).
     */
    fun reuseIsSuspicious(rotatedAt: Instant?, now: Instant, graceMs: Long): Boolean =
        rotatedAt == null || now.toEpochMilli() - rotatedAt.toEpochMilli() > graceMs
}

/**
 * Key of the per-client rate limit: the IP as is for IPv4 and the /64 prefix for IPv6
 * (every home connection gets a whole /64 and could rotate addresses inside it).
 * It does not resolve names: if it is not a literal IP, it returns the lowercase text.
 */
fun rateLimitKey(address: String): String {
    var a = address.trim().lowercase()
    if (a.startsWith("[")) a = a.substringAfter('[').substringBefore(']')
    a = a.substringBefore('%')
    if (!a.contains(':')) return a
    val hextets = expandIpv6(a) ?: return a
    // ::ffff:a.b.c.d (mapped IPv4) is an IPv4
    if (hextets.take(5).all { it == 0 } && hextets[5] == 0xffff) {
        return "${hextets[6] shr 8}.${hextets[6] and 0xff}.${hextets[7] shr 8}.${hextets[7] and 0xff}"
    }
    return hextets.take(4).joinToString(":") { Integer.toHexString(it) } + "::/64"
}

/** Expands a literal IPv6 into its 8 groups of 16 bits, or null if it is not valid. */
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
    // IPv4 embedded at the end (::ffff:1.2.3.4, 64:ff9b::1.2.3.4): it takes the last two groups
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
            // "::" stands for at least one group of zeros
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
