package pt.isel.chat.repositories

import pt.isel.Insert
import pt.isel.Repository
import pt.isel.chat.User
import java.sql.Date

interface UserRepository : Repository<Long, User> {
    @Insert
    fun insert(
        name: String,
        birthdate: Date,
        email: String,
    ): User
}
