package pt.isel.sports

import pt.isel.Column
import pt.isel.Pk
import pt.isel.Table

@Table("teams")
data class Team (
    @Pk
    val name : String,
    @Column("club_name")
    val teamClub: Club,
    @Column("sport_name")
    val teamSport: Sport
)