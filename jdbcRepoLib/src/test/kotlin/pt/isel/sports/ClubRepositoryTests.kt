package pt.isel.sports

import pt.isel.DB_URL
import pt.isel.Repository
import pt.isel.RepositoryReflect
import java.sql.Connection
import java.sql.Date
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ClubRepositoryTests {
    private val connection: Connection = DriverManager.getConnection(DB_URL)
    private val repository: Repository<Int, Club> =
        RepositoryReflect(connection, Club::class)

    @Test
    fun `getAll should return all clubs`() {
        val clubs: List<Club> = repository.getAll()
        clubs.forEach {
            println(it.name)
            println(it.foundedYear)
            println(it.director)
        }
        // assertEquals(3, clubs.size)
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
        val updatedBenfica = benfica.copy(foundedYear = 1904)
        repository.update(updatedBenfica)
        val retrieved = repository.getById(benfica.id)
        assertEquals(updatedBenfica, retrieved)
    }

    @Test
    fun `delete a club`() {
        val sql =
            """
            INSERT INTO clubs (name, year, director)
            VALUES (?, ?, ?)
            """
        val porto =
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, "Porto")
                stmt.setInt(2, 1893)
                stmt.setString(3, "Pinto da Costa")
                stmt.executeUpdate()

                val pk =
                    stmt.generatedKeys.use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                Club(
                    pk,
                    "Porto",
                    1893,
                    Director(
                        id = 1,
                        name = "Pinto da Costa",
                        birthdate = Date.valueOf(LocalDate.of(1900, 1, 1)),
                    ),
                )
            }

        val portoClub = repository.getById(porto.id)
        assertEquals("Porto", portoClub!!.name)
        repository.deleteById(porto.id)
        val deletedClub = repository.getById(porto.id)
        assertEquals(null, deletedClub)
    }
}
