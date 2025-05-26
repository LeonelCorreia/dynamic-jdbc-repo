package pt.isel

import java.lang.classfile.CodeBuilder
import java.lang.constant.MethodTypeDesc
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

fun buildUpdateQuery(
    tableName: String,
    pkName: String,
    columnNames: List<String>,
): String {
    val setClause = columnNames.joinToString(", ") { "$it = ?" }
    return "UPDATE $tableName SET $setClause WHERE $pkName = ?"
}

fun KClass<*>.getColumnNames(): List<String> {
    val ctorParams = primaryConstructor?.parameters ?: return emptyList()
    val propsByName = memberProperties.associateBy { it.name }

    return ctorParams.mapNotNull { param ->
        val name = param.name ?: return@mapNotNull null
        val prop = propsByName[name] ?: return@mapNotNull null
        if (prop.findAnnotation<Pk>() != null) return@mapNotNull null

        val columnAnnotation = param.findAnnotation<Column>()
        columnAnnotation?.name?.takeIf { it.isNotBlank() } ?: name
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
        aload(stmtSlot)
        // Load parameter index (starts from 1 in JDBC)
        bipush(index + 1)

        // Load the object
        aload(objectSlot)
        checkcast(domainKlass.descriptor())

        // Call getter for the field
        val getterName =
            if (param.cls == Boolean::class) {
                param.ctorArg.name
            } else {
                "get" + param.ctorArg.name!!.replaceFirstChar { it.uppercase() }
            }
        invokevirtual(
            domainKlass.descriptor(),
            getterName,
            MethodTypeDesc.of(param.cls.descriptor()),
        )

        // If it's a relation, call its PK getter
        if (param.isRelation()) {
            val pkProp = param.cls.getPkProp()
            val pkGetter = "get" + pkProp.name.replaceFirstChar { it.uppercase() }
            invokevirtual(
                param.cls.descriptor(),
                pkGetter,
                MethodTypeDesc.of(pkProp.returnType.descriptor()),
            )
        }

        // If it's an enum, call name()
        if (param.cls.isEnum()) {
            invokevirtual(
                param.cls.descriptor(),
                "name",
                MethodTypeDesc.of(String::class.descriptor()),
            )
            // set JDBC type to VARCHAR (1111 = Types.OTHER, or change to Types.VARCHAR if needed)
            sipush(1111)
        }

        // Finally, call setValue
        val stmtParam = if (param.isRelation()) param.convertParamInfoToPkInfo() else param
        setValue(stmtParam)
    }
    return insertParams.size
}

fun CodeBuilder.setPrimaryKeyParameter(
    domainKlass: KClass<*>,
    stmtSlot: Int,
    objectSlot: Int,
    paramIndex: Int,
) {
    aload(stmtSlot)
    bipush(paramIndex + 1)

    // Load the object
    aload(objectSlot)
    checkcast(domainKlass.descriptor())

    val pkProp = domainKlass.getPkProp()
    val pkGetter = "get" + pkProp.name.replaceFirstChar { it.uppercase() }

    // Call getter for the field
    invokevirtual(
        domainKlass.descriptor(),
        pkGetter,
        MethodTypeDesc.of(pkProp.returnType.descriptor()),
    )

    val pkParam =
        buildConstructor(domainKlass).parameters.find {
            it.name == pkProp.name
        } ?: throw IllegalStateException("Primary key parameter not found in constructor")

    val pkParamInfo =
        ParamInfo(
            slot = -1,
            cls = pkProp.returnType.classifier as KClass<*>,
            ctorArg = pkParam,
        )
    setValue(pkParamInfo)
}
