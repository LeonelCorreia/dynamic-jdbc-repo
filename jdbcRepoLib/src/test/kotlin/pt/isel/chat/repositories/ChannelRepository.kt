package pt.isel.chat.repositories

import pt.isel.Insert
import pt.isel.Repository
import pt.isel.chat.Channel
import pt.isel.chat.ChannelType

interface ChannelRepository : Repository<String, Channel> {
    @Insert
    fun insert(
        name: String,
        type: ChannelType,
        createdAt: Long,
        isArchived: Boolean,
        maxMessageLength: Int,
        maxMembers: Int,
        isReadOnly: Boolean,
        lastMessageTimestamp: Long,
    ): Channel
}
