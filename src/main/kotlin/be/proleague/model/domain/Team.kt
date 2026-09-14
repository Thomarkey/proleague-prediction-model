package be.proleague.model.domain

/** Identified by the provider's team id, since display names differ between sources. */
data class Team(
    val id: Int,
    val name: String,
)
