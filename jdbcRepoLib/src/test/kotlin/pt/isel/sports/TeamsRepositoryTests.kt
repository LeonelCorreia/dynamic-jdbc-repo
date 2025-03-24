package pt.isel.sports

import org.junit.jupiter.api.Test
import pt.isel.DB_URL
import pt.isel.Repository
import pt.isel.RepositoryReflect
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TeamsRepositoryTests {
    private val connection: Connection = DriverManager.getConnection(DB_URL)
    private val repository: Repository<Int, Team> =
        RepositoryReflect(connection, Team::class)

    @Test
    fun `getAll should return all teams`() {
        val teams: List<Team> = repository.getAll()
        teams.forEach {
            println(it.name)
            println(it.teamClub)
            println(it.teamSport)
        }
        // assertEquals(3, teams.size)
    }

    @Test
    fun `retrieve a team`() {
        val team = repository.getAll().first { it.name.contains("Football Benfica") }
        val otherTeam = repository.getById(team.id)
        assertNotNull(otherTeam)
        assertEquals(team, otherTeam)
    }

    @Test
    fun `update a team`() {
        val team = repository.getAll().first { it.name.contains("Football Benfica") }
        val updatedTeam = team.copy(name = "Benfica Football")
        repository.update(updatedTeam)
        val retrieved = repository.getById(team.id)
        assertNotNull(retrieved)
        assertEquals(updatedTeam, retrieved)
    }

    @Test
    fun `delete a team`() {
        val team = repository.getAll().first { it.name.contains("Football Benfica") }
        repository.deleteById(team.id)
        val retrieved = repository.getById(team.id)
        assertNotNull(retrieved)
    }
}
