package pt.isel.sports

import org.junit.jupiter.api.Test
import pt.isel.DB_URL
import pt.isel.Repository
import pt.isel.RepositoryReflect
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals

class DirectorRepositoryTests {
    private val connection: Connection = DriverManager.getConnection(DB_URL)
    private val repository: Repository<Long, Director> =
        RepositoryReflect(connection, Director::class)

    @Test
    fun `getAll should return all users`() {
        val directors: List<Director> = repository.getAll()
        directors.forEach {
            println(it.name)
            println(it.age)
        }
        //assertEquals(3, directors.size)
    }

    @Test
    fun `retrieve a director`() {
        val alice = repository.getAll().first { it.name.contains("") }
        val otherAlice = repository.getById(alice.name)
        assertNotNull(otherAlice)
        assertEquals(alice, otherAlice)
        assertNotSame(alice, otherAlice)
    }
}