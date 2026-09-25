package com.uberanalyzer.update

import org.junit.Assert.*
import org.junit.Test

class ReleasePolicyTest {
    @Test fun comparesBuildNumbersNumerically() {
        assertTrue(ReleasePolicy.isNewer("v1.0.10001", 9901))
        assertFalse(ReleasePolicy.isNewer("v1.0.9901", 10001))
        assertFalse(ReleasePolicy.isNewer("v1.0.8901", 8901))
        assertEquals(8901L, ReleasePolicy.versionCode("1.0.8901"))
    }

    @Test fun rejectsUnsupportedOrMalformedVersions() {
        listOf("latest", "v1.0.1-beta", "v1.0.-1", "v1.0.0", "v1.0.2100000001", "v1.0.99999999999999999", "v2.0.1")
            .forEach { assertNull(it, ReleasePolicy.versionCode(it)) }
    }

    @Test fun onlyDownloadsReleaseApksFromThisRepository() {
        val url = "https://github.com/AmaroPSJunior/Indriver-analyzer/releases/download/v1.0.8901/app-v1.0.8901.apk"
        assertTrue(ReleasePolicy.validApkUrl(url))
        assertFalse(ReleasePolicy.validApkUrl(url.replace("https:", "http:")))
        assertFalse(ReleasePolicy.validApkUrl(url.replace("github.com", "github.com.evil.test")))
        assertFalse(ReleasePolicy.validApkUrl(url.replace("AmaroPSJunior", "another-owner")))
        assertFalse(ReleasePolicy.validApkUrl(url.replace(".apk", ".zip")))
        assertFalse(ReleasePolicy.validApkUrl("$url?redirect=elsewhere"))
        assertFalse(ReleasePolicy.validApkUrl(url.replace("github.com", "user@github.com")))
    }

    private fun valid(pkg: String = "com.uberanalyzer", code: Long = 9001, installed: Long = 8901,
        tag: String = "v1.0.9001", signers: Set<String> = setOf("certificate")) =
        ReleasePolicy.validArchive(pkg, "com.uberanalyzer", code, installed, tag, setOf("certificate"), signers)

    @Test fun acceptsOnlyNewerApkMatchingReleasePackageAndSigner() {
        assertTrue(valid())
        assertFalse(valid(pkg = "another.app"))
        assertFalse(valid(code = 8901))
        assertFalse(valid(installed = 9901))
        assertFalse(valid(tag = "v1.0.9901"))
        assertFalse(valid(signers = emptySet()))
        assertFalse(valid(signers = setOf("different-certificate")))
    }
}
