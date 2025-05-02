@file:Suppress("ktlint:standard:no-unused-imports")

package pt.isel.chat

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import pt.isel.Insert
import pt.isel.Repository
import pt.isel.loadDynamicRepo
import java.sql.Connection
import java.sql.Date
import java.sql.DriverManager
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

interface UserRepository : Repository<Long, User> {
    @Insert
    fun insert(
        name: String,
        email: String,
        birthdate: Date,
    ): User
}

class UserRepositoryTest {
    companion object {
        private val connection: Connection = DriverManager.getConnection(DB_URL)

        @JvmStatic
        fun repositories() =
            listOf<Repository<Long, User>>(
                // RepositoryReflect(connection, User::class),
                loadDynamicRepo(connection, User::class, UserRepository::class) as UserRepository,
            )
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `getAll should return all users`(repository: Repository<Long, User>) {
        val users: List<User> = repository.getAll()
        assertEquals(3, users.size)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `retrieve a user`(repository: Repository<Long, User>) {
        val alice = repository.getAll().first { it.name.contains("Alice") }
        val otherAlice = repository.getById(alice.id)
        assertNotNull(otherAlice)
        assertEquals(alice, otherAlice)
        assertNotSame(alice, otherAlice)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `update a user`(repository: Repository<Long, User>) {
        val bob = repository.getAll().first { it.name.contains("Bob") }
        val updatedBob = bob.copy(email = "bob@marley.dev")
        repository.update(updatedBob)
        val retrieved = repository.getById(bob.id)
        assertNotNull(retrieved)
        assertEquals(updatedBob, retrieved)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `delete a user`(repository: UserRepository) {
        val tarantino =
            repository.insert(
                "Tarantino",
                "pulp@fiction.com",
                Date.valueOf(LocalDate.of(1994, 1, 1)),
            )
        assertEquals(4, repository.getAll().size)
        repository.deleteById(tarantino.id)
        assertEquals(3, repository.getAll().size)
    }

    @ParameterizedTest
    @MethodSource("repositories")
    fun `insert a user`(repository: UserRepository) {
        val christopher =
            repository.insert(
                name = "Christopher Nolan",
                email = "inception@email.com",
                birthdate = Date.valueOf(LocalDate.of(1970, 7, 30)),
            )
        val id = christopher.id
        val retrieved = repository.getById(id)

        assertNotNull(retrieved)
        assertEquals(christopher.name, retrieved.name)
        assertEquals(christopher.email, retrieved.email)
        assertEquals(christopher.birthdate, retrieved.birthdate)
        repository.deleteById(id)
    }
}
