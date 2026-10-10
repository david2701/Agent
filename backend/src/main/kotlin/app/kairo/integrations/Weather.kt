package app.kairo.integrations

import app.kairo.util.proxyFromEnv
import app.kairo.util.KairoJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class WeatherDay(val date: String, val min: Double, val max: Double, val rainChance: Int?, val description: String)

@Serializable
data class WeatherReport(
    val place: String,
    val temperature: Double,
    val feelsLike: Double?,
    val description: String,
    val days: List<WeatherDay>,
)

@Serializable
data class GeoPlace(val name: String, val admin: String?, val country: String?, val latitude: Double, val longitude: Double, val timezone: String?)

/** Clima con Open-Meteo (gratis, sin clave). Caché de 20 minutos. */
class WeatherService {
    private val http = HttpClient(CIO) {
        engine { proxyFromEnv()?.let { proxy = it } }
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        expectSuccess = false
    }
    private val cache = ConcurrentHashMap<String, Pair<Long, WeatherReport>>()

    suspend fun forecast(lat: Double, lon: Double, placeName: String, days: Int = 3): WeatherReport? {
        val key = "%.3f,%.3f,%d".format(lat, lon, days)
        cache[key]?.let { (at, r) -> if (System.currentTimeMillis() - at < 20 * 60_000) return r }
        val resp = runCatching {
            http.get("https://api.open-meteo.com/v1/forecast") {
                parameter("latitude", lat)
                parameter("longitude", lon)
                parameter("current", "temperature_2m,apparent_temperature,weather_code")
                parameter("daily", "temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code")
                parameter("timezone", "auto")
                parameter("forecast_days", days.coerceIn(1, 7))
            }
        }.getOrNull() ?: return null
        if (!resp.status.isSuccess()) return null
        val root = KairoJson.parseToJsonElement(resp.bodyAsText()).jsonObject
        val current = root["current"]?.jsonObject ?: return null
        val daily = root["daily"]?.jsonObject
        fun arr(name: String): JsonArray? = daily?.get(name) as? JsonArray
        val dates = arr("time")?.map { it.jsonPrimitive.content } ?: emptyList()
        val report = WeatherReport(
            place = placeName,
            temperature = current["temperature_2m"]!!.jsonPrimitive.content.toDouble(),
            feelsLike = current["apparent_temperature"]?.jsonPrimitive?.content?.toDoubleOrNull(),
            description = describe(current["weather_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0),
            days = dates.mapIndexed { i, d ->
                WeatherDay(
                    date = d,
                    min = arr("temperature_2m_min")!![i].jsonPrimitive.content.toDouble(),
                    max = arr("temperature_2m_max")!![i].jsonPrimitive.content.toDouble(),
                    rainChance = arr("precipitation_probability_max")?.get(i)?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt(),
                    description = describe(arr("weather_code")?.get(i)?.jsonPrimitive?.content?.toIntOrNull() ?: 0),
                )
            },
        )
        cache[key] = System.currentTimeMillis() to report
        return report
    }

    suspend fun geocode(query: String): List<GeoPlace> {
        val resp = runCatching {
            http.get("https://geocoding-api.open-meteo.com/v1/search") {
                parameter("name", query)
                parameter("count", 5)
                parameter("language", "es")
            }
        }.getOrNull() ?: return emptyList()
        if (!resp.status.isSuccess()) return emptyList()
        val results = KairoJson.parseToJsonElement(resp.bodyAsText()).jsonObject["results"] as? JsonArray ?: return emptyList()
        return results.map { it.jsonObject }.map { o: JsonObject ->
            GeoPlace(
                name = o["name"]!!.jsonPrimitive.content,
                admin = o["admin1"]?.jsonPrimitive?.content,
                country = o["country"]?.jsonPrimitive?.content,
                latitude = o["latitude"]!!.jsonPrimitive.content.toDouble(),
                longitude = o["longitude"]!!.jsonPrimitive.content.toDouble(),
                timezone = o["timezone"]?.jsonPrimitive?.content,
            )
        }
    }

    companion object {
        /** Códigos WMO a texto en español. */
        fun describe(code: Int): String = when (code) {
            0 -> "despejado"
            1 -> "mayormente despejado"
            2 -> "parcialmente nublado"
            3 -> "nublado"
            45, 48 -> "niebla"
            51, 53, 55 -> "llovizna"
            56, 57 -> "llovizna helada"
            61 -> "lluvia ligera"
            63 -> "lluvia"
            65 -> "lluvia fuerte"
            66, 67 -> "lluvia helada"
            71, 73, 75, 77 -> "nieve"
            80 -> "chubascos ligeros"
            81 -> "chubascos"
            82 -> "chubascos fuertes"
            85, 86 -> "chubascos de nieve"
            95 -> "tormenta"
            96, 99 -> "tormenta con granizo"
            else -> "variable"
        }
    }
}
