package com.uberanalyzer.settings

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

class SupabasePointSync {
    companion object {
        private const val BASE_URL = "https://mctdywrbqdxxqyrpidky.supabase.co"
        private const val ANON_KEY = "sb_publishable_pN-Y9kGeka2BYm7gCO7PYw_HTRreHTl"
        private const val TABLE = "interest_points"
        private const val USER_ID = "local-device"
    }

    fun upload(json: String) {
        Thread {
            try {
                val source = JSONArray(json)
                val rows = JSONArray()
                for (i in 0 until source.length()) {
                    val p = source.optJSONObject(i) ?: continue
                    rows.put(JSONObject().apply {
                        put("id", p.optString("id", "$"+"USER_ID-"+"$"+"i"))
                        put("user_id", USER_ID)
                        put("name", p.optString("name", "Ponto"))
                        put("address", p.optString("address", ""))
                        put("icon", p.optString("icon", "📍"))
                        put("visible", p.optBoolean("visible", true))
                    })
                }
                request("POST", rows.toString(), "?on_conflict=id")
            } catch (_: Exception) {}
        }.start()
    }

    private fun request(method: String, body: String, suffix: String) {
        val conn = (URL("$"+"BASE_URL/rest/v1/"+"$"+"TABLE"+"$"+"suffix").openConnection() as HttpURLConnection)
        conn.requestMethod = method
        conn.setRequestProperty("apikey", ANON_KEY)
        conn.setRequestProperty("Authorization", "Bearer "+"$"+"ANON_KEY")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Prefer", "resolution=merge-duplicates")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        conn.inputStream.close()
        conn.disconnect()
    }
}
