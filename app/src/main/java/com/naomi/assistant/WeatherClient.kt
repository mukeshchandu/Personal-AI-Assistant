package com.naomi.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Tiny weather lookup via open-meteo.com — free, no API key, no account.
 * Geocodes a city name, then fetches the current temperature + condition,
 * and returns one spoken-style sentence. Network-bound, so call off the main thread.
 */
class WeatherClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun forecast(city: String): String = withContext(Dispatchers.IO) {
        if (city.isBlank()) return@withContext "Which city's weather would you like?"
        try {
            val geo = get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=${enc(city)}")
                ?: return@withContext "I couldn't reach the weather service."
            val results = JSONObject(geo).optJSONArray("results")
            if (results == null || results.length() == 0) {
                return@withContext "I couldn't find a place called $city."
            }
            val place = results.getJSONObject(0)
            val lat = place.optDouble("latitude")
            val lon = place.optDouble("longitude")
            val name = place.optString("name", city)

            val wx = get("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code&latitude=$lat&longitude=$lon")
                ?: return@withContext "I couldn't reach the weather service."
            val cur = JSONObject(wx).optJSONObject("current")
                ?: return@withContext "I couldn't get the weather for $name."
            val temp = cur.optDouble("temperature_2m")
            val code = cur.optInt("weather_code")
            "It's ${temp.toInt()} degrees and ${describe(code)} in $name."
        } catch (e: java.net.UnknownHostException) {
            "I'm offline, so I can't check the weather right now."
        } catch (e: Exception) {
            "I couldn't get the weather: ${e.message}"
        }
    }

    private fun get(url: String): String? =
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** WMO weather codes → a plain spoken word. */
    private fun describe(code: Int): String = when (code) {
        0 -> "clear"
        1, 2 -> "mostly clear"
        3 -> "cloudy"
        45, 48 -> "foggy"
        51, 53, 55, 56, 57 -> "drizzly"
        61, 63, 65, 66, 67, 80, 81, 82 -> "rainy"
        71, 73, 75, 77, 85, 86 -> "snowy"
        95, 96, 99 -> "stormy"
        else -> "mild"
    }
}
