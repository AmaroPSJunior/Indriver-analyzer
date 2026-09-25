package com.uberanalyzer.update

import java.net.URI

internal object ReleasePolicy {
    const val REPOSITORY = "AmaroPSJunior/Indriver-analyzer"
    const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val MAX_APK_BYTES = 150L * 1024 * 1024

    // The release workflow publishes versionName 1.0.<versionCode>.
    fun versionCode(tag: String): Long? = Regex("^v?1\\.0\\.([0-9]+)$")
        .matchEntire(tag.trim())?.groupValues?.get(1)?.toLongOrNull()
        ?.takeIf { it in 1..2100000000L }

    fun isNewer(tag: String, installedCode: Long): Boolean =
        versionCode(tag)?.let { it > installedCode } == true

    fun validApkUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.path.startsWith("/$REPOSITORY/releases/download/") && uri.path.endsWith(".apk") &&
            !uri.path.contains("/../")
    }.getOrDefault(false)

    fun validArchive(packageName: String, expectedPackage: String, code: Long,
        installedCode: Long, tag: String, installedSigners: Set<String>, archiveSigners: Set<String>): Boolean =
        packageName == expectedPackage && code > installedCode && code == versionCode(tag) &&
            installedSigners.isNotEmpty() && installedSigners == archiveSigners
}
