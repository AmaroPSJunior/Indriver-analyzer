package com.uberanalyzer.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class AppRelease(val tag: String, val url: String, val size: Long, val digest: String)

internal object ReleaseClient {
    fun latest(): AppRelease {
        val connection = URL(ReleasePolicy.API_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "inDrive-Mapa-Updater")
            when (connection.responseCode) {
                200 -> Unit
                404 -> error("O projeto ainda não tem uma release disponível.")
                403, 429 -> error("Limite de consultas do GitHub atingido. Tente novamente mais tarde.")
                else -> error("Não foi possível consultar o GitHub (HTTP ${connection.responseCode}).")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(!json.optBoolean("draft") && !json.optBoolean("prerelease")) { "A release ainda não é estável." }
            val tag = json.getString("tag_name")
            check(ReleasePolicy.versionCode(tag) != null) { "A versão da release não segue o formato esperado." }
            val assets = json.getJSONArray("assets")
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk") && it.optString("state") == "uploaded" }
            val asset = apks.singleOrNull { it.optString("name") == "app-$tag.apk" }
                ?: apks.singleOrNull() ?: error("A release não contém um APK único para atualização.")
            val url = asset.getString("browser_download_url")
            val size = asset.getLong("size")
            check(ReleasePolicy.validApkUrl(url) && size in 1..ReleasePolicy.MAX_APK_BYTES) { "APK da release inválido." }
            val digest = asset.optString("digest").takeIf { it.matches(Regex("sha256:[a-fA-F0-9]{64}")) }.orEmpty()
            return AppRelease(tag, url, size, digest)
        } finally { connection.disconnect() }
    }
}
