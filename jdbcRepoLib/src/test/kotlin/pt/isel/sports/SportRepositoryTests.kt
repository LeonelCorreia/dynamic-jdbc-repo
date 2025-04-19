package pt.isel.sports

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import pt.isel.Repository
import pt.isel.RepositoryReflect
import pt.isel.loadDynamicRepo
import pt.isel.sports.dao.SportRepositoryJdbc
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class SportRepositoryTests {
    companion object {
        private val connection: Connection = DriverManager.getConnection(DB_URL_SPORTS)

        @JvmStatic
        fun repositories() =
            listOf<Repository<String, Sport>>(
                RepositoryReflect(connection, Sport::class),
                loadDynamicRepo(connection, Sport::class),
            )
    }

    private val tableTennis = Sport("Table Tennis", SportType.INDIVIDUAL, Location.INDOOR)

    @BeforeEach
    fun setup() {
        SportRepositoryJdbc(connection).run {
            deleteById(tableTennis.name)
            insert(tableTennis)
        }
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `getAll should return all sports`(repository: Repository<String, Sport>) {
        val sports: List<Sport> = repository.getAll()
        assertEquals(4, sports.size)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `retrieve a sport`(repository: Repository<String, Sport>) {
        val basket = repository.getAll().first { it.name.contains("Basketball") }
        val foundBasket = repository.getById(basket.name)
        assertEquals(basket, foundBasket)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `update a sport`(repository: Repository<String, Sport>) {
        val uniqueSportName = "TestSport${System.currentTimeMillis()}"

        val sql = """
        INSERT INTO sports (name, type, location)
        VALUES (?, ?::sport_type, ?::location)
        """.trimIndent()

        connection.prepareStatement(sql).use { stmt ->
            stmt.setString(1, uniqueSportName)
            stmt.setString(2, SportType.TEAM.name)
            stmt.setString(3, Location.INDOOR.name)
            stmt.executeUpdate()
        }
        val sport = repository.getById(uniqueSportName)
        assertEquals(SportType.TEAM, sport!!.type)

        val updatedSport = sport.copy(type = SportType.INDIVIDUAL)
        repository.update(updatedSport)

        val retrieved = repository.getById(uniqueSportName)
        assertNotSame(updatedSport, retrieved)
        assertEquals(updatedSport, retrieved)

        repository.deleteById(uniqueSportName)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `delete a sport`(repository: Repository<String, Sport>) {
        repository.deleteById(tableTennis.name)
        val tableTennis = repository.getById(tableTennis.name)
        assertNull(tableTennis)
    }
}
