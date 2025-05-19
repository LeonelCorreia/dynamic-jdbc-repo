package pt.isel.sports.dao

import pt.isel.Queryable
import pt.isel.sports.Location
import pt.isel.sports.Sport
import pt.isel.sports.SportType
import java.sql.Connection
import java.sql.ResultSet

class SportRepositoryJdbc(
    private val connection: Connection,
) : SportRepository {
    override fun insert(
        name: String,
        location: Location,
        type: SportType,
    ) {
        val sql =
            """
             INSERT INTO sports (name, location, type) 
            VALUES (?, ?::location, ?::sport_type) 
            """.trimIndent()

        connection.prepareStatement(sql).use {
            it.setString(1, name)
            it.setString(2, location.name)
            it.setString(3, type.name)
            it.executeUpdate()
        }
    }

    override fun getById(id: String): Sport? {
        val sql = "SELECT * FROM sports WHERE name = ?"

        connection.prepareStatement(sql).use { stm ->
            stm.setString(1, id)
            val rs = stm.executeQuery()
            return if (rs.next()) mapRowToSport(rs) else null
        }
    }

    override fun getAll(): List<Sport> {
        val sql = "SELECT * FROM sports"
        return connection.prepareStatement(sql).use { stm ->
            stm.executeQuery().use { rs ->
                val sports = mutableListOf<Sport>()
                while (rs.next()) {
                    sports.add(mapRowToSport(rs))
                }
                sports
            }
        }
    }

    override fun update(entity: Sport) {
        val sql =
            """
            UPDATE sports
            SET location = ?::location, type = ?::sport_type
            WHERE name = ?
            """.trimIndent()
        connection.prepareStatement(sql).use {
            it.setString(1, entity.location.name)
            it.setString(2, entity.type.name)
            it.setString(3, entity.name)
            it.executeUpdate()
        }
    }

    override fun deleteById(id: String) {
        val sql = "DELETE FROM sports WHERE name = ?"
        connection.prepareStatement(sql).use {
            it.setString(1, id)
            it.executeUpdate()
        }
    }

    override fun findAll(): Queryable<Sport> {
        TODO("Not yet implemented")
    }

    private fun mapRowToSport(rs: ResultSet): Sport =
        Sport(
            name = rs.getString("name"),
            location = Location.valueOf(rs.getString("location")),
            type = SportType.valueOf(rs.getString("type")),
        )
}
