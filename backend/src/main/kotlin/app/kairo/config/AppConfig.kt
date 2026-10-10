package app.kairo.config

import java.util.Base64

/** Configuración leída de variables de entorno. Ver `.env.example` en la raíz del repo. */
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
    /** Permite endpoints de push http:// (sólo pruebas o un ntfy en la red local). Por defecto, sólo https. */
    val pushAllowHttp: Boolean = false,
    /** Push nativo de Apple (APNs), opcional: id de la clave .p8, Team ID y bundle id de la app de iOS. */
    val apnsKeyId: String? = null,
    val apnsTeamId: String? = null,
    val apnsTopic: String? = null,
    /** Contenido PEM de la clave .p8 (APNS_PRIVATE_KEY, admite "\n" literales) o ruta del archivo (APNS_PRIVATE_KEY_FILE). */
    val apnsPrivateKey: String? = null,
    val apnsPrivateKeyFile: String? = null,
) {
    val googleEnabled: Boolean get() = !googleClientId.isNullOrBlank() && !googleClientSecret.isNullOrBlank()

    /** Están todos los datos de APNs (la clave se valida al arrancar en ApnsService). */
    val apnsConfigured: Boolean
        get() = !apnsKeyId.isNullOrBlank() && !apnsTeamId.isNullOrBlank() && !apnsTopic.isNullOrBlank() &&
            (!apnsPrivateKey.isNullOrBlank() || !apnsPrivateKeyFile.isNullOrBlank())

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun req(name: String): String =
                env[name]?.takeIf { it.isNotBlank() } ?: error("Falta la variable de entorno $name")
            fun opt(name: String): String? = env[name]?.takeIf { it.isNotBlank() }

            val key = encryptionKeyFrom(req("KAIRO_ENCRYPTION_KEY"))
            val jwt = req("KAIRO_JWT_SECRET").trim()
            jwtSecretProblem(jwt)?.let { error(it) }
            val ownerPassword = opt("KAIRO_OWNER_PASSWORD")
            ownerPasswordProblem(ownerPassword)?.let { error(it) }

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
                ownerName = opt("KAIRO_OWNER_NAME") ?: "Tú",
                defaultTimezone = opt("KAIRO_TIMEZONE") ?: "America/Bogota",
                googleClientId = opt("GOOGLE_CLIENT_ID"),
                googleClientSecret = opt("GOOGLE_CLIENT_SECRET"),
                ntfyUrl = opt("NTFY_URL")?.trimEnd('/'),
                ntfyTopic = opt("NTFY_TOPIC"),
                ntfyToken = opt("NTFY_TOKEN"),
                corsOrigins = opt("KAIRO_CORS_ORIGINS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                schedulersEnabled = opt("KAIRO_SCHEDULERS")?.toBooleanStrictOrNull() ?: true,
                pushAllowHttp = opt("KAIRO_PUSH_ALLOW_HTTP")?.toBooleanStrictOrNull() ?: false,
                apnsKeyId = opt("APNS_KEY_ID")?.trim(),
                apnsTeamId = opt("APNS_TEAM_ID")?.trim(),
                apnsTopic = opt("APNS_TOPIC")?.trim(),
                apnsPrivateKey = opt("APNS_PRIVATE_KEY")?.replace("\\n", "\n"),
                apnsPrivateKeyFile = opt("APNS_PRIVATE_KEY_FILE")?.trim(),
            )
        }

        /** Valores de ejemplo de `.env.example`: si siguen ahí, cualquiera que lea el repo conoce el secreto. */
        private val JWT_PLACEHOLDERS = setOf("cambia-esto-por-64-caracteres-hex")
        private val ENCRYPTION_KEY_PLACEHOLDERS = setOf("cambia-esto-por-32-bytes-en-base64=")
        private val OWNER_PASSWORD_PLACEHOLDERS = setOf("una-contraseña-larga")

        private val HEX = Regex("^[0-9a-fA-F]+$")
        private val BASE64 = Regex("^[A-Za-z0-9+/_-]+=*$")

        /**
         * Motivo por el que [secret] no sirve como KAIRO_JWT_SECRET, o null si sirve. Se exigen 256 bits
         * aleatorios: 64 caracteres hexadecimales (`openssl rand -hex 32`, lo que usa scripts/generate-env.sh)
         * o 43 en base64 (`openssl rand -base64 32`).
         */
        fun jwtSecretProblem(secret: String): String? {
            val s = secret.trim()
            val fix = "Genera uno con: openssl rand -hex 32 (o ejecuta scripts/generate-env.sh)."
            if (s in JWT_PLACEHOLDERS || s.contains("cambia", ignoreCase = true)) {
                return "KAIRO_JWT_SECRET todavía tiene el valor de ejemplo de .env.example. $fix"
            }
            val strongHex = HEX.matches(s) && s.length >= 64
            val strongBase64 = !HEX.matches(s) && BASE64.matches(s) && s.trimEnd('=').length >= 43
            if (!strongHex && !strongBase64) {
                return "KAIRO_JWT_SECRET es demasiado corto o no parece aleatorio: usa al menos 64 caracteres " +
                    "hexadecimales o 43 en base64 (32 bytes aleatorios). $fix"
            }
            // Un secreto aleatorio de ese largo usa muchos símbolos distintos; "aaaa…" o "0000…" no lo es
            if (s.toSet().size < 10) return "KAIRO_JWT_SECRET no parece aleatorio (repite muy pocos caracteres). $fix"
            return null
        }

        /** Decodifica KAIRO_ENCRYPTION_KEY (32 bytes en base64) con mensajes claros si no sirve. */
        fun encryptionKeyFrom(raw: String): ByteArray {
            val s = raw.trim()
            val fix = "Genera una con: openssl rand -base64 32 (o ejecuta scripts/generate-env.sh)."
            if (s in ENCRYPTION_KEY_PLACEHOLDERS || s.contains("cambia", ignoreCase = true)) {
                error("KAIRO_ENCRYPTION_KEY todavía tiene el valor de ejemplo de .env.example. $fix")
            }
            val key = runCatching { Base64.getDecoder().decode(s) }.getOrNull()
                ?: error("KAIRO_ENCRYPTION_KEY no es base64 válido. $fix")
            if (key.size != 32) error("KAIRO_ENCRYPTION_KEY debe ser de 32 bytes en base64 (tiene ${key.size}). $fix")
            return key
        }

        /** La contraseña inicial del propietario no puede ser la de ejemplo (sólo se usa en el primer arranque). */
        fun ownerPasswordProblem(password: String?): String? =
            if (password != null && password.trim() in OWNER_PASSWORD_PLACEHOLDERS) {
                "KAIRO_OWNER_PASSWORD todavía tiene el valor de ejemplo de .env.example. Pon una contraseña propia " +
                    "(o ejecuta scripts/generate-env.sh). Si la cuenta del propietario ya existe, puedes borrar la variable; " +
                    "y si entraste con esa contraseña, cámbiala en Ajustes > Seguridad."
            } else null
    }
}
