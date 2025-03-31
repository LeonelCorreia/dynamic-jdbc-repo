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
    val getter: ResultSet.() -> Any,
)

class RepositoryReflect<K : Any, T : Any>(
    private val connection: Connection,
    domainKlass: KClass<T>,
) : Repository<K, T> {
    companion object {
        private val auxRepos = mutableMapOf<KClass<*>, RepositoryReflect<Any, Any>>()
    }

    private var pk: KProperty<*>
    private val classifiers = mutableMapOf<KProperty<*>, KClass<*>>()

    private val tableName =
        domainKlass
            .findAnnotations(Table::class)
            .firstOrNull()
            ?.name
            ?: throw IllegalArgumentException("Missing @Table annotation in class $domainKlass")

    init {
        domainKlass
            .declaredMemberProperties
            .also {
                pk = it.first { prop -> prop.findAnnotations(Pk::class).isNotEmpty() }
            }.forEach { prop ->
                val classifier =
                    (prop.returnType.classifier as? KClass<Any>)
                        ?: throw IllegalStateException("Invalid classifier for property: ${prop.name}")

                classifiers[prop] = classifier

                if (!classifier.isPrimitiveOrStringOrDate() && !classifier.isEnum()) {
                    val auxRepository = RepositoryReflect<Any, Any>(connection, classifier)
                    auxRepos[classifier] = auxRepository
                }
            }
    }

    private val constructor =
        domainKlass
            .primaryConstructor
            ?: throw IllegalStateException("No suitable constructor found for $domainKlass")

    private val props: Map<GetPropInfo, SetPropInfo?> =
        constructor.parameters.let {
            it
                .withIndex()
                .associate { (index, constParam) ->
                    val columnName =
                        constParam.findAnnotations(Column::class).firstOrNull()?.name
                            ?: constParam.name
                            ?: throw IllegalStateException("Missing name for column in table: $tableName.")
                    val prop = classifiers.keys.firstOrNull { prop -> prop.name == constParam.name }
                    checkNotNull(prop)

                    val classifier = classifiers[prop]
                    checkNotNull(classifier)

                    val getProp =
                        GetPropInfo(
                            constParam,
                            getValue(classifier, columnName),
                        )

                    val setProp =
                        if (constParam.name == pk.name) {
                            null
                        } else {
                            SetPropInfo(columnName, constParam) { entity ->
                                val value = prop.call(entity)
                                setValue(value, index, classifier)
                            }
                        }

                    getProp to setProp
                }
        }

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
        val paramValues = props.keys.associate { (param, mapPropValue) -> param to rs.mapPropValue() }
        return constructor.callBy(paramValues)
    }

    private fun ResultSet.getPrimitiveStringDateValue(
        classifier: KClass<*>,
        columnName: String,
    ) = when (classifier) {
        Boolean::class -> getBoolean(columnName)
        Int::class -> getInt(columnName)
        Long::class -> getLong(columnName)
        String::class -> getString(columnName)
        Date::class -> getDate(columnName)
        else -> throw Exception("Unsupported type $classifier")
    }

    private fun ResultSet.getEnumValue(
        classifier: KClass<*>,
        columnName: String,
    ): Any {
        val enumName = getString(columnName)
        return classifier.java.enumConstants.first { (it as Enum<*>).name == enumName }
    }

    private fun ResultSet.getValueFromAuxRepo(
        classifier: KClass<*>,
        columnName: String,
    ): Any {
        val auxRepo = auxRepos[classifier] ?: throw Exception("No repository found for $classifier")
        val foreignKeyType = auxRepo.classifiers[auxRepo.pk] ?: throw IllegalStateException("Missing pk in ${auxRepo.tableName}")
        val pkValue = this.getPrimitiveStringDateValue(foreignKeyType, columnName)
        return auxRepo.getById(pkValue)!!
    }

    private fun getValue(
        classifier: KClass<*>,
        columnName: String,
    ): (ResultSet) -> Any =
        when {
            classifier.isPrimitiveOrStringOrDate() -> { rs -> rs.getPrimitiveStringDateValue(classifier, columnName) }
            classifier.isEnum() -> { rs -> rs.getEnumValue(classifier, columnName) }
            else -> { rs -> rs.getValueFromAuxRepo(classifier, columnName) }
        }

    private fun PreparedStatement.setPrimitiveOrStringOrDate(
        value: Any?,
        index: Int,
        classifier: KClass<*>,
    ) = when (value) {
        is Boolean -> setBoolean(index, value)
        is Int -> setInt(index, value)
        is Long -> setLong(index, value)
        is String -> setString(index, value)
        is Date -> setDate(index, value)
        else -> throw Exception("Unsupported type $classifier")
    }

    private fun PreparedStatement.setValueFromAuxRepo(
        value: Any?,
        index: Int,
        classifier: KClass<*>,
    ) {
        val auxRepo = auxRepos[classifier] ?: throw Exception("No repository found for $classifier")
        val fk = auxRepo.pk

        val fkValue = fk.call(value)

        val fkClassifier = auxRepo.classifiers[fk]
        checkNotNull(fkClassifier)

        setValue(fkValue, index, fkClassifier)
    }

    private fun PreparedStatement.setValue(
        value: Any?,
        index: Int,
        classifier: KClass<*>,
    ): Unit =
        when {
            classifier.isPrimitiveOrStringOrDate() -> {
                setPrimitiveOrStringOrDate(value, index, classifier)
            }
            classifier.isEnum() -> {
                val enumValue = value as Enum<*>
                setObject(index, enumValue.name, Types.OTHER) // Types.OTHER is for PostgreSQL
            }
            else -> {
                setValueFromAuxRepo(value, index, classifier)
            }
        }

    private fun KClass<*>.isEnum() = java.isEnum

    private fun KClass<*>.isPrimitiveOrStringOrDate() =
        java.isPrimitive ||
            this == String::class ||
            this == Date::class
}
