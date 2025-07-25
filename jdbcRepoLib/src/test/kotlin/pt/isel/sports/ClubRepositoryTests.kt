package pt.isel.sports

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import pt.isel.Repository
import pt.isel.RepositoryReflect
import pt.isel.loadDynamicRepo
import pt.isel.sports.repositories.ClubRepository
import pt.isel.sports.repositories.PresidentRepository
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement.RETURN_GENERATED_KEYS
import kotlin.test.Test
import kotlin.test.assertEquals

val DB_URL_SPORTS = System.getenv("DB_URL_SPORTS") ?: throw Exception("Missing env var DB_URL_SPORTS")

class ClubRepositoryTests {
    companion object {
        private val connection: Connection = DriverManager.getConnection(DB_URL_SPORTS)
        private val presidentRepository: Repository<Int, President> =
            loadDynamicRepo(connection, President::class, PresidentRepository::class)
        private val dynClubRepo =
            loadDynamicRepo(connection, Club::class, ClubRepository::class)

        @JvmStatic
        fun repositories() =
            listOf(
                RepositoryReflect(connection, Club::class),
                dynClubRepo,
            )
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `getAll should return all clubs`(repository: Repository<Int, Club>) {
        val clubs: List<Club> = repository.getAll()
        assertEquals(3, clubs.size)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `retrieve a club`(repository: Repository<Int, Club>) {
        val sporting = repository.getAll().first { it.name.contains("Sporting") }
        val otherSporting = repository.getById(sporting.id)
        assertEquals(sporting, otherSporting)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `update a club`(repository: Repository<Int, Club>) {
        val benfica = repository.getAll().first { it.name.contains("Benfica") }
        val updatedBenfica = benfica.copy(foundedYear = 1904, name = "Sport Lisboa e Benfica")
        repository.update(updatedBenfica)
        val retrieved = repository.getById(benfica.id)
        assertEquals(updatedBenfica, retrieved)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `delete a club`(repository: Repository<Int, Club>) {
        val pauloRosado =
            presidentRepository
                .getAll()
                .first { it.name.contains("Paulo Rosado") }

        val sql = "INSERT INTO clubs (name, year, president) VALUES (?, ?, ?)"

        val values =
            arrayOf(
                "Oriental de Lisboa",
                1946,
                pauloRosado.id,
            )

        val pk =
            connection
                .prepareStatement(sql, RETURN_GENERATED_KEYS)
                .use { stmt ->
                    values.forEachIndexed { index, it -> stmt.setObject(index + 1, it) }
                    stmt.executeUpdate()
                    stmt.generatedKeys.use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        assertEquals(4, repository.getAll().size)
        repository.deleteById(pk)
        assertEquals(3, repository.getAll().size)
    }

    @Test
    fun `insert a club`() {
        val pauloRosado =
            presidentRepository
                .getAll()
                .first { it.name.contains("Paulo Rosado") }

        val newClub = Club(0, "Oriental de Lisboa", 1946, pauloRosado)
        val insertedClub = (dynClubRepo as ClubRepository).insert(newClub.name, newClub.foundedYear, newClub.president)
        assertEquals(newClub.copy(id = insertedClub.id), insertedClub)
        dynClubRepo.deleteById(insertedClub.id)
    }
}
