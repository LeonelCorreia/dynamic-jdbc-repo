package pt.isel

import pt.isel.chat.Message
import pt.isel.chat.dao.ChannelRepositoryJdbc
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement.RETURN_GENERATED_KEYS
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

class MessageRepositoryTest {
    private val connection: Connection = DriverManager.getConnection(DB_URL)
    private val repository: Repository<Long, Message> = RepositoryReflect(connection, Message::class)

    @Test
    fun `getAll should return all messages`() {
        val users: List<Message> = repository.getAll()
        assertEquals(20, users.size)
    }

    @Test
    fun `retrieve a message`() {
        val esportsMsg = repository.getAll().first { it.channel.name == "Esports Discussion" }
        val otherMsg = repository.getById(esportsMsg.id)
        assertNotNull(otherMsg)
        assertEquals(otherMsg, esportsMsg)
        assertNotSame(otherMsg, esportsMsg)
    }

    @Test
    fun `retrieve message with PK three`() {
        val msg = repository.getById(3)
    }

    @Test
    fun `update a message content`() {
        val devMsg = repository.getAll().first { it.channel.name == "Development" }
        val updatedMsg = devMsg.copy(content = "Only include relevant and reliable contents.")
        repository.update(updatedMsg)
        val retrieved = repository.getById(updatedMsg.id)
        assertNotNull(retrieved)
        assertEquals(updatedMsg, retrieved)
        assertNotSame(updatedMsg, retrieved)
    }

    @Test
    fun `update a message channel`() {
        val devMsg = repository.getAll().first { it.channel.name == "Development" }
        val general = ChannelRepositoryJdbc(connection).getById("General")
        assertNotNull(general)
        val updatedMsg = devMsg.copy(channel = general)
        repository.update(updatedMsg)
        val retrieved = repository.getById(updatedMsg.id)
        assertNotNull(retrieved)
        assertEquals(updatedMsg, retrieved)
        assertNotSame(updatedMsg, retrieved)
    }

    @Test
    fun `delete a message`() {
        val sql =
            """
            INSERT INTO messages (content, timestamp, user_id, channel_name)
            VALUES (?, ?, ?, ?)
            """
        val values =
            arrayOf<Any>(
                "With Reflection we can provide an auto implementation of SQL operations via JDBC.",
                LocalDate.now().toEpochDay(),
                2,
                "Development",
            )
        val pk =
            connection
                .prepareStatement(sql, RETURN_GENERATED_KEYS)
                .use { stmt ->
                    values.forEachIndexed { index, it -> stmt.setObject(index + 1, it) }
                    stmt.executeUpdate() // Executes the INSERT
                    stmt.generatedKeys.use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
        assertEquals(21, repository.getAll().size)
        repository.deleteById(pk)
        assertEquals(20, repository.getAll().size)
    }
}
