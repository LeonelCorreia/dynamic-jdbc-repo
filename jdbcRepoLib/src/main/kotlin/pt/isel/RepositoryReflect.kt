package pt.isel

import java.sql.Connection
import kotlin.reflect.KClass

class RepositoryReflect<K : Any, T : Any>(
    private val connection: Connection,
    private val domainKlass: KClass<T>,
) : Repository<K, T> {
    override fun getById(id: K): T? {
        TODO("Not yet implemented")
    }

    override fun getAll(): List<T> {
        TODO("Not yet implemented")
    }

    override fun update(entity: T) {
        TODO("Not yet implemented")
    }

    override fun deleteById(id: K) {
        TODO("Not yet implemented")
    }
}
