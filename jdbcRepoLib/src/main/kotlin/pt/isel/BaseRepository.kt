package pt.isel

import java.lang.classfile.CodeBuilder
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty

data class SetPropInfo(
    val name: String,
    val param: KParameter,
    val setFun: PreparedStatement.(Any) -> Unit,
)

sealed class GetPropInfo {
    abstract val kParam: KParameter
    abstract val columnName: String

    data class Reflect(
        override val kParam: KParameter,
        val getter: ResultSet.() -> Any,
        override val columnName: String,
    ) : GetPropInfo()

    data class Dynamic(
        override val kParam: KParameter,
        val getter: CodeBuilder.() -> Unit,
        override val columnName: String,
    ) : GetPropInfo()
}

abstract class BaseRepository<K : Any, T : Any>(
    protected val connection: Connection,
) : Repository<K, T> {
    abstract val pk: KProperty<*>

    abstract val tableName: String

    override fun getById(id: K): T? {
        val query = "SELECT * FROM $tableName WHERE ${pk.name} = ?"
        connection.prepareStatement(query).use { preparedStatement ->
            preparedStatement.setObject(1, id)
            preparedStatement.executeQuery().use { resultSet ->
                return if (resultSet.next()) mapRowToEntity(resultSet) else null
            }
        }
    }

    override fun getAll(): List<T> {
        val query = "SELECT * FROM $tableName"
        connection.prepareStatement(query).use { preparedStatement ->
            preparedStatement.executeQuery().use { resultSet ->
                val entities = mutableListOf<T>()
                while (resultSet.next()) {
                    entities.add(mapRowToEntity(resultSet))
                }
                return entities
            }
        }
    }

    override fun deleteById(id: K) {
        val query = "DELETE FROM $tableName WHERE ${pk.name} = ?"
        connection.prepareStatement(query).use { preparedStatement ->
            preparedStatement.setObject(1, id)
            preparedStatement.executeUpdate()
        }
    }

    abstract fun mapRowToEntity(rs: ResultSet): T
}
