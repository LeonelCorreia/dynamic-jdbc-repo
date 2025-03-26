package pt.isel.sports

import org.junit.jupiter.api.Test
import pt.isel.Repository
import pt.isel.RepositoryReflect
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class PresidentRepositoryTests {
    private val connection: Connection = DriverManager.getConnection(DB_URL_SPORTS)
    private val repository: Repository<Int, President> =
        RepositoryReflect(connection, President::class)

    @Test
    fun `getAll should return all users`() {
        val presidents: List<President> = repository.getAll()
        assertEquals(4, presidents.size)
    }

    @Test
    fun `retrieve a president`() {
        val ruiCosta = repository.getAll().first { it.name.contains("Rui Costa") }
        val rui = repository.getById(ruiCosta.id)
        assertNotNull(rui)
        assertEquals(ruiCosta, rui)
        assertNotSame(ruiCosta, rui)
    }

    @Test
    fun `update a president`() {
        val rui = repository.getAll().first { it.name.contains("Rui Costa") }
        val updatedRui = rui.copy(name = "Rui Manuel César Costa ")
        repository.update(updatedRui)
        val retrieved = repository.getById(rui.id)
        assertNotNull(retrieved)
        assertEquals(updatedRui, retrieved)
    }

    @Test
    fun `delete a president`() {
        val andreVillasBoas =
            repository.getAll().first { it.name.contains("ndré Villas-Boas") }
        repository.deleteById(andreVillasBoas.id)
        val retrieved = repository.getById(andreVillasBoas.id)
        assertNull(retrieved)
    }
}
