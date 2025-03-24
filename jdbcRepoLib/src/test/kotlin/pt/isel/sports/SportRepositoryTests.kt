package pt.isel.sports

import org.junit.jupiter.api.Test
import pt.isel.DB_URL
import pt.isel.Repository
import pt.isel.RepositoryReflect
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals

class SportRepositoryTests {
    private val connection: Connection = DriverManager.getConnection(DB_URL)
    private val repository: Repository<Int, Sport> =
        RepositoryReflect(connection, Sport::class)

    @Test
    fun `getAll should return all sports`() {
        val sports: List<Sport> = repository.getAll()
        sports.forEach {
            println(it.id)
            println(it.name)
            println(it.type)
            println(it.location)
        }
        // assertEquals(3, sports.size)
    }

    @Test
    fun `retrieve a sport`() {
        val basket = repository.getAll().first { it.name.contains("BasketBall") }
        val foundBasket = repository.getById(basket.id)
        assertEquals(basket, foundBasket)
    }

    @Test
    fun `update a sport`() {
        val tennis = repository.getAll().first { it.name.contains("Tennis") }
        val updatedTennis = tennis.copy(type = SportType.INDIVIDUAL)
        repository.update(updatedTennis)
        val retrieved = repository.getById(tennis.id)
        assertEquals(updatedTennis, retrieved)
    }

    @Test
    fun `delete a sport`() {
        val sport = repository.getAll().first { it.name.contains("BasketBall") }
        repository.deleteById(sport.id)
        val retrieved = repository.getById(sport.id)
        assertEquals(null, retrieved)
    }
}
