package pt.isel.chat

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import pt.isel.Repository
import pt.isel.RepositoryReflect
import pt.isel.chat.dao.ChannelRepositoryJdbc
import pt.isel.chat.repositories.ChannelRepository
import pt.isel.chat.repositories.MessageRepository
import pt.isel.chat.repositories.UserRepository
import pt.isel.loadDynamicRepo
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement.RETURN_GENERATED_KEYS
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

class MessageRepositoryTest {
    companion object {
        private val connection: Connection = DriverManager.getConnection(DB_URL)
        private val dynChannelRepo = loadDynamicRepo(connection, Channel::class, ChannelRepository::class)
        private val dynUserRepo = loadDynamicRepo(connection, User::class, UserRepository::class)
        private val dynMsgRepo =
            loadDynamicRepo(
                connection,
                Message::class,
                MessageRepository::class,
            ) as MessageRepository

        @JvmStatic
        fun repositories() =
            listOf(
                RepositoryReflect(connection, Message::class),
                dynMsgRepo,
            )
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `getAll should return all messages`(repository: Repository<Long, Message>) {
        val users: List<Message> = repository.getAll()
        assertEquals(20, users.size)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `retrieve a message`(repository: Repository<Long, Message>) {
        val esportsMsg = repository.getAll().first { it.channel.name == "Esports Discussion" }
        val otherMsg = repository.getById(esportsMsg.id)
        assertNotNull(otherMsg)
        assertEquals(otherMsg, esportsMsg)
        assertNotSame(otherMsg, esportsMsg)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `retrieve message with PK three`(repository: Repository<Long, Message>) {
        val msg = repository.getById(3)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `update a message content`(repository: Repository<Long, Message>) {
        val devMsg = repository.getAll().first { it.channel.name == "Development" }
        val updatedMsg = devMsg.copy(content = "Only include relevant and reliable contents.")
        repository.update(updatedMsg)
        val retrieved = repository.getById(updatedMsg.id)
        assertNotNull(retrieved)
        assertEquals(updatedMsg, retrieved)
        assertNotSame(updatedMsg, retrieved)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `update a message channel`(repository: Repository<Long, Message>) {
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

    @ParameterizedTest
    @MethodSource("repositories")
    fun `delete a message`(repository: Repository<Long, Message>) {
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

    @Test
    fun `insert a message`() {
        val channel = dynChannelRepo.getById("General")
        assertNotNull(channel)
        val user = dynUserRepo.getById(2)
        assertNotNull(user)
        val msg =
            dynMsgRepo.insert(
                "With dynamic implementation of the jdbc repo we can support insert operations.",
                LocalDate.now().toEpochDay(),
                channel,
                user,
            )
        assertNotNull(msg)
        assertEquals("With dynamic implementation of the jdbc repo we can support insert operations.", msg.content)
        dynMsgRepo.deleteById(msg.id)
    }

    @Test
    fun `find all should have a lazy implementation`() {
        val charlie = dynUserRepo.getById(3)
        assertNotNull(charlie)
        val development = dynChannelRepo.getById("Development")
        assertNotNull(development)

        val allMessagesFromDevelopmentSentByCharlie =
            dynMsgRepo
                .findAll()
                .whereEquals(Message::channel, development.name)
                .orderBy(Message::timestamp)
                .whereEquals(Message::user, charlie.id)
                .iterator()

        val newMessage =
            dynMsgRepo.insert(
                "The lazy implementation its done, now we can test it.",
                LocalDate.now().toEpochDay(),
                development,
                charlie,
            )

        assertEquals(
            newMessage.content,
            allMessagesFromDevelopmentSentByCharlie.next().content,
        )
        assertEquals(
            "Only include relevant and reliable contents.",
            allMessagesFromDevelopmentSentByCharlie.next().content,
        )
        assertFalse { allMessagesFromDevelopmentSentByCharlie.hasNext() }

        dynMsgRepo.deleteById(newMessage.id)
    }
}
