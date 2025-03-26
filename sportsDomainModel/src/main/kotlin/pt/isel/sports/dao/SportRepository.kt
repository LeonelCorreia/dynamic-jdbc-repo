package pt.isel.sports.dao

import pt.isel.Repository
import pt.isel.sports.Location
import pt.isel.sports.Sport
import pt.isel.sports.SportType

interface SportRepository : Repository<String, Sport> {
    fun insert(
        name: String,
        location: Location,
        type: SportType,
    )

    fun insert(sport: Sport) {
        insert(sport.name, sport.location, sport.type)
    }
}
