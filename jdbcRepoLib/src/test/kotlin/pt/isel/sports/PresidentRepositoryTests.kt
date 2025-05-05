package pt.isel.sports

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import pt.isel.Repository
import pt.isel.RepositoryReflect
import pt.isel.loadDynamicRepo
import pt.isel.sports.repositories.PresidentRepository
import java.sql.Connection
import java.sql.Date
import java.sql.DriverManager
import java.sql.Statement.RETURN_GENERATED_KEYS
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class PresidentRepositoryTests {
    companion object {
        private val connection: Connection = DriverManager.getConnection(DB_URL_SPORTS)
        private val dynPresidentRepo =
            loadDynamicRepo(
                connection,
                President::class,
                PresidentRepository::class,
            ) as PresidentRepository

        @JvmStatic
        fun repositories() =
            listOf(
                RepositoryReflect(connection, President::class),
                dynPresidentRepo,
            )
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `getAll should return all users`(repository: Repository<Int, President>) {
        val presidents: List<President> = repository.getAll()
        assertEquals(4, presidents.size)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `retrieve a president`(repository: Repository<Int, President>) {
        val ruiCosta = repository.getAll().first { it.name.contains("Rui Costa") }
        val rui = repository.getById(ruiCosta.id)
        assertNotNull(rui)
        assertEquals(ruiCosta, rui)
        assertNotSame(ruiCosta, rui)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `update a president`(repository: Repository<Int, President>) {
        val uniqueName = "President Test ${System.currentTimeMillis()}"
        val sql = "INSERT INTO presidents (name, birthdate) VALUES (?, ?)"
        val values =
            arrayOf<Any>(
                uniqueName,
                Date.valueOf(LocalDate.of(1972, 2, 8)),
            )
        val pk =
            connection.prepareStatement(sql, RETURN_GENERATED_KEYS).use { stmt ->
                values.forEachIndexed { index, it -> stmt.setObject(index + 1, it) }
                stmt.executeUpdate()
                stmt.generatedKeys.use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        val president = repository.getById(pk)
        assertNotNull(president)
        val updatedPresident = president.copy(name = "$uniqueName Updated")
        repository.update(updatedPresident)
        val retrieved = repository.getById(pk)
        assertNotNull(retrieved)
        assertEquals(updatedPresident, retrieved)

        repository.deleteById(pk)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `delete a president`(repository: Repository<Int, President>) {
        val sql = "INSERT INTO presidents (name, birthdate) VALUES (?, ?)"
        val values =
            arrayOf<Any>(
                "Bruno Carvalho",
                Date.valueOf(LocalDate.of(1972, 2, 8)),
            )
        val pk =
            connection.prepareStatement(sql, RETURN_GENERATED_KEYS).use { stmt ->
                values.forEachIndexed { index, it -> stmt.setObject(index + 1, it) }
                stmt.executeUpdate()
                stmt.generatedKeys.use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        assertEquals(5, repository.getAll().size)
        repository.deleteById(pk)
        assertNull(repository.getById(pk))
        assertEquals(4, repository.getAll().size)
    }

    @Test
    fun `insert president`() {
        val luisFelipeViera = dynPresidentRepo.insert("Luis Felipe Viera", Date.valueOf(LocalDate.of(1949, 6, 22)))
        assertEquals(luisFelipeViera.name, "Luis Felipe Viera")
        dynPresidentRepo.deleteById(luisFelipeViera.id)
    }
}
