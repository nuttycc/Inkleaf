package com.exio.inkleaf.plugin

import java.math.BigInteger

/** SemVer 2.0 value used for plugin update and rollback ordering. */
data class SemVer(
    val major: BigInteger,
    val minor: BigInteger,
    val patch: BigInteger,
    val prerelease: List<Identifier> = emptyList(),
    val build: List<String> = emptyList(),
) : Comparable<SemVer> {
    sealed interface Identifier {
        data class Numeric(val value: BigInteger) : Identifier

        data class Text(val value: String) : Identifier
    }

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major)
            .takeIf { it != 0 }
            ?.let {
                return it
            }
        compareValues(minor, other.minor)
            .takeIf { it != 0 }
            ?.let {
                return it
            }
        compareValues(patch, other.patch)
            .takeIf { it != 0 }
            ?.let {
                return it
            }

        if (prerelease.isEmpty() && other.prerelease.isEmpty()) return 0
        if (prerelease.isEmpty()) return 1
        if (other.prerelease.isEmpty()) return -1

        val count = minOf(prerelease.size, other.prerelease.size)
        for (index in 0 until count) {
            val result = comparePrereleaseIdentifier(prerelease[index], other.prerelease[index])
            if (result != 0) return result
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    override fun toString(): String {
        val core = "$major.$minor.$patch"
        val pre = prerelease.takeIf { it.isNotEmpty() }?.joinToString(".") { it.asText() }
        val metadata = build.takeIf { it.isNotEmpty() }?.joinToString(".")
        return buildString {
            append(core)
            if (pre != null) append('-').append(pre)
            if (metadata != null) append('+').append(metadata)
        }
    }

    companion object {
        private val pattern =
            Regex(
                "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                    "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                    "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"
            )

        fun parse(value: String): SemVer? {
            val match = pattern.matchEntire(value) ?: return null
            val major = match.groupValues[1].toBigIntegerOrNull() ?: return null
            val minor = match.groupValues[2].toBigIntegerOrNull() ?: return null
            val patch = match.groupValues[3].toBigIntegerOrNull() ?: return null
            val prerelease = parseIdentifiers(match.groupValues[4]) ?: return null
            val build = match.groupValues[5].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            return SemVer(major, minor, patch, prerelease, build)
        }

        private fun parseIdentifiers(value: String): List<Identifier>? {
            if (value.isEmpty()) return emptyList()
            return value.split('.').map { token ->
                if (token.all(Char::isDigit)) {
                    if (token.length > 1 && token.startsWith('0')) return null
                    Identifier.Numeric(token.toBigIntegerOrNull() ?: return null)
                } else {
                    Identifier.Text(token)
                }
            }
        }

        private fun comparePrereleaseIdentifier(left: Identifier, right: Identifier): Int =
            when {
                left is Identifier.Numeric && right is Identifier.Numeric ->
                    left.value.compareTo(right.value)
                left is Identifier.Numeric && right is Identifier.Text -> -1
                left is Identifier.Text && right is Identifier.Numeric -> 1
                left is Identifier.Text && right is Identifier.Text ->
                    left.value.compareTo(right.value)
                else -> 0
            }

        private fun Identifier.asText(): String =
            when (this) {
                is Identifier.Numeric -> value.toString()
                is Identifier.Text -> value
            }
    }
}

data class ApiVersion(val major: Int, val minor: Int) {
    override fun toString(): String = "$major.$minor"

    companion object {
        private val pattern = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")

        fun parse(value: String): ApiVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            return ApiVersion(major, minor)
        }
    }
}

enum class PluginCompatibility {
    COMPATIBLE,
    API_MAJOR_MISMATCH,
    API_MINOR_TOO_NEW,
}

fun ApiVersion.compatibilityWith(host: ApiVersion): PluginCompatibility =
    when {
        major != host.major -> PluginCompatibility.API_MAJOR_MISMATCH
        minor > host.minor -> PluginCompatibility.API_MINOR_TOO_NEW
        else -> PluginCompatibility.COMPATIBLE
    }
