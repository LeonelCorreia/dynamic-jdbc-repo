package pt.isel.sports.repositories

import pt.isel.Insert
import pt.isel.Repository
import pt.isel.sports.President
import java.sql.Date

interface PresidentRepository : Repository<Int, President> {
    @Insert
    fun insert(
        name: String,
        birthdate: Date,
    ): President
}
