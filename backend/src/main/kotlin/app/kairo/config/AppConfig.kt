package app.kairo.config

import app.kairo.util.Lang
import app.kairo.util.M
import java.util.Base64

/** Configuration read from environment variables. See `.env.example` at the repo root. */
data class AppConfig(
    val port: Int,
    val publicUrl: String,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val nanBaseUrl: String,
    val nanApiKey: String,
    val jwtSecret: String,
    val encryptionKey: ByteArray,
    val ownerEmail: String?,
    val ownerPassword: String?,
    val ownerName: String,
    val defaultTimezone: String,
    val googleClientId: String?,
    val googleClientSecret: String?,
    val ntfyUrl: String?,
    val ntfyTopic: String?,
    val ntfyToken: String?,
    val corsOrigins: List<String>,
    val schedulersEnabled: Boolean,
    /** Allows http:// push endpoints (tests only, or an ntfy on the local network). Https only by default. */
    val pushAllowHttp: Boolean = false,
    /**
     * Language for server text when nobody's preference is known: the owner account created at first start,
     * and `auto` users outside a request. `en` by default (KAIRO_DEFAULT_LANGUAGE=es for Spanish).
     */
    val defaultLanguage: Lang = Lang.DEFAULT,
    /** Apple native push (APNs), optional: .p8 key id, Team ID and the iOS app's bundle id. */
    val apnsKeyId: String? = null,
    val apnsTeamId: String? = null,
    val apnsTopic: String? = null,
    /** PEM content of the .p8 key (APNS_PRIVATE_KEY, accepts literal "\n") or the file path (APNS_PRIVATE_KEY_FILE). */
    val apnsPrivateKey: String? = null,
    val apnsPrivateKeyFile: String? = null,
) {
    val googleEnabled: Boolean get() = !googleClientId.isNullOrBlank() && !googleClientSecret.isNullOrBlank()

    /** All the APNs data is present (the key itself is validated at startup in ApnsService). */
    val apnsConfigured: Boolean
        get() = !apnsKeyId.isNullOrBlank() && !apnsTeamId.isNullOrBlank() && !apnsTopic.isNullOrBlank() &&
            (!apnsPrivateKey.isNullOrBlank() || !apnsPrivateKeyFile.isNullOrBlank())

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun req(name: String): String =
                env[name]?.takeIf { it.isNotBlank() } ?: error("Missing environment variable $name")
            fun opt(name: String): String? = env[name]?.takeIf { it.isNotBlank() }

            val key = encryptionKeyFrom(req("KAIRO_ENCRYPTION_KEY"))
            val jwt = req("KAIRO_JWT_SECRET").trim()
            jwtSecretProblem(jwt)?.let { error(it) }
            val ownerPassword = opt("KAIRO_OWNER_PASSWORD")
            ownerPasswordProblem(ownerPassword)?.let { error(it) }
            val defaultLanguage = opt("KAIRO_DEFAULT_LANGUAGE")?.let {
                Lang.fromCode(it) ?: error("KAIRO_DEFAULT_LANGUAGE must be en or es (got '$it')")
            } ?: Lang.DEFAULT

            return AppConfig(
                port = opt("KAIRO_PORT")?.toInt() ?: 8080,
                publicUrl = (opt("KAIRO_PUBLIC_URL") ?: "http://localhost:8080").trimEnd('/'),
                dbUrl = opt("KAIRO_DB_URL") ?: "jdbc:postgresql://localhost:5432/kairo",
                dbUser = opt("KAIRO_DB_USER") ?: "kairo",
                dbPassword = req("KAIRO_DB_PASSWORD"),
                nanBaseUrl = (opt("NAN_BASE_URL") ?: "https://api.nan.builders/v1").trimEnd('/'),
                nanApiKey = req("NAN_API_KEY"),
                jwtSecret = jwt,
                encryptionKey = key,
                ownerEmail = opt("KAIRO_OWNER_EMAIL"),
                ownerPassword = ownerPassword,
                ownerName = opt("KAIRO_OWNER_NAME") ?: M.DEFAULT_OWNER_NAME.t(defaultLanguage),
                defaultTimezone = opt("KAIRO_TIMEZONE") ?: "America/Bogota",
                googleClientId = opt("GOOGLE_CLIENT_ID"),
                googleClientSecret = opt("GOOGLE_CLIENT_SECRET"),
                ntfyUrl = opt("NTFY_URL")?.trimEnd('/'),
                ntfyTopic = opt("NTFY_TOPIC"),
                ntfyToken = opt("NTFY_TOKEN"),
                corsOrigins = opt("KAIRO_CORS_ORIGINS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                schedulersEnabled = opt("KAIRO_SCHEDULERS")?.toBooleanStrictOrNull() ?: true,
                pushAllowHttp = opt("KAIRO_PUSH_ALLOW_HTTP")?.toBooleanStrictOrNull() ?: false,
                defaultLanguage = defaultLanguage,
                apnsKeyId = opt("APNS_KEY_ID")?.trim(),
                apnsTeamId = opt("APNS_TEAM_ID")?.trim(),
                apnsTopic = opt("APNS_TOPIC")?.trim(),
                apnsPrivateKey = opt("APNS_PRIVATE_KEY")?.replace("\\n", "\n"),
                apnsPrivateKeyFile = opt("APNS_PRIVATE_KEY_FILE")?.trim(),
            )
        }

        /**
         * Example values of `.env.example` (current English ones and the former Spanish ones): if they are still
         * there, anyone who reads the repo knows the secret.
         */
        private val JWT_PLACEHOLDERS = setOf("change-this-to-64-hex-characters", "cambia-esto-por-64-caracteres-hex")
        private val ENCRYPTION_KEY_PLACEHOLDERS = setOf("change-this-to-32-bytes-in-base64=", "cambia-esto-por-32-bytes-en-base64=")
        private val OWNER_PASSWORD_PLACEHOLDERS = setOf("a-long-password-here", "una-contraseña-larga")
        private val PLACEHOLDER_MARKERS = listOf("change-this", "cambia-esto")

        private val HEX = Regex("^[0-9a-fA-F]+$")
        private val BASE64 = Regex("^[A-Za-z0-9+/_-]+=*$")

        private fun looksLikePlaceholder(s: String): Boolean = PLACEHOLDER_MARKERS.any { s.contains(it, ignoreCase = true) }

        /**
         * Why [secret] is not usable as KAIRO_JWT_SECRET, or null if it is. 256 random bits are required:
         * 64 hexadecimal characters (`openssl rand -hex 32`, what scripts/generate-env.sh uses)
         * or 43 in base64 (`openssl rand -base64 32`).
         */
        fun jwtSecretProblem(secret: String): String? {
            val s = secret.trim()
            val fix = "Generate one with: openssl rand -hex 32 (or run scripts/generate-env.sh)."
            if (s in JWT_PLACEHOLDERS || looksLikePlaceholder(s)) {
                return "KAIRO_JWT_SECRET still has the example value from .env.example. $fix"
            }
            val strongHex = HEX.matches(s) && s.length >= 64
            val strongBase64 = !HEX.matches(s) && BASE64.matches(s) && s.trimEnd('=').length >= 43
            if (!strongHex && !strongBase64) {
                return "KAIRO_JWT_SECRET is too short or does not look random: use at least 64 " +
                    "hexadecimal characters or 43 in base64 (32 random bytes). $fix"
            }
            // A random secret of that length uses many different symbols; "aaaa..." or "0000..." does not
            if (s.toSet().size < 10) return "KAIRO_JWT_SECRET does not look random (it repeats very few characters). $fix"
            return null
        }

        /** Decodes KAIRO_ENCRYPTION_KEY (32 bytes in base64) with clear messages if it is not usable. */
        fun encryptionKeyFrom(raw: String): ByteArray {
            val s = raw.trim()
            val fix = "Generate one with: openssl rand -base64 32 (or run scripts/generate-env.sh)."
            if (s in ENCRYPTION_KEY_PLACEHOLDERS || looksLikePlaceholder(s)) {
                error("KAIRO_ENCRYPTION_KEY still has the example value from .env.example. $fix")
            }
            val key = runCatching { Base64.getDecoder().decode(s) }.getOrNull()
                ?: error("KAIRO_ENCRYPTION_KEY is not valid base64. $fix")
            if (key.size != 32) error("KAIRO_ENCRYPTION_KEY must be 32 bytes in base64 (it has ${key.size}). $fix")
            return key
        }

        /** The owner's initial password cannot be the example one (it is only used on first start). */
        fun ownerPasswordProblem(password: String?): String? =
            if (password != null && password.trim() in OWNER_PASSWORD_PLACEHOLDERS) {
                "KAIRO_OWNER_PASSWORD still has the example value from .env.example. Set a password of your own " +
                    "(or run scripts/generate-env.sh). If the owner account already exists, you can delete the variable; " +
                    "and if you signed in with that password, change it in Settings > Security."
            } else null
    }
}
