package com.justvideo.app.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// =====================================================================
// ReleaseParserTest.kt
// Checks that GitHub's release JSON is read correctly.
// =====================================================================

class ReleaseParserTest {

    // STEP 1: A trimmed copy of what GitHub's API returns.
    private fun release(
        tag: String = "build-3",
        draft: Boolean = false,
        assetName: String = "JustVideo-0.1.2-build3.apk",
        digest: String? = "sha256:ABCDEF",
    ) = """
        {
          "tag_name": "$tag",
          "name": "JustVideo 0.1.2 (build 3)",
          "body": "Added the preview player",
          "draft": $draft,
          "prerelease": false,
          "html_url": "https://github.com/x/y/releases/tag/$tag",
          "author": {"login": "someone"},
          "assets": [
            {"name": "notes.txt", "size": 10, "browser_download_url": "https://e/notes.txt"},
            {"name": "$assetName", "size": 1600000,
             "browser_download_url": "https://e/app.apk"
             ${if (digest != null) ", \"digest\": \"$digest\"" else ""}}
          ]
        }
    """.trimIndent()

    // STEP 2: Tests.
    @Test
    fun parsesNormalRelease() {
        val info = ReleaseParser.parse(release())!!
        assertEquals(3L, info.versionCode)
        assertEquals("0.1.2", info.versionName)
        assertEquals("https://e/app.apk", info.apkUrl)
        assertEquals(1_600_000L, info.apkSizeBytes)
        assertEquals("abcdef", info.sha256)
        assertEquals("Added the preview player", info.notes)
    }

    @Test
    fun missingDigestIsAllowed() {
        assertNull(ReleaseParser.parse(release(digest = null))!!.sha256)
    }

    @Test
    fun rejectsDraftsUnknownTagsAndMissingApk() {
        assertNull(ReleaseParser.parse(release(draft = true)))
        assertNull(ReleaseParser.parse(release(tag = "v1.0")))
        assertNull(ReleaseParser.parse(release(assetName = "something.zip")))
        assertNull(ReleaseParser.parse("not json"))
    }
}
