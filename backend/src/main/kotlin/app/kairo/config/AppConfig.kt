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
) {
    val googleEnabled: Boolean get() = !googleClientId.isNullOrBlank() && !googleClientSecret.isNullOrBlank()

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            fun req(name: String): String =
                env[name]?.takeIf { it.isNotBlank() } ?: error("Falta la variable de entorno $name")
            fun opt(name: String): String? = env[name]?.takeIf { it.isNotBlank() }

            val key = Base64.getDecoder().decode(req("KAIRO_ENCRYPTION_KEY"))
            require(key.size == 32) { "KAIRO_ENCRYPTION_KEY debe ser 32 bytes en base64 (openssl rand -base64 32)" }
            val jwt = req("KAIRO_JWT_SECRET")
            require(jwt.length >= 32) { "KAIRO_JWT_SECRET debe tener al menos 32 caracteres" }

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
                ownerPassword = opt("KAIRO_OWNER_PASSWORD"),
                ownerName = opt("KAIRO_OWNER_NAME") ?: "Tú",
                defaultTimezone = opt("KAIRO_TIMEZONE") ?: "America/Bogota",
                googleClientId = opt("GOOGLE_CLIENT_ID"),
                googleClientSecret = opt("GOOGLE_CLIENT_SECRET"),
                ntfyUrl = opt("NTFY_URL")?.trimEnd('/'),
                ntfyTopic = opt("NTFY_TOPIC"),
                ntfyToken = opt("NTFY_TOKEN"),
                corsOrigins = opt("KAIRO_CORS_ORIGINS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                schedulersEnabled = opt("KAIRO_SCHEDULERS")?.toBooleanStrictOrNull() ?: true,
            )
        }
    }
}
