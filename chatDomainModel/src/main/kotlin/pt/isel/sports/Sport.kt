package pt.isel.sports

import pt.isel.Column
import pt.isel.Pk
import pt.isel.Table

@Table("sports")
data class Sport (
    @Pk
    val type : SportType,
    @Column("number_of_players")
    val nofPlayers: Int,
    @Column("match_duration")
    val matchDuration: Long,
    val location: Location
)