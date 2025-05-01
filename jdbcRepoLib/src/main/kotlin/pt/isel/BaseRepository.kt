package pt.isel

import java.sql.Connection
import java.sql.ResultSet
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty

abstract class BaseRepository<K : Any, T : Any>(
    protected val connection: Connection,
) : Repository<K, T> {
    abstract val classifiers: Map<KProperty<*>, KClass<*>>

    abstract val pk: KProperty<*>

    abstract val tableName: String

    abstract val constructor: KFunction<T>

    abstract val props: Map<GetPropInfo, SetPropInfo?>

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

    override fun update(entity: T) {
        val updates =
            props
                .values
                .filterNotNull()
                .map { it.name }
                .joinToString { "$it = ?" }
        val query = "UPDATE $tableName SET $updates WHERE ${pk.name} = ?"

        connection.prepareStatement(query).use { preparedStatement ->
            props.values
                .filterNotNull()
                .forEach { (_, _, setter) ->
                    preparedStatement.setter(entity)
                }

            val pkValue = pk.call(entity)
            preparedStatement.setObject(props.size, pkValue)
            preparedStatement.executeUpdate()
        }
    }

    abstract fun mapRowToEntity(rs: ResultSet): T

    /**
     * Helper function to retrieve the getter by a given parameter name
     *
     * @param columnName Name of the parameter to retrieve the getter of
     * @return The respective getter
     */
    fun findGetterByParamName(columnName: String): ResultSet.() -> Any = props.keys.first { prop -> prop.columnName == columnName }.getter
}
