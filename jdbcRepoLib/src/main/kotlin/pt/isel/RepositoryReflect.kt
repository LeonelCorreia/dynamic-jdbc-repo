@file:Suppress("ktlint:standard:no-wildcard-imports")

package pt.isel

import java.sql.*
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty

data class SetPropInfo(
    val name: String,
    val param: KParameter,
    val setFun: PreparedStatement.(Any) -> Unit,
)

data class GetPropInfo(
    val kParam: KParameter,
    val getter: ResultSet.() -> Any,
    val columnName: String,
)

class RepositoryReflect<K : Any, T : Any>(
    connection: Connection,
    domainKlass: KClass<T>,
) : BaseRepository<K, T>(connection) {
    companion object {
        private val auxRepos = mutableMapOf<KClass<*>, RepositoryReflect<Any, Any>>()
    }

    init {
        addAuxRepos(domainKlass, connection, auxRepos as MutableMap<KClass<*>, BaseRepository<Any, Any>>)
    }

    override val classifiers = buildClassifiers(domainKlass)

    override val pk: KProperty<*> = buildPk(domainKlass)

    override val tableName: String = buildTableName(domainKlass)

    override val constructor: KFunction<T> = buildConstructor(domainKlass)

    override val props: Map<GetPropInfo, SetPropInfo?> =
        buildProps(
            constructor,
            classifiers,
            pk,
            tableName,
            auxRepos as MutableMap<KClass<*>, BaseRepository<Any, Any>>,
        )

    override fun mapRowToEntity(rs: ResultSet): T {
        val paramValues = props.keys.associate { (param, mapPropValue) -> param to rs.mapPropValue() }
        return constructor.callBy(paramValues)
    }
}
