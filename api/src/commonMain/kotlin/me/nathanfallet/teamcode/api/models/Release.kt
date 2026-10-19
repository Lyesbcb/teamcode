package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Public release metadata. Authenticate exact manifest bytes before trusting these fields. */
@Serializable
data class ReleaseManifest(val schemaVersion: Int, val version: String, val tag: String, val sourceCommit: String, val assets: Map<String, ReleaseAsset>)
/** A canonical executable name, exact byte length and SHA-256 authenticated by its manifest. */
@Serializable
data class ReleaseAsset(val fileName: String, val sha256: String, val size: Long)

/** Shared release schema bounds; unknown future targets may be read but cannot be installed by older clients. */
object ReleaseFormat {
    const val MAX_MANIFEST_BYTES = 16 * 1024
    const val MAX_SIGNATURE_BYTES = 2048
    const val MAX_BINARY_BYTES = 256L * 1024 * 1024
    val targets = setOf("macos-arm64", "macos-x64", "linux-arm64", "linux-x64", "windows-x64")
    private const val NUMBER = "(?:0|[1-9][0-9]*)"
    private const val PART = "(?:$NUMBER|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"
    private val versionPattern = Regex("$NUMBER\\.$NUMBER\\.$NUMBER(?:-$PART(?:\\.$PART)*)?")
    /** Checks bounded SemVer without build metadata or ambiguous numeric identifiers. */
    fun validVersion(value: String) = value.length <= 80 && versionPattern.matches(value)
    /** Accepts bounded OS/CPU identifiers, including future schema-1 targets. */
    fun validTarget(value: String) = value.length <= 40 && Regex("[a-z][a-z0-9]*-[a-z0-9]+").matches(value)
    /** Derives the asset basename without accepting a path or a remote URL. */
    fun fileName(version: String, target: String): String {
        require(validVersion(version) && validTarget(target))
        return "teamcode-$version-$target" + if (target.startsWith("windows-")) ".exe" else ""
    }
}
