package app.kairo.integrations

import app.kairo.util.Lang
import app.kairo.util.M
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

/** Weather from Open-Meteo (free, no key). 20-minute cache. */
class WeatherService {
    private val http = HttpClient(CIO) {
        engine { proxyFromEnv()?.let { proxy = it } }
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        expectSuccess = false
    }
    private val cache = ConcurrentHashMap<String, Pair<Long, WeatherReport>>()

    /** Forecast with the weather descriptions written in [lang]. */
    suspend fun forecast(lat: Double, lon: Double, placeName: String, days: Int = 3, lang: Lang = Lang.DEFAULT): WeatherReport? {
        val key = "%.3f,%.3f,%d,%s".format(lat, lon, days, lang.code)
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
            description = describe(current["weather_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0, lang),
            days = dates.mapIndexed { i, d ->
                WeatherDay(
                    date = d,
                    min = arr("temperature_2m_min")!![i].jsonPrimitive.content.toDouble(),
                    max = arr("temperature_2m_max")!![i].jsonPrimitive.content.toDouble(),
                    rainChance = arr("precipitation_probability_max")?.get(i)?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt(),
                    description = describe(arr("weather_code")?.get(i)?.jsonPrimitive?.content?.toIntOrNull() ?: 0, lang),
                )
            },
        )
        cache[key] = System.currentTimeMillis() to report
        return report
    }

    /** Searches places by name; the results (names, regions) come in [lang]. */
    suspend fun geocode(query: String, lang: Lang = Lang.DEFAULT): List<GeoPlace> {
        val resp = runCatching {
            http.get("https://geocoding-api.open-meteo.com/v1/search") {
                parameter("name", query)
                parameter("count", 5)
                parameter("language", lang.code)
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
        /** WMO weather codes as text in [lang]. */
        fun describe(code: Int, lang: Lang = Lang.DEFAULT): String = when (code) {
            0 -> M.WX_CLEAR
            1 -> M.WX_MOSTLY_CLEAR
            2 -> M.WX_PARTLY_CLOUDY
            3 -> M.WX_OVERCAST
            45, 48 -> M.WX_FOG
            51, 53, 55 -> M.WX_DRIZZLE
            56, 57 -> M.WX_FREEZING_DRIZZLE
            61 -> M.WX_LIGHT_RAIN
            63 -> M.WX_RAIN
            65 -> M.WX_HEAVY_RAIN
            66, 67 -> M.WX_FREEZING_RAIN
            71, 73, 75, 77 -> M.WX_SNOW
            80 -> M.WX_LIGHT_SHOWERS
            81 -> M.WX_SHOWERS
            82 -> M.WX_HEAVY_SHOWERS
            85, 86 -> M.WX_SNOW_SHOWERS
            95 -> M.WX_THUNDERSTORM
            96, 99 -> M.WX_HAIL_STORM
            else -> M.WX_VARIABLE
        }.t(lang)
    }
}
