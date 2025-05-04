package pt.isel.sports.repositories

import pt.isel.Insert
import pt.isel.Repository
import pt.isel.sports.Club
import pt.isel.sports.Sport
import pt.isel.sports.Team

interface TeamRepository : Repository<Int, Team> {
    @Insert
    fun insert(
        name: String,
        teamClub: Club,
        teamSport: Sport,
    ): Team
}
