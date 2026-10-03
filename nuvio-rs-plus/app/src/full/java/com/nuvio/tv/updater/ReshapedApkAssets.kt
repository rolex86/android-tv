package com.nuvio.tv.updater

/**
 * Nuvio RS Plus release APKs use the `NuvioRSPlus-` prefix.
 *
 * `NuvioRS-` remains accepted as a legacy fallback so an already installed baseline
 * build can update into the renamed asset line without a manual reinstall.
 */
internal object ReshapedApkAssets {
    const val PREFIX = "NuvioRSPlus-"
    private const val LEGACY_PREFIX = "NuvioRS-"

    private val abiAliases = mapOf(
        "arm64-v8a" to "arm64",
        "armeabi-v7a" to "arm32",
        "x86_64" to "x64",
        "x86" to "x32",
    )

    /** Picks the Plus APK for [supportedAbis] (else the "-any" one). */
    fun choose(apkNames: List<String>, supportedAbis: List<String>): String? {
        val plus = apkNames.filter { it.startsWith(PREFIX, ignoreCase = true) }
        if (plus.isNotEmpty()) return chooseFrom(plus, supportedAbis)

        val legacy = apkNames.filter { it.startsWith(LEGACY_PREFIX, ignoreCase = true) }
        if (legacy.isNotEmpty()) return chooseFrom(legacy, supportedAbis)

        return null
    }

    private fun chooseFrom(candidates: List<String>, supportedAbis: List<String>): String {
        for (abi in supportedAbis) {
            val alias = abiAliases[abi] ?: continue
            candidates.firstOrNull { it.lowercase().removeSuffix(".apk").endsWith("-$alias") }?.let { return it }
        }
        return candidates.firstOrNull { it.lowercase().removeSuffix(".apk").endsWith("-any") }
            ?: candidates.first()
    }
}
