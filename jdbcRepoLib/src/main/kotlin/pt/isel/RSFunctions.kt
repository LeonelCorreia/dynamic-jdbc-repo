package pt.isel

import java.sql.Date
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.reflect.KClass

fun ResultSet.getPrimitiveStringDateValue(
    classifier: KClass<*>,
    columnName: String,
): Any =
    when (classifier) {
        Boolean::class -> getBoolean(columnName)
        Int::class -> getInt(columnName)
        Long::class -> getLong(columnName)
        String::class -> getString(columnName)
        Date::class -> getDate(columnName)
        else -> throw Exception("Unsupported type $classifier")
    }

fun ResultSet.getEnumValue(
    classifier: KClass<*>,
    columnName: String,
): Any {
    val enumName = getString(columnName)
    return classifier.java.enumConstants.first { (it as Enum<*>).name == enumName }
}

fun ResultSet.getValueFromAuxRepo(
    classifier: KClass<*>,
    columnName: String,
    auxRepos: MutableMap<KClass<*>, BaseRepository<Any, Any>>,
): Any {
    val auxRepo = auxRepos[classifier] ?: throw Exception("No repository found for $classifier")
    auxRepo as RepositoryReflect
    val foreignKeyType = auxRepo.classifiers[auxRepo.pk] ?: throw IllegalStateException("Missing pk in ${auxRepo.tableName}")
    val pkValue = this.getPrimitiveStringDateValue(foreignKeyType, columnName)
    return auxRepo.getById(pkValue)!!
}

fun getValue(
    classifier: KClass<*>,
    columnName: String,
    auxRepos: MutableMap<KClass<*>, BaseRepository<Any, Any>>,
): (ResultSet) -> Any =
    when {
        classifier.isPrimitiveOrStringOrDate() -> { rs -> rs.getPrimitiveStringDateValue(classifier, columnName) }
        classifier.isEnum() -> { rs -> rs.getEnumValue(classifier, columnName) }
        else -> { rs -> rs.getValueFromAuxRepo(classifier, columnName, auxRepos) }
    }

fun PreparedStatement.setPrimitiveOrStringOrDate(
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

fun PreparedStatement.setValueFromAuxRepo(
    value: Any?,
    index: Int,
    classifier: KClass<*>,
    auxRepos: MutableMap<KClass<*>, BaseRepository<Any, Any>>,
) {
    val auxRepo = auxRepos[classifier] ?: throw Exception("No repository found for $classifier")
    auxRepo as RepositoryReflect<Any, Any>
    val fk = auxRepo.pk

    val fkValue = fk.call(value)

    val fkClassifier = auxRepo.classifiers[fk]
    checkNotNull(fkClassifier)

    setValue(fkValue, index, fkClassifier, auxRepos)
}

fun PreparedStatement.setValue(
    value: Any?,
    index: Int,
    classifier: KClass<*>,
    auxRepos: MutableMap<KClass<*>, BaseRepository<Any, Any>>,
) = when {
    classifier.isPrimitiveOrStringOrDate() -> {
        setPrimitiveOrStringOrDate(value, index, classifier)
    }
    classifier.isEnum() -> {
        val enumValue = value as Enum<*>
        setObject(index, enumValue.name, Types.OTHER) // Types.OTHER is for PostgreSQL
    }
    else -> {
        setValueFromAuxRepo(value, index, classifier, auxRepos)
    }
}

fun KClass<*>.isEnum() = java.isEnum

fun KClass<*>.isPrimitiveOrStringOrDate() =
    java.isPrimitive ||
        this == String::class ||
        this == Date::class ||
        isPrimitiveBoxed()

fun KClass<*>.isPrimitiveBoxed() =
    this == Integer::class ||
        this == Long::class ||
        this == Double::class ||
        this == Character::class ||
        this == Boolean::class ||
        this == Short::class ||
        this == Byte::class ||
        this == Float::class
