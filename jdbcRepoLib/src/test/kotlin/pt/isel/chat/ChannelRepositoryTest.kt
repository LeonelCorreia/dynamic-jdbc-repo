package pt.isel.chat

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import pt.isel.Repository
import pt.isel.RepositoryReflect
import pt.isel.chat.dao.ChannelRepositoryJdbc
import pt.isel.loadDynamicRepo
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

val DB_URL = System.getenv("DB_URL") ?: throw Exception("Missing env var DB_URL")

class ChannelRepositoryTest {
    companion object {
        private val connection: Connection = DriverManager.getConnection(DB_URL)

        @JvmStatic
        fun repositories() =
            listOf<Repository<String, Channel>>(
                RepositoryReflect(connection, Channel::class),
                loadDynamicRepo(connection, Channel::class)
            )
    }

    private val channelRandom =
        Channel("Random", ChannelType.PRIVATE, System.currentTimeMillis(), false, 400, 50, false, 0L)

    @BeforeEach
    fun setup() {
        ChannelRepositoryJdbc(connection).run {
            deleteById("Random")
            insert(channelRandom)
        }
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `retrieve a channel`(repository: Repository<String, Channel>) {
        val retrieved = repository.getById("General")
        assertNotNull(retrieved)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `update a channel`(repository: Repository<String, Channel>) {
        val updatedChannel = channelRandom.copy(maxMembers = 200, isReadOnly = true)
        repository.update(updatedChannel)

        val retrieved = repository.getById(channelRandom.name)
        assertNotNull(retrieved)
        assertEquals(200, retrieved.maxMembers)
        assertEquals(true, retrieved.isReadOnly)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `delete a channel`(repository: Repository<String, Channel>) {
        repository.deleteById(channelRandom.name)
        val retrieved = repository.getById(channelRandom.name)
        assertNull(retrieved)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `getAll should return all channels`(repository: Repository<String, Channel>) {
        val channels: List<Channel> = repository.getAll()
        assertEquals(6, channels.size)
        assertTrue(channelRandom in channels)
    }
}
