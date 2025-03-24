package pt.isel

import java.sql.*
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotations
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.internal.impl.metadata.ProtoBuf.Type

class RepositoryReflect<K : Any, T : Any>(
    private val connection: Connection,
    private val domainKlass: KClass<T>,
) : Repository<K, T> {
    companion object {
        private val auxRepos = mutableMapOf<KClass<*>, RepositoryReflect<Any, Any>>()
    }

    init {
        domainKlass
            .declaredMemberProperties
            .forEach { prop ->
                val entityType = prop.returnType.classifier as KClass<Any>

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

    private val props: Map<KParameter, (ResultSet) -> Any> =
        constructor.parameters.associate { constParam ->
            val prop = domainKlass.declaredMemberProperties.first { it.name == constParam.name }
            val columnName = constParam.findAnnotations(Column::class).firstOrNull()?.name ?: prop.name
            when {
                prop.isPrimitiveOrStringOrDate() -> constParam to { rs -> rs.getValue(prop, columnName) }
                prop.isEnum() -> constParam to { rs -> rs.getEnumValue(prop, columnName) }
                else ->
                    constParam to { rs ->
                        val type = prop.returnType.classifier as KClass<*>
                        val auxRepo = auxRepos[type] ?: throw Exception("No repository found for $type")
                        val foreignKeyType = auxRepo.pk
                        val pkValue = rs.getValue(foreignKeyType, columnName)
                        auxRepo.getById(pkValue)!!
                    }
            }
        }

    private val pk =
        domainKlass
            .declaredMemberProperties
            .first { prop -> prop.findAnnotations(Pk::class).isNotEmpty() }

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
        val updates = constructor.parameters
            .joinToString(", ") { param ->
                val columnName = param.findAnnotations(Column::class).firstOrNull()?.name ?: param.name
                "$columnName = ?"
            }
        val query = "UPDATE $tableName SET $updates WHERE ${pk.name} = ?"

        connection.prepareStatement(query).use { preparedStatement ->
            var index = 1
            constructor.parameters
                .filter { param -> param.findAnnotations(Pk::class).isEmpty() }
                .forEach { param ->
                    val prop = domainKlass.declaredMemberProperties.first { it.name == param.name }
                    val value = prop.getter.call(entity)
                    index = setPreparedStatementValue(preparedStatement, index, value, prop)
                }
            val pkValue = pk.getter.call(entity)
            preparedStatement.setObject(index, pkValue)
            preparedStatement.executeUpdate()
        }
    }

    private fun setPreparedStatementValue(
        preparedStatement: PreparedStatement,
        index: Int,
        value: Any?,
        prop: KProperty<*>
    ): Int {
        return when {
            (prop.returnType.classifier as KClass<*>).java.isEnum -> {
                val enumValue = value as Enum<*>
                preparedStatement.setObject(index, enumValue.name, Types.OTHER) // Types.OTHER is for PostgreSQL
                index + 1
            }
            prop.isPrimitiveOrStringOrDate() -> {
                when (value) {
                    is Boolean -> preparedStatement.setBoolean(index, value)
                    is Int -> preparedStatement.setInt(index, value)
                    is Long -> preparedStatement.setLong(index, value)
                    is String -> preparedStatement.setString(index, value)
                    is Date -> preparedStatement.setDate(index, value)
                    else -> throw Exception("Unsupported type ${prop.returnType.classifier}")
                }
                index + 1
            }
            else -> {
                check(value != null) { "Nested entity is null for property ${prop.name}" }
                setPreparedStatementValues(preparedStatement, index, value, prop.returnType.classifier as KClass<*>)
            }
        }
    }

    private fun setPreparedStatementValues(
        preparedStatement: PreparedStatement,
        startIndex: Int,
        entity: Any,
        entityKlass: KClass<*>
    ): Int {
        var index = startIndex
        entityKlass.declaredMemberProperties
            .first { prop -> prop.findAnnotations(Pk::class).isNotEmpty() }
            .let { prop ->
                val value = prop.getter.call(entity)
                index = setPreparedStatementValue(preparedStatement, index, value, prop)
            }
        return index
    }

    override fun deleteById(id: K) {
        val query = "DELETE FROM $tableName WHERE ${pk.name} = ?"
        connection.prepareStatement(query).use { preparedStatement ->
            preparedStatement.setObject(1, id)
            preparedStatement.executeUpdate()
        }
    }

    private fun mapRowToEntity(rs: ResultSet): T {
        val paramValues = props.mapValues { (_, mapPropValue) -> mapPropValue(rs) }
        return constructor.callBy(paramValues)
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

private fun ResultSet.getValue(
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
