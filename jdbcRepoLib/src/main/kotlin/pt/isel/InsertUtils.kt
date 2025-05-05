@file:Suppress("ktlint:standard:no-wildcard-imports")

package pt.isel

import java.lang.classfile.CodeBuilder
import java.lang.constant.ConstantDescs.CD_void
import java.lang.constant.ConstantDescs.INIT_NAME
import java.lang.constant.MethodTypeDesc
import java.sql.Date
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.reflect.*
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

fun KClass<*>.getTableName(): String = findAnnotations(Table::class).firstOrNull()?.name ?: simpleName ?: error("Missing table name")

fun KClass<*>.getPrimaryKeyType(): KType =
    declaredMemberProperties.firstOrNull { it.findAnnotations(Pk::class).isNotEmpty() }?.returnType
        ?: error("Missing @Pk for insert operation")

fun ParamInfo.isEnum(): Boolean = cls.java.isEnum

fun ParamInfo.isRelation(): Boolean = !cls.java.isPrimitive && !cls.java.isEnum && cls != String::class && cls != Date::class

fun KClass<*>.primaryCtorArgs(): List<KParameter> = primaryConstructor?.parameters ?: error("Primary constructor not found for $this")

fun columnsNames(
    domainKcls: KClass<*>,
    params: List<ParamInfo>,
): List<String> =
    params.map { paramInfo ->
        paramInfo.ctorArg
            .findAnnotations(Column::class)
            .firstOrNull()
            ?.name
            ?: paramInfo.ctorArg.name
            ?: error("No name found for parameter ${paramInfo.ctorArg.name} in $domainKcls")
    }

data class ParamInfo(
    val slot: Int,
    val cls: KClass<*>,
    val ctorArg: KParameter,
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

fun List<KParameter>.toParamInfo(domainKcls: KClass<*>): List<ParamInfo> {
    val firstOffsetIndex =
        indexOfFirst {
            it.type.classifier == Long::class || it.type.classifier == Double::class
        }
    val ctorParams = domainKcls.primaryConstructor?.parameters ?: error("No primary constructor found for $domainKcls")

    return mapIndexed { idx, kparam ->
        val applyOffset = firstOffsetIndex != -1 && idx > firstOffsetIndex
        val paramSlot = kparam.index + if (applyOffset) 1 else 0
        val ctorParam =
            ctorParams.firstOrNull { it.name == kparam.name && it.type == kparam.type }
                ?: error("No matching constructor parameter found for ${kparam.name} in $domainKcls")

        ParamInfo(
            paramSlot,
            kparam.type.classifier as KClass<*>,
            ctorParam,
        )
    }
}

fun ParamInfo.convertParamInfoToPkInfo(): ParamInfo =
    copy(
        slot = slot,
        cls = cls.getPkProp().returnType.classifier as KClass<*>,
    )

fun KClass<*>.getPkProp(): KProperty<*> =
    declaredMemberProperties.firstOrNull { it.findAnnotations(Pk::class).isNotEmpty() }
        ?: error("No primary key found for class $this")

fun CodeBuilder.setValue(parameter: ParamInfo) {
    val (methodName, methodDesc) =
        when {
            parameter.cls.java.isEnum -> {
                "setObject" to
                    MethodTypeDesc.of(
                        Unit::class.descriptor(),
                        Int::class.descriptor(),
                        Any::class.descriptor(),
                        Int::class.descriptor(),
                    )
            }
            parameter.cls == String::class -> {
                "setString" to
                    MethodTypeDesc.of(
                        Unit::class.descriptor(),
                        Int::class.descriptor(),
                        String::class.descriptor(),
                    )
            }
            parameter.cls == Int::class ->
                "setInt" to
                    MethodTypeDesc.of(
                        Unit::class.descriptor(),
                        Int::class.descriptor(),
                        Int::class.descriptor(),
                    )
            parameter.cls == Date::class ->
                "setDate" to
                    MethodTypeDesc.of(
                        Unit::class.descriptor(),
                        Int::class.descriptor(),
                        Date::class.descriptor(),
                    )
            parameter.cls == Long::class ->
                "setLong" to
                    MethodTypeDesc.of(
                        Unit::class.descriptor(),
                        Int::class.descriptor(),
                        Long::class.descriptor(),
                    )
            parameter.cls == Boolean::class ->
                "setBoolean" to
                    MethodTypeDesc.of(
                        Unit::class.descriptor(),
                        Int::class.descriptor(),
                        Boolean::class.descriptor(),
                    )
            else -> throw Exception("Unsupported type for set operation in prepared statement: ${parameter.cls}")
        }

    invokeinterface(
        PreparedStatement::class.descriptor(),
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
        ResultSet::class.descriptor(),
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

fun KFunction<*>.isInsertMethodForKCls(kCls: KClass<*>) =
    parameters.matchConstructorParams(kCls) &&
        findAnnotations(Insert::class).isNotEmpty() &&
        returnType.classifier == kCls

fun List<KParameter>.matchConstructorParams(kClass: KClass<*>): Boolean {
    val constructorParams = kClass.primaryConstructor?.parameters ?: return false

    val (pkPropName, pkPropType) =
        kClass.declaredMemberProperties
            .firstOrNull { it.findAnnotations(Pk::class).isNotEmpty() }
            ?.let { it.name to it.returnType }
            ?: (null to null)
    // Filter out optional parameters and the PK property if it is of type Int or Long
    // because we assume that the Pk is a serial in the database when it is of these types.
    val requiredParams =
        constructorParams
            .filter { !it.isOptional }
            .filterNot { it.name == pkPropName && pkPropType?.classifier in setOf(Int::class, Long::class) }

    return requiredParams.all { requiredParam ->
        any { param -> param.name == requiredParam.name && param.type == requiredParam.type }
    }
}
