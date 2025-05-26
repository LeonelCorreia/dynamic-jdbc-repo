package pt.isel

import java.lang.classfile.CodeBuilder
import java.lang.constant.MethodTypeDesc
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

fun buildUpdateQuery(
    tableName: String,
    pkName: String,
    columnNames: List<String>,
): String {
    val setClause = columnNames.joinToString(", ") { "$it = ?" }
    return "UPDATE $tableName SET $setClause WHERE $pkName = ?"
}

fun KClass<*>.getColumnNames(): List<String> {
    val ctorParams = primaryCtorArgs()
    val propsByName = memberProperties.associateBy { it.name }

    return ctorParams.mapNotNull { param ->
        val paramName = param.name ?: return@mapNotNull null
        val prop = propsByName[paramName] ?: return@mapNotNull null
        if (prop.contains(Pk::class)) return@mapNotNull null

        val columnName = param.columnName()
        columnName.ifBlank { paramName }
    }
}

fun CodeBuilder.setPreparedStatementParamsFromObject(
    domainKlass: KClass<*>,
    stmtSlot: Int,
    objectSlot: Int,
    insertParams: List<ParamInfo>,
): Int {
    insertParams.forEachIndexed { index, param ->
        // Load PreparedStatement (1st argument)
        // Load parameter index (starts from 1 in JDBC)
        loadPreparedStatement(stmtSlot, index)
        loadObject(domainKlass, objectSlot)
        generateGetter(domainKlass, param.ctorArg.name ?: "", param.cls)

        handleSpecialTypes(param)
        setValue(if (param.isRelation()) param.convertParamInfoToPkInfo() else param)
    }
    return insertParams.size
}

fun CodeBuilder.setPrimaryKeyParameter(
    domainKlass: KClass<*>,
    stmtSlot: Int,
    objectSlot: Int,
    paramIndex: Int,
) {
    loadPreparedStatement(stmtSlot, paramIndex)
    loadObject(domainKlass, objectSlot)

    val pkProp = domainKlass.getPkProp()
    generateGetter(domainKlass, pkProp.name, pkProp.returnType.classifier as KClass<*>)

    val pkParam =
        buildConstructor(domainKlass).parameters.find {
            it.name == pkProp.name
        } ?: throw IllegalStateException("Primary key parameter not found in constructor")

    setValue(
        ParamInfo(
            slot = -1,
            cls = pkProp.returnType.classifier as KClass<*>,
            ctorArg = pkParam,
        ),
    )
}

fun CodeBuilder.loadPreparedStatement(
    stmtSlot: Int,
    paramIndex: Int,
) {
    aload(stmtSlot)
    bipush(paramIndex + 1)
}

private fun CodeBuilder.loadObject(
    domainClass: KClass<*>,
    objectSlot: Int,
) {
    aload(objectSlot)
    checkcast(domainClass.descriptor())
}

private fun CodeBuilder.generateGetter(
    domainClass: KClass<*>,
    propertyName: String,
    propertyClass: KClass<*>,
) {
    val getterName =
        if (propertyClass == Boolean::class) {
            propertyName
        } else {
            "get${propertyName.replaceFirstChar { it.uppercase() }}"
        }
    invokevirtual(
        domainClass.descriptor(),
        getterName,
        MethodTypeDesc.of(propertyClass.descriptor()),
    )
}

fun CodeBuilder.handleSpecialTypes(param: ParamInfo) {
    when {
        param.isRelation() -> handleRelationType(param)
        param.cls.isEnum() -> handleEnumType(param)
    }
}

fun CodeBuilder.handleRelationType(param: ParamInfo) {
    val pkProperty = param.cls.getPkProp()
    generateGetter(param.cls, pkProperty.name, pkProperty.returnType.classifier as KClass<*>)
}

fun CodeBuilder.handleEnumType(param: ParamInfo) {
    invokevirtual(
        param.cls.descriptor(),
        "name",
        MethodTypeDesc.of(String::class.descriptor()),
    )
    sipush(1111) // Types.OTHER para enum
}
