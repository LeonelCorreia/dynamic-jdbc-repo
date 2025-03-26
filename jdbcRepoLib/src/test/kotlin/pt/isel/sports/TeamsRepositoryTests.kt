package pt.isel.sports

import org.junit.jupiter.api.Test
import pt.isel.Repository
import pt.isel.RepositoryReflect
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement.RETURN_GENERATED_KEYS
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TeamsRepositoryTests {
    private val connection: Connection = DriverManager.getConnection(DB_URL_SPORTS)
    private val repository: Repository<Int, Team> =
        RepositoryReflect(connection, Team::class)

    @Test
    fun `getAll should return all teams`() {
        val teams: List<Team> = repository.getAll()
        assertEquals(9, teams.size)
    }

    @Test
    fun `retrieve a team`() {
        val team = repository.getAll().first { it.name.contains("Benfica Football") }
        val otherTeam = repository.getById(team.id)
        assertNotNull(otherTeam)
        assertEquals(team, otherTeam)
    }

    @Test
    fun `update a team`() {
        val team = repository.getAll().first { it.name.contains("Benfica Soccer") }
        val updatedTeam = team.copy(name = "Benfica Football")
        repository.update(updatedTeam)
        val retrieved = repository.getById(team.id)
        assertNotNull(retrieved)
        assertEquals(updatedTeam, retrieved)
    }

    @Test
    fun `delete a team`() {
        val sql =
            """
            INSERT INTO teams (name, sport, club)
            VALUES (?, ?, ?)
            """.trimIndent()

        val values =
            arrayOf(
                "Benfica Feminine Football",
                "Football",
                2,
            )

        val pk =
            connection
                .prepareStatement(sql, RETURN_GENERATED_KEYS)
                .use { stmt ->
                    values.forEachIndexed { index, value -> stmt.setObject(index + 1, value) }
                    stmt.executeUpdate()
                    stmt.generatedKeys.use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        val teams = repository.getAll()
        assertEquals(10, teams.size)
        repository.deleteById(pk)
        assertEquals(9, repository.getAll().size)
    }
}
