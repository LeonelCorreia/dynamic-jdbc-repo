package pt.isel

import pt.isel.chat.Channel
import kotlin.test.Test
import kotlin.test.assertEquals

class RepositoryReflectTest {
    private val repository: Repository<String, Channel> =
        RepositoryReflect(FakeConnection(), Channel::class) // ChannelRepositoryJdbc(connection)

    @Test
    fun `getAll should return all channels`() {
        val channels: List<Channel> = repository.getAll()
        assertEquals(5, channels.size)
    }
}
