package pt.isel.chat.repositories

import pt.isel.Insert
import pt.isel.Repository
import pt.isel.chat.Channel
import pt.isel.chat.Message
import pt.isel.chat.User

interface MessageRepository : Repository<Long, Message> {
    @Insert
    fun insert(
        content: String,
        timestamp: Long,
        channel: Channel,
        user: User,
    ): Message
}
