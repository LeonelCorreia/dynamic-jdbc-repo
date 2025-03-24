package pt.isel.sports

import pt.isel.Pk
import pt.isel.Table

@Table("sports")
data class Sport(
    @Pk
    val id: Int,
    val name: String,
    val type: SportType,
    val location: Location,
)
