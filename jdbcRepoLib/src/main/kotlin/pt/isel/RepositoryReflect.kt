@file:Suppress("ktlint:standard:no-wildcard-imports")

package pt.isel

import java.sql.*
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotations
import kotlin.reflect.full.primaryConstructor

private data class SetPropInfo(
    val name: String,
    val param: KParameter,
    val setFun: PreparedStatement.(Any) -> Unit,
)

private data class GetPropInfo(
    val kParam: KParameter,
    val getter: (ResultSet) -> Any,
)

class RepositoryReflect<K : Any, T : Any>(
    private val connection: Connection,
    private val domainKlass: KClass<T>,
) : Repository<K, T> {
    companion object {
        private val auxRepos = mutableMapOf<KClass<*>, RepositoryReflect<Any, Any>>()
    }

    private var pk: KProperty<*>

    init {
        domainKlass
            .declaredMemberProperties
            .also {
                pk = it.first { prop -> prop.findAnnotations(Pk::class).isNotEmpty() }
            }.forEach { prop ->
                val entityType =
                    (prop.returnType.classifier as? KClass<Any>)
                        ?: throw IllegalStateException("Invalid classifier for property: ${prop.name}")

                if (!prop.isPrimitiveOrStringOrDate() && !prop.isEnum()) {
                    val auxRepository = RepositoryReflect<Any, Any>(connection, entityType)
                    auxRepos[entityType] = auxRepository
                }
            }
    }

    private val constructor =
        domainKlass
            .primaryConstructor
            ?: throw Exception("No suitable constructor found for $domainKlass")

    private val props: Map<GetPropInfo, SetPropInfo?> =
        constructor.parameters.let {
            it
                .withIndex()
                .associate { (index, constParam) ->
                    val columnName =
                        constParam.findAnnotations(Column::class).firstOrNull()?.name ?: constParam.name ?: ""
                    val prop = domainKlass.declaredMemberProperties.first { prop -> prop.name == constParam.name }

                    val getProp =
                        GetPropInfo(
                            kParam = constParam,
                            getter = getValue(prop, columnName),
                        )

                    val setProp =
                        if (constParam.name == pk.name) {
                            null
                        } else {
                            SetPropInfo(columnName, constParam) { entity ->
                                val value = prop.call(entity)
                                setValue(value, index, prop)
                            }
                        }

                    getProp to setProp
                }
        }

    private val tableName =
        domainKlass
            .findAnnotations(Table::class)
            .firstOrNull()
            ?.name
            ?: throw IllegalArgumentException("Missing @Table annotation in class $domainKlass")

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

    override fun deleteById(id: K) {
        val query = "DELETE FROM $tableName WHERE ${pk.name} = ?"
        connection.prepareStatement(query).use { preparedStatement ->
            preparedStatement.setObject(1, id)
            preparedStatement.executeUpdate()
        }
    }

    private fun mapRowToEntity(rs: ResultSet): T {
        val paramValues = props.keys.associate { (param, mapPropValue) -> param to mapPropValue(rs) }
        return constructor.callBy(paramValues)
    }

    private fun ResultSet.getValueFromAuxRepo(
        kProperty: KProperty<*>,
        columnName: String,
    ): Any {
        val type = kProperty.returnType.classifier as KClass<*>
        val auxRepo = auxRepos[type] ?: throw Exception("No repository found for $type")
        val foreignKeyType = auxRepo.pk
        val pkValue = this.getPrimitiveStringDateValue(foreignKeyType, columnName)
        return auxRepo.getById(pkValue)!!
    }

    private fun getValue(
        kProperty: KProperty<*>,
        columnName: String,
    ): (ResultSet) -> Any =
        when {
            kProperty.isPrimitiveOrStringOrDate() -> { rs -> rs.getPrimitiveStringDateValue(kProperty, columnName) }
            kProperty.isEnum() -> { rs -> rs.getEnumValue(kProperty, columnName) }
            else -> { rs -> rs.getValueFromAuxRepo(kProperty, columnName) }
        }

    private fun PreparedStatement.setPrimitiveOrStringOrDate(
        value: Any?,
        index: Int,
        kProperty: KProperty<*>,
    ) = when (value) {
        is Boolean -> setBoolean(index, value)
        is Int -> setInt(index, value)
        is Long -> setLong(index, value)
        is String -> setString(index, value)
        is Date -> setDate(index, value)
        else -> throw Exception("Unsupported type ${kProperty.returnType.classifier}")
    }

    private fun PreparedStatement.setValueFromAuxRepo(
        value: Any?,
        index: Int,
        kProperty: KProperty<*>,
    ) {
        val entityKlass = kProperty.returnType.classifier as KClass<*>
        entityKlass.declaredMemberProperties
            .first { prop -> prop.findAnnotations(Pk::class).isNotEmpty() }
            .let { prop ->
                val type = kProperty.returnType.classifier as KClass<*>
                val auxRepo = auxRepos[type] ?: throw Exception("No repository found for $type")
                val fk = auxRepo.pk

                val fkValue = fk.call(value)

                setValue(fkValue, index, prop)
            }
    }

    private fun PreparedStatement.setValue(
        value: Any?,
        index: Int,
        kProperty: KProperty<*>,
    ): Unit =
        when {
            kProperty.isPrimitiveOrStringOrDate() -> {
                setPrimitiveOrStringOrDate(value, index, kProperty)
            }
            kProperty.isEnum() -> {
                val enumValue = value as Enum<*>
                setObject(index, enumValue.name, Types.OTHER) // Types.OTHER is for PostgreSQL
            }
            else -> {
                setValueFromAuxRepo(value, index, kProperty)
            }
        }
}

private fun KProperty<*>.isEnum() = (returnType.classifier as KClass<*>).java.isEnum

private fun ResultSet.getEnumValue(
    kProperty: KProperty<*>,
    columnName: String,
): Any {
    val enumName = getString(columnName)
    val enumClass = kProperty.returnType.classifier as KClass<*>
    return enumClass.java.enumConstants.first { (it as Enum<*>).name == enumName }
}

private fun KProperty<*>.isPrimitiveOrStringOrDate() =
    (returnType.classifier as KClass<*>)
        .java
        .isPrimitive ||
        returnType.classifier == String::class ||
        returnType.classifier == Date::class

private fun ResultSet.getPrimitiveStringDateValue(
    kProperty: KProperty<*>,
    columnName: String,
) = when (kProperty.returnType.classifier) {
    Boolean::class -> getBoolean(columnName)
    Int::class -> getInt(columnName)
    Long::class -> getLong(columnName)
    String::class -> getString(columnName)
    Date::class -> getDate(columnName)
    else -> throw Exception("Unsupported type ${kProperty.returnType.classifier}")
}
