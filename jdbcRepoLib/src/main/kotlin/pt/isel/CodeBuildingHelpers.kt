@file:Suppress("ktlint:standard:no-wildcard-imports")

package pt.isel

import java.lang.classfile.CodeBuilder
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.*
import java.lang.constant.MethodTypeDesc
import java.sql.Date
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.full.findAnnotations

private const val RESULTSET_SLOT = 1

fun buildDynamicGetPropInfo(
    domainKlass: KClass<*>,
    generatedKlassDesc: ClassDesc,
    classifiers: MutableMap<KProperty<*>, KClass<*>>,
    tableName: String,
    auxRepos: MutableMap<KClass<*>, BaseRepository<Any, Any>>,
): List<GetPropInfo> {
    val constructor = buildConstructor(domainKlass)
    return constructor.parameters.let {
        it
            .map { constParam ->
                val columnName =
                    constParam.findAnnotations(Column::class).firstOrNull()?.name
                        ?: constParam.name
                        ?: throw IllegalStateException("Missing name for column in table $tableName.")
                val prop = classifiers.keys.firstOrNull { prop -> prop.name == constParam.name }
                checkNotNull(prop)

                val classifier = classifiers[prop]
                checkNotNull(classifier)

                val getDynProp =
                    GetPropInfo.Dynamic(
                        constParam,
                        getDynCode(generatedKlassDesc, classifier, columnName, auxRepos),
                        columnName,
                    )

                getDynProp
            }
    }
}

fun CodeBuilder.getPrimitiveStringDateDynCode(
    classifier: KClass<*>,
    columnName: String,
) {
    when (classifier) {
        Boolean::class -> getBoolean(columnName)
        Int::class -> getInt(columnName)
        Long::class -> getLong(columnName)
        String::class -> getString(columnName)
        Date::class -> getDate(columnName)
        else -> throw Exception("Unsupported type $classifier")
    }
}

fun CodeBuilder.getEnumDynCode(
    classifier: KClass<*>,
    columnName: String,
) {
    ldc(classifier.descriptor())
    getString(columnName)
    invokestatic(
        CD_Enum,
        "valueOf",
        MethodTypeDesc.of(
            CD_Enum,
            CD_Class,
            CD_String,
        ),
    )
    checkcast(classifier.descriptor())
}

fun CodeBuilder.getDynCodeFromAuxRepo(
    generatedKlassDesc: ClassDesc,
    classifier: KClass<*>,
    columnName: String,
    auxRepos: MutableMap<KClass<*>, BaseRepository<Any, Any>>,
) {
    val auxRepo = auxRepos[classifier] ?: throw Exception("No repository found for $classifier")
    val foreignKeyType = auxRepo.pk.returnType.classifier as KClass<*>
    aload(THIS_SLOT)
    // get the auxRepos instance to call getById
    getfield(
        generatedKlassDesc,
        auxRepo::class.simpleName,
        SUPER_CLASS_DESC,
    )
    getPrimitiveStringDateDynCode(foreignKeyType, columnName)
    if (foreignKeyType.isPrimitiveBoxed()) box(foreignKeyType)
    // call getById
    invokevirtual(
        BaseRepository::class.descriptor(),
        "getById",
        MethodTypeDesc.of(
            CD_Object,
            CD_Object,
        ),
    )
    checkcast(classifier.descriptor())
}

fun getDynCode(
    generatedKlassDesc: ClassDesc,
    classifier: KClass<*>,
    columnName: String,
    auxRepos: MutableMap<KClass<*>, BaseRepository<Any, Any>>,
): CodeBuilder.() -> Unit =
    when {
        classifier.isPrimitiveOrStringOrDate() -> {
            { getPrimitiveStringDateDynCode(classifier, columnName) }
        }
        classifier.isEnum() -> {
            { getEnumDynCode(classifier, columnName) }
        }
        else -> {
            { getDynCodeFromAuxRepo(generatedKlassDesc, classifier, columnName, auxRepos) }
        }
    }

fun CodeBuilder.getBoolean(columnName: String) {
    aload(RESULTSET_SLOT)
    ldc(constantPool().stringEntry(columnName))
    invokeinterface(
        RESULTSET_DESC,
        "getBoolean",
        MethodTypeDesc.of(
            CD_boolean,
            CD_String,
        ),
    )
}

fun CodeBuilder.getInt(columnName: String) {
    aload(RESULTSET_SLOT)
    ldc(constantPool().stringEntry(columnName))
    invokeinterface(
        RESULTSET_DESC,
        "getInt",
        MethodTypeDesc.of(
            CD_int,
            CD_String,
        ),
    )
}

fun CodeBuilder.getLong(columnName: String) {
    aload(RESULTSET_SLOT)
    ldc(constantPool().stringEntry(columnName))
    invokeinterface(
        RESULTSET_DESC,
        "getLong",
        MethodTypeDesc.of(
            CD_long,
            CD_String,
        ),
    )
}

fun CodeBuilder.getString(columnName: String) {
    aload(RESULTSET_SLOT)
    ldc(constantPool().stringEntry(columnName))
    invokeinterface(
        RESULTSET_DESC,
        "getString",
        MethodTypeDesc.of(
            CD_String,
            CD_String,
        ),
    )
}

fun CodeBuilder.getDate(columnName: String) {
    aload(RESULTSET_SLOT)
    ldc(constantPool().stringEntry(columnName))
    invokeinterface(
        RESULTSET_DESC,
        "getDate",
        MethodTypeDesc.of(
            Date::class.descriptor(),
            CD_String,
        ),
    )
}

fun CodeBuilder.box(type: KClass<*>) {
    when (type) {
        Long::class -> {
            invokestatic(
                CD_Long,
                "valueOf",
                MethodTypeDesc.of(CD_Long, CD_long),
            )
        }
        Double::class -> {
            invokestatic(
                CD_Double,
                "valueOf",
                MethodTypeDesc.of(CD_Double, CD_double),
            )
        }
        Int::class -> {
            invokestatic(
                CD_Integer,
                "valueOf",
                MethodTypeDesc.of(CD_Integer, CD_int),
            )
        }
        Boolean::class -> {
            invokestatic(
                CD_Boolean,
                "valueOf",
                MethodTypeDesc.of(CD_Boolean, CD_boolean),
            )
        }
        Byte::class -> {
            invokestatic(
                CD_Byte,
                "valueOf",
                MethodTypeDesc.of(CD_Byte, CD_byte),
            )
        }
        Short::class -> {
            invokestatic(
                CD_Short,
                "valueOf",
                MethodTypeDesc.of(CD_Short, CD_short),
            )
        }
        Char::class -> {
            invokestatic(
                CD_Character,
                "valueOf",
                MethodTypeDesc.of(CD_Character, CD_char),
            )
        }
        // Float::class
        else -> {
            invokestatic(
                CD_Float,
                "valueOf",
                MethodTypeDesc.of(CD_Float, CD_float),
            )
        }
    }
}
