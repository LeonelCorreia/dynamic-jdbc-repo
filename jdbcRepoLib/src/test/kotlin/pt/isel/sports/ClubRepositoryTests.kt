package pt.isel.sports

import pt.isel.Repository
import pt.isel.RepositoryReflect
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement.RETURN_GENERATED_KEYS
import kotlin.test.Test
import kotlin.test.assertEquals

val DB_URL_SPORTS = System.getenv("DB_URL_SPORTS") ?: throw Exception("Missing env var DB_URL_SPORTS")

class ClubRepositoryTests {
    private val connection: Connection = DriverManager.getConnection(DB_URL_SPORTS)
    private val repository: Repository<Int, Club> =
        RepositoryReflect(connection, Club::class)

    private val presidentRepository: Repository<Int, President> =
        RepositoryReflect(connection, President::class)

    @Test
    fun `getAll should return all clubs`() {
        val clubs: List<Club> = repository.getAll()
        assertEquals(3, clubs.size)
    }

    @Test
    fun `retrieve a club`() {
        val sporting = repository.getAll().first { it.name.contains("Sporting") }
        val otherSporting = repository.getById(sporting.id)
        assertEquals(sporting, otherSporting)
    }

    @Test
    fun `update a club`() {
        val benfica = repository.getAll().first { it.name.contains("Benfica") }
        val updatedBenfica = benfica.copy(foundedYear = 1904, name = "Sport Lisboa e Benfica")
        repository.update(updatedBenfica)
        val retrieved = repository.getById(benfica.id)
        assertEquals(updatedBenfica, retrieved)
    }

    @Test
    fun `delete a club`() {
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
}
