package com.justvideo.app.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// =====================================================================
// ReleaseParser.kt
// Turns GitHub's "latest release" JSON into an UpdateInfo.
// Pure code with no network, so it is unit-tested.
//
// Every release is made by the GitHub Actions workflow
// (.github/workflows/release.yml), which names things like this:
//   tag:   build-2
//   title: JustVideo 0.1.1 (build 2)
//   asset: JustVideo-0.1.1-build2.apk
// =====================================================================

object ReleaseParser {

    // STEP 1: Only the fields we need. GitHub sends many more, which are ignored.
    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String? = null,
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("html_url") val htmlUrl: String = "",
        val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    private data class GhAsset(
        val name: String = "",
        val size: Long = 0,
        @SerialName("browser_download_url") val downloadUrl: String = "",
        /** e.g. "sha256:ab12…". Newer GitHub API versions include it. */
        val digest: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val buildNumber = Regex("""build-(\d+)""")
    private val versionInTitle = Regex("""JustVideo\s+(\S+)""")

    // STEP 2: Parse. Returns null if the release isn't usable (no APK,
    // a draft, or a tag we don't understand).
    fun parse(text: String): UpdateInfo? {
        val r = runCatching { json.decodeFromString(GhRelease.serializer(), text) }.getOrNull() ?: return null
        if (r.draft || r.prerelease) return null
        val code = buildNumber.find(r.tagName)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val apk = r.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
        val version = r.name?.let { versionInTitle.find(it)?.groupValues?.get(1) } ?: r.tagName
        return UpdateInfo(
            versionCode = code,
            versionName = version,
            notes = r.body.orEmpty().trim(),
            apkUrl = apk.downloadUrl,
            apkSizeBytes = apk.size,
            sha256 = apk.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase(),
            pageUrl = r.htmlUrl,
        )
    }
}
