package pt.isel

import pt.isel.sports.FakeSportsConnection
import pt.isel.sports.President
import kotlin.test.Test
import kotlin.test.assertEquals

class RepositoryDynamicTest {
    private val repository: Repository<String, President> =
        loadDynamicRepo(FakeSportsConnection(), President::class)

    @Test
    fun `getAll should return all presidents`() {
        val channels: List<President> = repository.getAll()
        assertEquals(2, channels.size)
    }
}