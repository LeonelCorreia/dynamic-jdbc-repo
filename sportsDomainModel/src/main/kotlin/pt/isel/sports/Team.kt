package pt.isel.sports

import pt.isel.Column
import pt.isel.Pk
import pt.isel.Table

@Table("teams")
data class Team(
    @Pk val id: Int,
    val name: String,
    @Column("club")
    val teamClub: Club,
    @Column("sport")
    val teamSport: Sport,
)
