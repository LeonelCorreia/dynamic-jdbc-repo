package pt.isel.sports

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import pt.isel.Repository
import pt.isel.RepositoryReflect
import pt.isel.loadDynamicRepo
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement.RETURN_GENERATED_KEYS
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TeamsRepositoryTests {
    companion object {
        private val connection: Connection = DriverManager.getConnection(DB_URL_SPORTS)

        @JvmStatic
        fun repositories() =
            listOf<Repository<Int, Team>>(
                RepositoryReflect(connection, Team::class),
                loadDynamicRepo(connection, Team::class),
            )
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `getAll should return all teams`(repository: Repository<Int, Team>) {
        val teams: List<Team> = repository.getAll()
        assertEquals(9, teams.size)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `retrieve a team`(repository: Repository<Int, Team>) {
        val team = repository.getAll().first { it.name.contains("Sporting Football") }
        val otherTeam = repository.getById(team.id)
        assertNotNull(otherTeam)
        assertEquals(team, otherTeam)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `update a team`(repository: Repository<Int, Team>) {
        val uniqueName = "Benfica Test Team ${System.currentTimeMillis()}"

        val sportName = "Football"
        val clubId = 2

        val sql = """
        INSERT INTO teams (name, sport, club)
        VALUES (?, ?, ?)
        """.trimIndent()

        val values = arrayOf(
            uniqueName,
            sportName,
            clubId
        )

        val pk = connection
            .prepareStatement(sql, RETURN_GENERATED_KEYS)
            .use { stmt ->
                values.forEachIndexed { index, value -> stmt.setObject(index + 1, value) }
                stmt.executeUpdate()
                stmt.generatedKeys.use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }

        val team = repository.getById(pk)
        assertNotNull(team)
        val updatedTeam = team.copy(name = "$uniqueName - Updated")
        repository.update(updatedTeam)
        val retrieved = repository.getById(pk)
        assertNotNull(retrieved)
        assertEquals(updatedTeam, retrieved)

        repository.deleteById(pk)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `delete a team`(repository: Repository<Int, Team>) {
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
