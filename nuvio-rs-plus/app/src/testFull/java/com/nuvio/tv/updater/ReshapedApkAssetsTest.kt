package com.nuvio.tv.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReshapedApkAssetsTest {
    private val tag = "1.1.0-beta.1-autosync.11"
    private val releaseAssets = listOf(
        "NuvioRSPlus-TV-$tag-arm64.apk",
        "NuvioRSPlus-TV-$tag-arm32.apk",
        "NuvioRSPlus-TV-$tag-x64.apk",
        "NuvioRSPlus-TV-$tag-x32.apk",
        "NuvioRSPlus-TV-$tag-any.apk",
        // Transitional aliases for the already-installed baseline updater.
        "NuvioRS-TV-$tag-arm64.apk",
        "NuvioRS-TV-$tag-any.apk",
    )
    private val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    @Test
    fun `picks the Nuvio RS Plus APK for the device ABI`() {
        assertEquals("NuvioRSPlus-TV-$tag-arm64.apk", ReshapedApkAssets.choose(releaseAssets, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("NuvioRSPlus-TV-$tag-arm32.apk", ReshapedApkAssets.choose(releaseAssets, listOf("armeabi-v7a")))
        assertEquals("NuvioRSPlus-TV-$tag-x64.apk", ReshapedApkAssets.choose(releaseAssets, listOf("x86_64")))
        assertEquals("NuvioRSPlus-TV-$tag-x32.apk", ReshapedApkAssets.choose(releaseAssets, listOf("x86")))
        assertEquals("NuvioRSPlus-TV-$tag-any.apk", ReshapedApkAssets.choose(releaseAssets, listOf("riscv64")))
    }

    @Test
    fun `accepts the previous Nuvio RS asset prefix as fallback`() {
        val legacy = listOf(
            "NuvioRS-TV-$tag-arm64.apk",
            "NuvioRS-TV-$tag-any.apk",
        )
        assertEquals("NuvioRS-TV-$tag-arm64.apk", ReshapedApkAssets.choose(legacy, listOf("arm64-v8a")))
    }

    @Test
    fun `ignores unrelated APK names`() {
        assertNull(ReshapedApkAssets.choose(listOf("NuvioTV-AutoSync-1.0.0-autosync.9-arm64-v8a.apk"), abis))
    }
}
