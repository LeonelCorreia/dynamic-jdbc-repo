package pt.isel

import java.lang.classfile.CodeBuilder
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.ConstantDescs.INIT_NAME
import java.lang.constant.MethodTypeDesc
import java.sql.Date
import java.sql.SQLException
import kotlin.reflect.KClass
import kotlin.reflect.KClassifier
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotations
import kotlin.reflect.full.primaryConstructor

fun buildInsertQuery(
    tableName: String,
    params: List<String>,
): String {
    val columnNames = params.joinToString(", ") { it }
    val placeholders = params.joinToString(", ") { "?" }

    return "INSERT INTO $tableName ($columnNames) VALUES ($placeholders)"
}

fun columnsNames(
    domainKcls: KClass<*>,
    params: List<KParameter>,
): List<String> {
    val paramInfo = params.mapNotNull { it.name?.let { name -> name to it.type } }.toMap()

    return domainKcls.primaryConstructor
        ?.parameters
        ?.filter { ctorParam ->
            val paramType = paramInfo[ctorParam.name]
            paramType != null && paramType == ctorParam.type
        }?.map { ctorParam ->
            ctorParam
                .findAnnotations(Column::class)
                .firstOrNull()
                ?.name
                ?: ctorParam.name.orEmpty()
        }
        ?: error("No matching constructor found for $domainKcls with params $params")
}

data class ParamInfo(
    val slot: Int,
    val name: String,
    val classifier: KClassifier,
)

fun KClass<*>.hasSerialPrimaryKey(): Boolean =
    declaredMemberProperties.any { prop ->
        val hasPkAnnotation = prop.findAnnotations(Pk::class).isNotEmpty()
        val isIntOrLong = prop.returnType.classifier == Int::class || prop.returnType.classifier == Long::class
        hasPkAnnotation && isIntOrLong
    }

/**
 * Creates a new instance of SQLException with the given message.
 */
fun CodeBuilder.createSqlException(message: String) {
    new_(SQLException::class.descriptor())
    dup()
    ldc(constantPool().stringEntry(message))
    invokespecial(
        SQLException::class.descriptor(),
        INIT_NAME,
        MethodTypeDesc.of(CD_void, String::class.descriptor()),
    )
}

fun List<KParameter>.resolveRelationShips(): List<ParamInfo> {
    val firstOffsetIndex =
        indexOfFirst {
            it.type.classifier == Long::class || it.type.classifier == Double::class
        }

    return mapIndexed { idx, kparam ->
        val paramName = kparam.name ?: error("Missing parameter name")
        val applyOffset = firstOffsetIndex != -1 && idx > firstOffsetIndex
        val paramSlot = kparam.index + if (applyOffset) 1 else 0

        if (!kparam.javaClass.isPrimitive && !kparam.type.javaClass.isEnum) {
            // Go to auxRepos and get the entity by the pk if it came as null
            // throw Exception else map the relation to the pk value
            ParamInfo(paramSlot, paramName, kparam.type.classifier ?: error("Missing classifier"))
        } else {
            ParamInfo(paramSlot, paramName, kparam.type.classifier ?: error("Missing classifier"))
        }
    }
}

fun List<KParameter>.toParamInfo(): List<ParamInfo> {
    val firstOffsetIndex =
        indexOfFirst {
            it.type.classifier == Long::class || it.type.classifier == Double::class
        }

    return mapIndexed { idx, kparam ->
        val applyOffset = firstOffsetIndex != -1 && idx > firstOffsetIndex
        val paramSlot = kparam.index + if (applyOffset) 1 else 0

        ParamInfo(
            paramSlot,
            kparam.name ?: error("Missing parameter name"),
            kparam.type.classifier ?: error("Missing classifier"),
        )
    }
}

fun KClass<*>.hasRelationships(): Boolean =
    primaryConstructor?.parameters?.any {
        !it.type.javaClass.isPrimitive && !it.type.javaClass.isEnum
    } ?: error("No constructor found")

fun CodeBuilder.setValue(parameter: ParamInfo) {
    val (methodName, methodDesc) =
        when {
            (parameter.classifier as KClass<*>).java.isEnum -> {
                "setObject" to
                    MethodTypeDesc.of(
                        Unit::class.descriptor(),
                        Int::class.descriptor(),
                        Any::class.descriptor(),
                        Int::class.descriptor(),
                    )
            }
            parameter.classifier == String::class -> {
                "setString" to
                    MethodTypeDesc.of(
                        Unit::class.descriptor(),
                        Int::class.descriptor(),
                        String::class.descriptor(),
                    )
            }
            parameter.classifier == Int::class ->
                "setInt" to
                    MethodTypeDesc.of(
                        Unit::class.descriptor(),
                        Int::class.descriptor(),
                        Int::class.descriptor(),
                    )
            parameter.classifier == Date::class ->
                "setDate" to
                    MethodTypeDesc.of(
                        Unit::class.descriptor(),
                        Int::class.descriptor(),
                        Date::class.descriptor(),
                    )
            parameter.classifier == Long::class ->
                "setLong" to
                    MethodTypeDesc.of(
                        Unit::class.descriptor(),
                        Int::class.descriptor(),
                        Long::class.descriptor(),
                    )
            parameter.classifier == Boolean::class ->
                "setBoolean" to
                    MethodTypeDesc.of(
                        Unit::class.descriptor(),
                        Int::class.descriptor(),
                        Boolean::class.descriptor(),
                    )
            else -> throw Exception("Unsupported type for set operation in prepared statement: ${parameter.classifier}")
        }

    invokeinterface(
        ClassDesc.of("java.sql.PreparedStatement"),
        methodName,
        methodDesc,
    )
}

/**
 * Gets the value of the primary key from the ResultSet.
 */
fun CodeBuilder.getPkValue(pkType: KType) {
    val (methodName, methodDesc) =
        when (pkType.classifier) {
            Int::class ->
                "getInt" to
                    MethodTypeDesc.of(
                        Int::class.descriptor(),
                        Int::class.descriptor(),
                    )
            Long::class ->
                "getLong" to
                    MethodTypeDesc.of(
                        Long::class.descriptor(),
                        Int::class.descriptor(),
                    )
            else -> throw Exception("Unsupported type for get operation from ResultSet: $pkType")
        }

    invokeinterface(
        ClassDesc.of("java.sql.ResultSet"),
        methodName,
        methodDesc,
    )
}

fun List<KParameter>.firstSlotAvailableAfterParams(): Int {
    val firstOffsetIndex =
        indexOfFirst {
            it.type.classifier == Long::class || it.type.classifier == Double::class
        }

    val lastSlotUsed =
        mapIndexed { idx, kparam ->
            val applyOffset = firstOffsetIndex != -1 && idx > firstOffsetIndex
            kparam.index + 1 + if (applyOffset) 1 else 0
        }.maxOrNull() ?: 0

    return lastSlotUsed + 1
}

fun CodeBuilder.loadParameter(
    slot: Int,
    classifier: KClassifier,
) {
    when (classifier) {
        Int::class, Boolean::class, Char::class, Byte::class, Short::class -> iload(slot)
        Long::class -> lload(slot)
        Double::class -> dload(slot)
        Float::class -> fload(slot)
        else -> aload(slot)
    }
}
