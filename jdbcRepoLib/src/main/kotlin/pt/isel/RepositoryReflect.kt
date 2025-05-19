@file:Suppress("ktlint:standard:no-wildcard-imports")

package pt.isel

import java.sql.*
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty

class RepositoryReflect<K : Any, T : Any>(
    connection: Connection,
    domainKlass: KClass<T>,
) : BaseRepository<K, T>(connection) {
    companion object {
        private val auxRepos = mutableMapOf<KClass<*>, BaseRepository<Any, Any>>()
    }

    init {
        addAuxRepos(domainKlass, connection, auxRepos)
    }

    val classifiers = buildClassifiers(domainKlass)

    override val pk: KProperty<*> = buildPk(domainKlass)

    override val tableName: String = buildTableName(domainKlass)

    private val constructor: KFunction<T> = buildConstructor(domainKlass)

    override val properties = buildPropList(constructor, classifiers, tableName)

    private val props: Map<GetPropInfo, SetPropInfo?> =
        buildPropsMap(
            constructor,
            classifiers,
            pk,
            tableName,
            auxRepos,
        )

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

    override fun mapRowToEntity(rs: ResultSet): T {
        val paramValues =
            props.keys.associate { getPropInfo ->
                getPropInfo as GetPropInfo.Reflect
                val getter = getPropInfo.getter
                getPropInfo.kParam to getter(rs)
            }

        return constructor.callBy(paramValues)
    }
}
