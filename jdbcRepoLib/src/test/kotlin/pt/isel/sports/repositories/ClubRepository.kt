package pt.isel.sports.repositories

import pt.isel.Insert
import pt.isel.Repository
import pt.isel.sports.Club
import pt.isel.sports.President

interface ClubRepository : Repository<Int, Club> {
    @Insert
    fun insert(
        name: String,
        foundedYear: Int,
        president: President,
    ): Club
}
