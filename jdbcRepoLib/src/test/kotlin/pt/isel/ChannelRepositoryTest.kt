package pt.isel

import org.junit.jupiter.api.BeforeEach
import pt.isel.chat.Channel
import pt.isel.chat.ChannelType
import pt.isel.chat.dao.ChannelRepositoryJdbc
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

val DB_URL = System.getenv("DB_URL") ?: throw Exception("Missing env var DB_URL")

class ChannelRepositoryTest {
    private val connection: Connection = DriverManager.getConnection(DB_URL)
    private val repository: Repository<String, Channel> =
        RepositoryReflect(connection, Channel::class) // ChannelRepositoryJdbc(connection)
    private val channelRandom =
        Channel("Random", ChannelType.PRIVATE, System.currentTimeMillis(), false, 400, 50, false, 0L)

    @BeforeEach
    fun setup() {
        ChannelRepositoryJdbc(connection).run {
            deleteById("Random")
            insert(channelRandom)
        }
    }

    @Test
    fun `retrieve a channel`() {
        val retrieved = repository.getById("General")
        assertNotNull(retrieved)
    }

    @Test
    fun `update a channel`() {
        val updatedChannel = channelRandom.copy(maxMembers = 200, isReadOnly = true)
        repository.update(updatedChannel)

        val retrieved = repository.getById(channelRandom.name)
        assertNotNull(retrieved)
        assertEquals(200, retrieved.maxMembers)
        assertEquals(true, retrieved.isReadOnly)
    }

    @Test
    fun `delete a channel`() {
        repository.deleteById(channelRandom.name)
        val retrieved = repository.getById(channelRandom.name)
        assertNull(retrieved)
    }

    @Test
    fun `getAll should return all channels`() {
        val channels: List<Channel> = repository.getAll()
        channels.forEach {
            println("--------------------------------")
            println(it.name)
            println(it.type)
            println(it.createdAt)
            println(it.isArchived)
            println(it.maxMessageLength)
            println(it.maxMembers)
            println(it.isReadOnly)
            println(it.lastMessageTimestamp)
        }
        assertEquals(6, channels.size)
        assertTrue(channelRandom in channels)
    }
}
