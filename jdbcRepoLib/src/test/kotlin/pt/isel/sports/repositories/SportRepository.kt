package pt.isel.sports.repositories

import pt.isel.Insert
import pt.isel.Repository
import pt.isel.sports.Location
import pt.isel.sports.Sport
import pt.isel.sports.SportType

interface SportRepository : Repository<String, Sport> {
    @Insert
    fun insert(
        name: String,
        type: SportType,
        location: Location,
    ): Sport
}
