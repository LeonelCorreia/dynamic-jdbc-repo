package pt.isel.sports

import org.junit.jupiter.api.Test
import pt.isel.DB_URL
import pt.isel.Repository
import pt.isel.RepositoryReflect
import java.sql.Connection
import java.sql.Date
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

class DirectorRepositoryTests {
    private val connection: Connection = DriverManager.getConnection(DB_URL)
    private val repository: Repository<Int, Director> =
        RepositoryReflect(connection, Director::class)

    @Test
    fun `getAll should return all users`() {
        val directors: List<Director> = repository.getAll()
        directors.forEach {
            println(it.name)
            println(it.birthdate)
        }
        // assertEquals(3, directors.size)
    }

    @Test
    fun `retrieve a director`() {
        val ric = repository.getAll().first { it.name.contains("Ric") }
        val otherAlice = repository.getById(ric.id)
        assertNotNull(otherAlice)
        assertEquals(ric, otherAlice)
        assertNotSame(ric, otherAlice)
    }

    @Test
    fun `update a director`() {
        val ric = repository.getAll().first { it.name.contains("Ric") }
        val updatedRic = ric.copy(birthdate = Date.valueOf(LocalDate.of(2004, 4, 3)))
        repository.update(updatedRic)
        val retrieved = repository.getById(ric.id)
        assertNotNull(retrieved)
        assertEquals(updatedRic, retrieved)
    }

    @Test
    fun `delete a director`() {
        val ric = repository.getAll().first { it.name.contains("Ric") }
        repository.deleteById(ric.id)
        val retrieved = repository.getById(ric.id)
        assertNotNull(retrieved)
    }
}
