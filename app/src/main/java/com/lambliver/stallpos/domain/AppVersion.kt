package com.lambliver.stallpos.domain

data class StableVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<StableVersion> {
    override fun compareTo(other: StableVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"
}

object AppVersion {
    private val tag = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$")

    fun parse(raw: String): StableVersion? {
        val match = tag.matchEntire(raw.trim()) ?: return null
        return StableVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
    }

    fun isNewer(current: String, candidateTag: String): Boolean {
        val here = parse(current) ?: return false
        val there = parse(candidateTag) ?: return false
        return there > here
    }
}
