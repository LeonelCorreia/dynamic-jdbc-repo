@file:Suppress("ktlint:standard:no-wildcard-imports")

package pt.isel

import java.io.File
import java.lang.classfile.ClassBuilder
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassFile.ACC_PUBLIC
import java.lang.classfile.CodeBuilder
import java.lang.classfile.Interfaces
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.*
import java.lang.constant.MethodTypeDesc
import java.net.URLClassLoader
import java.sql.*
import kotlin.reflect.*
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotations
import kotlin.reflect.full.primaryConstructor

val SUPER_CLASS_DESC = BaseRepository::class.descriptor()
val CONNECTION_DESC = Connection::class.descriptor()
val KPROPERTY_DESC = KProperty::class.descriptor()
val KFUNCTION_DESC = KFunction::class.descriptor()
val MAP_DESC = Map::class.descriptor()
val RESULTSET_DESC = ResultSet::class.descriptor()
val KCLASS_DESC = KClass::class.descriptor()
val CLASS_DESC = Class::class.descriptor()

private const val PACKAGE_NAME: String = "pt.isel"
private val packageFolder = PACKAGE_NAME.replace(".", "/")

private val root =
    RepositoryReflect::class.java
        .getResource("/")
        ?.toURI()
        ?.path
        ?: "${System.getProperty("user.dir")}/"

/**
 * A new ClassLoader is required when the existing one loads classes from a JAR
 * and its resource path is null. In such cases, we create a ClassLoader that uses
 * the current working directory, as specified by the 'user.dir' system property.
 */
private val rootLoader = URLClassLoader(arrayOf(File(root).toURI().toURL()))

/**
 * Cache of dynamically generated repo keyed by the domain class and the repoReflect.
 * Prevents the need to regenerate the same repo instance multiple times.
 */
private val repositories = mutableMapOf<KClass<*>, BaseRepository<Any, Any>>()

/**
 * Loads a dynamic repo instance for the given domain class using its Java `Class` representation.
 * Delegates to the `loadDynamicRepo` function with the Kotlin class representation.
 */

fun <K : Any, T : Any, R : Repository<K, T>> loadDynamicRepo(
    connection: Connection,
    domainKlass: Class<T>,
    repositoryInterface: Class<R>? = null,
) = loadDynamicRepo(connection, domainKlass.kotlin, repositoryInterface?.kotlin)

/**
 * Loads or creates a dynamic repository for the given domain class.
 * If a repository already exists, it returns the existing one, otherwise
 * it generates a new one using the buildRepositoryClassfile, loads it and instantiates it.
 */
fun <K : Any, T : Any, R : Repository<K, T>> loadDynamicRepo(
    connection: Connection,
    domainKlass: KClass<T>,
    repositoryInterface: KClass<R>? = null,
): BaseRepository<K, T> {
    // calculate the parameters values
    addAuxRepos(domainKlass, connection, repositories)
    val classifiers = buildClassifiers(domainKlass)
    val constructor = buildConstructor(domainKlass)
    val pk: KProperty<*> = buildPk(domainKlass)
    val tableName = buildTableName(domainKlass)
    val props = buildProps(constructor, classifiers, pk, tableName, repositories)

    return repositories.getOrPut(domainKlass) {
        buildRepositoryClassfile(domainKlass, repositoryInterface)
            .constructors
            .first()
            .call(connection, classifiers, pk, tableName, constructor, props) as BaseRepository<Any, Any>
    } as BaseRepository<K, T>
}

/**
 * Generates the class file for a dynamic repository based on the given domain class.
 * Using Class-File API to build the repository implementation at runtime.
 */
private fun <K : Any, T : Any, R : Repository<K, T>> buildRepositoryClassfile(
    domainKlass: KClass<T>,
    repositoryInterface: KClass<R>?,
): KClass<out Any> {
    val className = "RepositoryDyn${domainKlass.simpleName}"
    buildRepositoryByteArray(className, domainKlass, repositoryInterface)
    return rootLoader
        .loadClass("$PACKAGE_NAME.$className")
        .kotlin
}

private fun KFunction<*>.isInsertMethodForKCls(kCls: KClass<*>) =
    parameters.matchConstructorParams(kCls) &&
        findAnnotations(Insert::class).isNotEmpty() &&
        returnType.classifier == kCls

private fun List<KParameter>.matchConstructorParams(kClass: KClass<*>): Boolean {
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

/**
 * Generates a byte array representing a dynamically created
 * class that extends RepositoryReflect, and then saves it to the
 * corresponding class file.
 */
fun <K : Any, T : Any, R : Repository<K, T>> buildRepositoryByteArray(
    className: String,
    domainKlass: KClass<T>,
    repositoryInterface: KClass<R>?,
) {
    val fullClassName = "$PACKAGE_NAME.$className"
    val generatedClassDesc = ClassDesc.of(fullClassName)
    val domainKlassDesc = domainKlass.descriptor()
    val constructor = domainKlass.constructors.first { it.parameters.isNotEmpty() }
    val props = buildPropsDyn(constructor)

    val insertMethod =
        repositoryInterface
            ?.declaredFunctions
            ?.first { it.isInsertMethodForKCls(domainKlass) }

    val bytes =
        ClassFile
            .of()
            .build(generatedClassDesc) { clb ->
                // the class generated is a subclass of RepositoryReflect
                clb.withSuperclass(SUPER_CLASS_DESC)
                clb.declareFields()
                clb.defineFieldGetters(generatedClassDesc, 0)
                clb.withMethod(
                    INIT_NAME,
                    MethodTypeDesc.of(
                        CD_void,
                        CONNECTION_DESC,
                        // classifiers
                        MAP_DESC,
                        // pk
                        KPROPERTY_DESC,
                        // tableName
                        CD_String,
                        // constructor
                        KFUNCTION_DESC,
                        // props
                        MAP_DESC,
                    ),
                    ACC_PUBLIC,
                ) { mb ->
                    mb.withCode { cb ->
                        cb.withInit(generatedClassDesc, SUPER_CLASS_DESC, CONNECTION_DESC)
                    }
                }

                // Only implement the repository interface where the insert fun is declared
                // if this interface has a valid method and only one method
                insertMethod?.let {
                    if (repositoryInterface.declaredFunctions.size == 1) {
                        clb.withInterfaces(
                            Interfaces.ofSymbols(ClassDesc.of(repositoryInterface.qualifiedName)).interfaces(),
                        )
                    }
                }

                // redefine the mapToRow method to no longer use reflection at runtime
                clb.withMethod(
                    "mapRowToEntity",
                    MethodTypeDesc.of(
                        CD_Object,
                        RESULTSET_DESC,
                    ),
                    ACC_PUBLIC,
                ) { mb ->
                    mb.withCode { cb ->
                        cb.withMapRowToEntity(domainKlassDesc, SUPER_CLASS_DESC, constructor, props)
                    }
                }

                // if suitable insert method is found, implements it
                insertMethod?.also { kFun ->
                    // Drop the first parameter (the receiver)
                    val insertMthParams = kFun.parameters.drop(1)
                    clb.withMethod(
                        kFun.name,
                        MethodTypeDesc.of(
                            kFun.returnType.descriptor(),
                            insertMthParams.map { kParam -> kParam.type.descriptor() },
                        ),
                        ACC_PUBLIC,
                    ) { mb ->
                        mb.withCode { cb ->
                            cb.insertMethod(
                                domainKlass,
                                insertMthParams,
                                SUPER_CLASS_DESC,
                                CONNECTION_DESC,
                            )
                        }
                    }
                }
            }
    File(root, fullClassName.replace(".", "/") + ".class")
        .also { it.parentFile.mkdirs() }
        .writeBytes(bytes)
}

private fun ClassBuilder.declareFields() {
    withField(
        "classifiers",
        MAP_DESC,
        ACC_PUBLIC,
    )
    withField(
        "pk",
        KPROPERTY_DESC,
        ACC_PUBLIC,
    )
    withField(
        "tableName",
        CD_String,
        ACC_PUBLIC,
    )
    withField(
        "constructor",
        KFUNCTION_DESC,
        ACC_PUBLIC,
    )
    withField(
        "props",
        MAP_DESC,
        ACC_PUBLIC,
    )
}

private fun ClassBuilder.defineFieldGetters(
    generatedClassDesc: ClassDesc,
    thisSlot: Int,
) {
    withMethod(
        "getClassifiers",
        MethodTypeDesc.of(MAP_DESC),
        ACC_PUBLIC,
    ) { mb ->
        mb.withCode { cb ->
            cb.aload(thisSlot)
            cb.getfield(
                generatedClassDesc,
                "classifiers",
                MAP_DESC,
            )
            cb.areturn()
        }
    }

    withMethod(
        "getPk",
        MethodTypeDesc.of(KPROPERTY_DESC),
        ACC_PUBLIC,
    ) { mb ->
        mb.withCode { cb ->
            cb.aload(thisSlot)
            cb.getfield(
                generatedClassDesc,
                "pk",
                KPROPERTY_DESC,
            )
            cb.areturn()
        }
    }

    withMethod(
        "getTableName",
        MethodTypeDesc.of(CD_String),
        ACC_PUBLIC,
    ) { mb ->
        mb.withCode { cb ->
            cb.aload(thisSlot)
            cb.getfield(
                generatedClassDesc,
                "tableName",
                CD_String,
            )
            cb.areturn()
        }
    }

    withMethod(
        "getConstructor",
        MethodTypeDesc.of(KFUNCTION_DESC),
        ACC_PUBLIC,
    ) { mb ->
        mb.withCode { cb ->
            cb.aload(thisSlot)
            cb.getfield(
                generatedClassDesc,
                "constructor",
                KFUNCTION_DESC,
            )
            cb.areturn()
        }
    }

    withMethod(
        "getProps",
        MethodTypeDesc.of(MAP_DESC),
        ACC_PUBLIC,
    ) { mb ->
        mb.withCode { cb ->
            cb.aload(thisSlot)
            cb.getfield(
                generatedClassDesc,
                "props",
                MAP_DESC,
            )
            cb.areturn()
        }
    }
}

/**
 * Returns a ClassDesc of the type descriptor of the given KClass.
 */
fun KClass<*>.descriptor(): ClassDesc =
    if (this.java.isPrimitive) {
        when (this) {
            Char::class -> CD_char
            Short::class -> CD_short
            Int::class -> CD_int
            Long::class -> CD_long
            Float::class -> CD_float
            Double::class -> CD_double
            Boolean::class -> CD_boolean
            else -> {
                throw IllegalStateException("No primitive type for ${this.qualifiedName}!")
            }
        }
    } else {
        if (this == Unit::class) {
            CD_void
        } else {
            ClassDesc.of(this.java.name)
        }
    }

/**
 * Returns a ClassDesc of the type descriptor of the given KType.
 */
fun KType.descriptor(): ClassDesc {
    val klass = this.classifier as KClass<*>
    return klass.descriptor()
}

/**
 * Convert Class in KClass, that is in the stack
 */
fun CodeBuilder.toKClass(): CodeBuilder {
    invokestatic(
        ClassDesc.of("kotlin.jvm.internal.Reflection"),
        "getOrCreateKotlinClass",
        MethodTypeDesc.of(
            KCLASS_DESC,
            CLASS_DESC,
        ),
    )
    return this
}

/**
 * Function that generates bytecode for the insert method noted with @Insert
 * and the necessary parameters.
 */
private fun CodeBuilder.insertMethod(
    domainKcls: KClass<*>,
    params: List<KParameter>,
    superClassDesc: ClassDesc,
    connectionDesc: ClassDesc,
) {
    val tableName =
        domainKcls
            .findAnnotations(Table::class)
            .firstOrNull()
            ?.name
            ?: domainKcls.simpleName
            ?: error("Missing table name")

    val pkType =
        domainKcls
            .declaredMemberProperties
            .firstOrNull { prop -> prop.findAnnotations(Pk::class).isNotEmpty() }
            ?.returnType
            ?: error("Missing @Pk for insert operation")

    val columnsNames = columnsNames(domainKcls, params)
    val sql = buildInsertQuery(tableName, columnsNames)
    val insertParams = if (domainKcls.hasRelationships()) params.resolveRelationShips() else params.toParamInfo()
    val availableSlot = params.firstSlotAvailableAfterParams()
    val preparedStmtSlot = availableSlot
    val affectedRows = availableSlot + 1
    val resultSetSlot = availableSlot + 2

    // Prepare statement
    aload(0)
    invokevirtual(
        ClassDesc.of("${PACKAGE_NAME}.BaseRepository"),
        "getConnection",
        MethodTypeDesc.of(
            connectionDesc,
        ),
    )
    ldc(constantPool().stringEntry(sql))
    iconst_1()
    invokeinterface(
        ClassDesc.of("java.sql.Connection"),
        "prepareStatement",
        MethodTypeDesc.of(
            PreparedStatement::class.descriptor(),
            String::class.descriptor(),
            Int::class.descriptor(),
        ),
    )
    astore(preparedStmtSlot)

    // Set insert parameters
    insertParams.forEachIndexed { index, param ->
        aload(preparedStmtSlot)
        bipush(index + 1)
        loadParameter(param.slot, param.classifier)
        if ((param.classifier as KClass<*>).java.isEnum) {
            invokevirtual(
                ClassDesc.of(param.classifier.qualifiedName),
                "name",
                MethodTypeDesc.of(String::class.descriptor()),
            )
            sipush(1111)
        }
        setValue(param)
    }

    // Execute update
    aload(preparedStmtSlot)
    invokeinterface(
        ClassDesc.of("java.sql.PreparedStatement"),
        "executeUpdate",
        MethodTypeDesc.of(Int::class.descriptor()),
    )
    val labelNoAffectedRows = newLabel()
    istore(affectedRows)
    iload(affectedRows)
    ifeq(labelNoAffectedRows)
    if (domainKcls.hasSerialPrimaryKey()) {
        val labelNoGeneratedKeys = newLabel()
        // Get generated keys
        aload(preparedStmtSlot)
        invokeinterface(
            ClassDesc.of("java.sql.PreparedStatement"),
            "getGeneratedKeys",
            MethodTypeDesc.of(ResultSet::class.descriptor()),
        )
        astore(resultSetSlot)

        aload(resultSetSlot)
        invokeinterface(
            ClassDesc.of("java.sql.ResultSet"),
            "next",
            MethodTypeDesc.of(Boolean::class.descriptor()),
        )
        ifeq(labelNoGeneratedKeys)

        // Build domain object
        new_(domainKcls.descriptor())
        dup()
        aload(resultSetSlot)
        iconst_1()
        getPkValue(pkType)

        params.forEachIndexed { index, param ->
            val classfier = param.type.classifier ?: error("Missing classifier for parameter")
            loadParameter(index + 1, classfier)
        }

        invokespecial(
            domainKcls.descriptor(),
            INIT_NAME,
            MethodTypeDesc.of(
                CD_void,
                listOf(pkType.descriptor()) + params.map { it.type.descriptor() },
            ),
        )
        areturn()
        // Handle missing keys
        labelBinding(labelNoGeneratedKeys)
        createSqlException("No generated keys")
        athrow()
    } else {
        new_(domainKcls.descriptor())
        dup()
        insertParams.forEach { param ->
            loadParameter(param.slot, param.classifier)
        }
        invokespecial(
            domainKcls.descriptor(),
            INIT_NAME,
            MethodTypeDesc.of(
                CD_void,
                params.map { it.type.descriptor() },
            ),
        )
        areturn()
    }
    labelBinding(labelNoAffectedRows)
    createSqlException("No rows affected, when inserting into $tableName")
    athrow()
}

/**
 * Helper function to build the props with the name of the columns and its kotlin class representative.
 *
 * @param constructor The constructor for the domain class
 * @return A map of the column names for the ResultSet and their respective kotlin class representative
 */
private fun buildPropsDyn(constructor: KFunction<Any>): Map<KParameter, String> =
    constructor.parameters.let {
        it.associateWith { constParam ->
            val columnName =
                constParam.findAnnotations(Column::class).firstOrNull()?.name
                    ?: constParam.name
                    ?: throw IllegalStateException("Missing name for column in table.")

            columnName
        }
    }

private data class ParamSlot(
    val slot: Int,
    val type: KClass<*>,
)

private fun CodeBuilder.withInit(
    generatedClassDesc: ClassDesc,
    superClassDesc: ClassDesc,
    connectionDesc: ClassDesc,
) {
    val thisSlot = 0
    val connectionSlot = 1

    aload(thisSlot)
    aload(connectionSlot)
    invokespecial(
        superClassDesc,
        INIT_NAME,
        MethodTypeDesc.of(
            CD_void,
            connectionDesc,
        ),
    )
    // load the values into the val's
    loadVals(generatedClassDesc, thisSlot)
    return_()
}

private fun CodeBuilder.loadVals(
    generatedClassDesc: ClassDesc,
    thisSlot: Int,
) {
    val classifiersSlot = 2
    val pkSlot = 3
    val tableNameSlot = 4
    val constructorSlot = 5
    val propsSlot = 6

    // assign classifiers
    aload(thisSlot)
    aload(classifiersSlot)
    putfield(
        generatedClassDesc,
        "classifiers",
        MAP_DESC,
    )

    aload(thisSlot)
    aload(pkSlot)
    putfield(
        generatedClassDesc,
        "pk",
        KPROPERTY_DESC,
    )

    aload(thisSlot)
    aload(tableNameSlot)
    putfield(
        generatedClassDesc,
        "tableName",
        CD_String,
    )

    aload(thisSlot)
    aload(constructorSlot)
    putfield(
        generatedClassDesc,
        "constructor",
        KFUNCTION_DESC,
    )

    aload(thisSlot)
    aload(propsSlot)
    putfield(
        generatedClassDesc,
        "props",
        MAP_DESC,
    )
}

/**
 * Helper function that generates bytecode for the mapToRowEntity function.
 *
 * @param domainKlassDesc Descriptor for the domain class
 * @param superClassDesc Descriptor for the superclass that has getValue method
 * @param constructor Constructor of the domain class
 * @param props Map with the column names and respective kotlin class representatives
 */
private fun CodeBuilder.withMapRowToEntity(
    domainKlassDesc: ClassDesc,
    superClassDesc: ClassDesc,
    constructor: KFunction<Any>,
    props: Map<KParameter, String>,
) {
    // constants for special local variable slots
    val thisSlot = 0
    val resultSetSlot = 1
    val firstConstructorParamSlot = 2

    // stores the indexes and types of the local variables
    val paramSlots = mutableListOf<ParamSlot>()
    var localIndex = firstConstructorParamSlot

    // run through constructor parameters to get all the indexes for the localVariables and the types
    constructor.parameters.forEach { param ->
        val klass = param.type.classifier as KClass<*>
        paramSlots += ParamSlot(localIndex, klass)

        // Long and Double require 2 slots, others only 1
        // for reference go to: https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-2.html#jvms-2.6.1
        localIndex += if (klass == Long::class || klass == Double::class) 2 else 1
    }

    props.toList().forEachIndexed { index, (kParam, columnName) ->
        val targetSlot = paramSlots[index].slot
        val classifier = kParam.type.classifier as KClass<*>
        val boxedDesc = classifier.javaObjectType.kotlin.descriptor() // get the descriptor of the boxed type
        loadColumnValue(thisSlot, resultSetSlot, superClassDesc, boxedDesc, columnName)
        storeValueInSlot(classifier, targetSlot)
    }

    // create instance of final object
    new_(domainKlassDesc)
    // duplicate the reference for the constructor call
    dup()
    loadConstructorArguments(paramSlots)
    callConstructor(domainKlassDesc, paramSlots.map { it.type })
    // return from the function
    areturn()
}

/**
 * Helper function to load the value of a column from the ResultSet.
 * It uses the superclass getValue method to obtain the value
 * @param thisRefSlot The slot for the reference of the Superclass
 * @param resultSetSlot The slot for the ResultSet
 * @param superClassDesc The descriptor of the superclass
 * @param boxedDesc The descriptor of the boxed type
 * @param columnName The name of the column to be loaded
 */
private fun CodeBuilder.loadColumnValue(
    thisRefSlot: Int,
    resultSetSlot: Int,
    superClassDesc: ClassDesc,
    boxedDesc: ClassDesc,
    columnName: String,
) {
    // push superclass to be able to call getValue that is from the extended class
    aload(thisRefSlot)
    // create entry in constant pool with the name of the parameter for the result set
    ldc(constantPool().stringEntry(columnName))
    // call superclass.getValue(KClass, String)
    invokevirtual(
        superClassDesc,
        "findGetterByParamName",
        MethodTypeDesc.of(
            ClassDesc.of("kotlin.jvm.functions.Function1"),
            CD_String,
        ),
    )
    // push the resultSet to the stack
    aload(resultSetSlot)
    // call the function returned by getValue, (ResultSet) -> Any implements Function<ResultSet, Object>
    invokeinterface(
        ClassDesc.of("kotlin.jvm.functions.Function1"),
        "invoke",
        MethodTypeDesc.of(CD_Object, CD_Object),
    )
    // cast to the expected boxed type
    checkcast(boxedDesc)
}

/**
 * Helper function to store the value in the local variable slot.
 * @param type The type of the value to be stored
 * @param targetSlot The slot where the value will be stored
 */
private fun CodeBuilder.storeValueInSlot(
    type: KClass<*>,
    targetSlot: Int,
) {
    // store the result according to its type, also primitive values need unboxing
    when (type) {
        Long::class -> {
            // needs unboxing from Long to long
            invokevirtual(
                CD_Long,
                "longValue",
                MethodTypeDesc.of(CD_long),
            )
            lstore(targetSlot)
        }
        Double::class -> {
            // needs unboxing from Double to double
            invokevirtual(
                CD_Double,
                "doubleValue",
                MethodTypeDesc.of(CD_double),
            )
            dstore(targetSlot)
        }
        Int::class -> {
            // needs unboxing from Integer to int
            invokevirtual(
                CD_Integer,
                "intValue",
                MethodTypeDesc.of(CD_int),
            )
            istore(targetSlot)
        }
        Boolean::class -> {
            invokevirtual(
                CD_Boolean,
                "booleanValue",
                MethodTypeDesc.of(CD_boolean),
            )
            istore(targetSlot)
        }
        Byte::class -> {
            invokevirtual(
                CD_Byte,
                "byteValue",
                MethodTypeDesc.of(CD_byte),
            )
            istore(targetSlot)
        }
        Short::class -> {
            invokevirtual(
                CD_Short,
                "shortValue",
                MethodTypeDesc.of(CD_short),
            )
            istore(targetSlot)
        }
        Char::class -> {
            invokevirtual(
                CD_Character,
                "charValue",
                MethodTypeDesc.of(CD_char),
            )
        }
        Float::class -> {
            invokevirtual(
                CD_Float,
                "floatValue",
                MethodTypeDesc.of(CD_float),
            )
        }
        else -> astore(targetSlot)
    }
}

/**
 * Helper function to load the constructor arguments from the local variable slots.
 * @param paramSlots The list of local variable slots and types for the constructor parameters
 */
private fun CodeBuilder.loadConstructorArguments(paramSlots: List<ParamSlot>) {
    // push to the stack every parameter of the constructor obtained previously in order
    for ((slot, type) in paramSlots) {
        when (type) {
            Long::class -> lload(slot)
            Double::class -> dload(slot)
            Int::class, Boolean::class, Byte::class, Char::class, Short::class -> iload(slot)
            Float::class -> fload(slot)
            else -> aload(slot)
        }
    }
}

/**
 * Helper function to call the constructor of the domain class.
 * It uses the MethodTypeDesc to get the descriptor of the constructor.
 * @param klassDesc The descriptor of the domain class
 * @param paramTypes The list of types for the constructor parameters
 */
private fun CodeBuilder.callConstructor(
    klassDesc: ClassDesc,
    paramTypes: List<KClass<*>>,
) {
    val constructorDesc =
        MethodTypeDesc.of(
            CD_void,
            paramTypes.map { type ->
                when (type) {
                    Long::class -> CD_long
                    Double::class -> CD_double
                    Int::class -> CD_int
                    Boolean::class -> CD_boolean
                    Byte::class -> CD_byte
                    Char::class -> CD_char
                    Short::class -> CD_short
                    Float::class -> CD_float
                    else -> type.javaObjectType.kotlin.descriptor()
                }
            },
        )

    // with the values in the stack, call the constructor
    invokespecial(
        klassDesc,
        INIT_NAME,
        constructorDesc,
    )
}
