@file:Suppress("ktlint:standard:no-wildcard-imports", "UNCHECKED_CAST")

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

val SUPER_CLASS_DESC = BaseRepository::class.descriptor()
val CONNECTION_DESC = Connection::class.descriptor()
val KPROPERTY_DESC = KProperty::class.descriptor()
val RESULTSET_DESC = ResultSet::class.descriptor()
val PREPARED_STM_DESC = PreparedStatement::class.descriptor()
const val THIS_SLOT = 0

private const val PACKAGE_NAME: String = "pt.isel"

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
    val className = "RepositoryDyn${domainKlass.simpleName}"
    val generatedClassDesc = ClassDesc.of("$PACKAGE_NAME.$className")
    // calculate the parameters values
    val auxRepos = getDynAuxRepos(domainKlass, connection, repositories)
    val classifiers = buildClassifiers(domainKlass)
    val pk: KProperty<*> = buildPk(domainKlass)
    val constructor = buildConstructor(domainKlass)
    val tableName = buildTableName(domainKlass)
    val properties = buildPropList(constructor, classifiers, tableName)
    val getProps = buildDynamicGetPropInfo(domainKlass, generatedClassDesc, classifiers, tableName, repositories)

    val params = listOf(connection, pk, tableName, properties) + auxRepos.values

    return repositories.getOrPut(domainKlass) {
        buildRepositoryClassfile(
            domainKlass,
            repositoryInterface,
            constructor,
            getProps,
            className,
            generatedClassDesc,
            auxRepos,
        ).constructors
            .first()
            .call(*params.toTypedArray()) as BaseRepository<Any, Any>
    } as BaseRepository<K, T>
}

/**
 * Generates the class file for a dynamic repository based on the given domain class.
 * Using Class-File API to build the repository implementation at runtime.
 */
private fun <K : Any, T : Any, R : Repository<K, T>> buildRepositoryClassfile(
    domainKlass: KClass<T>,
    repositoryInterface: KClass<R>?,
    constructor: KFunction<T>,
    props: List<GetPropInfo>,
    className: String,
    generatedClassDesc: ClassDesc,
    auxRepos: Map<KClass<*>, BaseRepository<Any, Any>>,
): KClass<out Any> {
    val fullClassName = "$PACKAGE_NAME.$className"
    buildRepositoryByteArray(
        domainKlass,
        repositoryInterface,
        constructor,
        props,
        fullClassName,
        generatedClassDesc,
        auxRepos,
    )
    return rootLoader
        .loadClass("$PACKAGE_NAME.$className")
        .kotlin
}

/**
 * Generates a byte array representing a dynamically created
 * class that extends RepositoryReflect, and then saves it to the
 * corresponding class file.
 */
fun <K : Any, T : Any, R : Repository<K, T>> buildRepositoryByteArray(
    domainKlass: KClass<T>,
    repositoryInterface: KClass<R>?,
    constructor: KFunction<T>,
    props: List<GetPropInfo>,
    fullClassName: String,
    generatedClassDesc: ClassDesc,
    auxRepos: Map<KClass<*>, BaseRepository<Any, Any>>,
) {
    val domainKlassDesc = domainKlass.descriptor()

    val insertMethod =
        repositoryInterface
            ?.declaredFunctions
            ?.first { it.isInsertMethodForKCls(domainKlass) }

    val bytes =
        ClassFile
            .of()
            .build(generatedClassDesc) { clb ->
                // the class generated is a subclass of BaseRepository
                clb.withSuperclass(SUPER_CLASS_DESC)
                clb.declareFields(auxRepos)
                clb.defineFieldGetters(generatedClassDesc, auxRepos)
                val paramDescList = listOf(CONNECTION_DESC, KPROPERTY_DESC, CD_String, CD_Map) + List(auxRepos.size) { SUPER_CLASS_DESC }

                clb.withMethod(
                    INIT_NAME,
                    MethodTypeDesc.of(
                        CD_void,
                        paramDescList,
                    ),
                    ACC_PUBLIC,
                ) { mb ->
                    mb.withCode { cb ->
                        cb.withInit(generatedClassDesc, SUPER_CLASS_DESC, CONNECTION_DESC, auxRepos)
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
                        cb.withMapRowToEntity(domainKlassDesc, constructor, props)
                    }
                }

                clb.withMethod(
                    "update",
                    MethodTypeDesc.of(
                        CD_void,
                        CD_Object,
                    ),
                    ACC_PUBLIC,
                ) { mb ->
                    mb.withCode { cb ->
                        val params = constructor.parameters.drop(1)
                        cb.updateMethod(
                            domainKlass,
                            params,
                        )
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
                            )
                        }
                    }
                }
            }
    File(root, fullClassName.replace(".", "/") + ".class")
        .also { it.parentFile.mkdirs() }
        .writeBytes(bytes)
}

private fun ClassBuilder.declareFields(auxRepos: Map<KClass<*>, BaseRepository<Any, Any>>) {
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
        "properties",
        CD_Map,
        ACC_PUBLIC,
    )

    auxRepos.values.forEach { repo ->
        withField(
            repo::class.simpleName,
            SUPER_CLASS_DESC,
            ACC_PUBLIC,
        )
    }
}

private fun ClassBuilder.defineFieldGetters(
    generatedClassDesc: ClassDesc,
    repositories: Map<KClass<*>, BaseRepository<Any, Any>>,
) {
    withMethod(
        "getPk",
        MethodTypeDesc.of(KPROPERTY_DESC),
        ACC_PUBLIC,
    ) { mb ->
        mb.withCode { cb ->
            cb.aload(THIS_SLOT)
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
            cb.aload(THIS_SLOT)
            cb.getfield(
                generatedClassDesc,
                "tableName",
                CD_String,
            )
            cb.areturn()
        }
    }

    withMethod(
        "getProperties",
        MethodTypeDesc.of(CD_Map),
        ACC_PUBLIC,
    ) { mb ->
        mb.withCode { cb ->
            cb.aload(THIS_SLOT)
            cb.getfield(
                generatedClassDesc,
                "properties",
                CD_Map,
            )
            cb.areturn()
        }
    }

    repositories.values.forEach { repo ->
        val repoName = repo::class.simpleName
        withMethod(
            "get$repoName",
            MethodTypeDesc.of(SUPER_CLASS_DESC),
            ACC_PUBLIC,
        ) { mb ->
            mb.withCode { cb ->
                cb.aload(THIS_SLOT)
                cb.getfield(
                    generatedClassDesc,
                    repoName,
                    SUPER_CLASS_DESC,
                )
                cb.areturn()
            }
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
 * Function that generates bytecode for the update method
 */
private fun CodeBuilder.updateMethod(
    domainKlass: KClass<*>,
    params: List<KParameter>,
) {
    val tableName = domainKlass.getTableName()
    val pkName = domainKlass.getPkProp().name
    val columnName = domainKlass.getColumnNames()
    val paramsInfo = params.toParamInfo(domainKlass)
    val sql = buildUpdateQuery(tableName, pkName, columnName)

    val objectSlot = 1
    val prepStmtSlot = 2

    prepareStatement(sql, domainKlass, prepStmtSlot)
    val paramCount = setPreparedStatementParamsFromObject(domainKlass, prepStmtSlot, objectSlot, paramsInfo)
    setPrimaryKeyParameter(domainKlass, prepStmtSlot, objectSlot, paramCount)

    executeUpdate(prepStmtSlot)
}

/**
 * Function that generates bytecode for the insert method noted with @Insert
 * and the necessary parameters.
 */
private fun CodeBuilder.insertMethod(
    domainKlass: KClass<*>,
    params: List<KParameter>,
) {
    val tableName = domainKlass.getTableName()
    val pkType = domainKlass.getPkProp().returnType
    val paramInfos = params.toParamInfo(domainKlass)
    val columnNames = columnsNames(paramInfos)
    val sql = buildInsertQuery(tableName, columnNames)

    val prepStmtSlot = params.firstSlotAvailableAfterParams()
    val affectedRowsSlot = prepStmtSlot + 1
    val resultSetSlot = prepStmtSlot + 2
    prepareStatement(sql, domainKlass, prepStmtSlot)
    setPreparedStatementParams(prepStmtSlot, paramInfos)

    executeUpdateAndReturn(domainKlass, paramInfos, prepStmtSlot, affectedRowsSlot, resultSetSlot, tableName, pkType)
}

private fun CodeBuilder.prepareStatement(
    sql: String,
    domainKlass: KClass<*>,
    targetSlot: Int,
) {
    aload(THIS_SLOT)
    invokevirtual(ClassDesc.of("${PACKAGE_NAME}.BaseRepository"), "getConnection", MethodTypeDesc.of(CONNECTION_DESC))
    ldc(constantPool().stringEntry(sql))

    val hasSerialPK = domainKlass.hasSerialPrimaryKey()

    val methodDesc =
        if (hasSerialPK) {
            iconst_1()
            MethodTypeDesc.of(PREPARED_STM_DESC, String::class.descriptor(), Int::class.descriptor())
        } else {
            MethodTypeDesc.of(PREPARED_STM_DESC, String::class.descriptor())
        }

    invokeinterface(CONNECTION_DESC, "prepareStatement", methodDesc)
    astore(targetSlot)
}

private fun CodeBuilder.setPreparedStatementParams(
    stmtSlot: Int,
    insertParams: List<ParamInfo>,
) {
    insertParams.forEachIndexed { index, param ->
        loadPreparedStatement(stmtSlot, index)
        loadParameter(param.slot, param.cls)
        handleSpecialTypes(param)

        setValue(if (param.isRelation()) param.convertParamInfoToPkInfo() else param)
    }
}

private fun CodeBuilder.executeUpdateAndReturn(
    domainKlass: KClass<*>,
    insertParams: List<ParamInfo>,
    stmtSlot: Int,
    affectedSlot: Int,
    resultSetSlot: Int,
    tableName: String,
    pkType: KType,
) {
    aload(stmtSlot)
    invokeinterface(PreparedStatement::class.descriptor(), "executeUpdate", MethodTypeDesc.of(Int::class.descriptor()))
    istore(affectedSlot)

    val labelNoAffected = newLabel()
    iload(affectedSlot)
    ifeq(labelNoAffected)

    if (domainKlass.hasSerialPrimaryKey()) {
        handleGeneratedKeyInsert(domainKlass, insertParams, stmtSlot, resultSetSlot, pkType)
    } else {
        buildDomainAndReturn(domainKlass, insertParams)
    }

    labelBinding(labelNoAffected)
    createSqlException("No rows affected, when inserting into $tableName")
    athrow()
}

private fun CodeBuilder.executeUpdate(stmtSlot: Int) {
    aload(stmtSlot)
    invokeinterface(ClassDesc.of("java.sql.PreparedStatement"), "executeUpdate", MethodTypeDesc.of(Int::class.descriptor()))

    return_()
}

private fun CodeBuilder.handleGeneratedKeyInsert(
    domainKlass: KClass<*>,
    insertParams: List<ParamInfo>,
    stmtSlot: Int,
    resultSetSlot: Int,
    pkType: KType,
) {
    val labelNoKeys = newLabel()

    aload(stmtSlot)
    invokeinterface(PreparedStatement::class.descriptor(), "getGeneratedKeys", MethodTypeDesc.of(ResultSet::class.descriptor()))
    astore(resultSetSlot)

    aload(resultSetSlot)
    invokeinterface(RESULTSET_DESC, "next", MethodTypeDesc.of(Boolean::class.descriptor()))
    ifeq(labelNoKeys)

    new_(domainKlass.descriptor())
    dup()
    aload(resultSetSlot)
    iconst_1()
    getPkValue(pkType)

    val orderedParameters = insertParams.sortedBy { it.ctorArg.index }
    orderedParameters.forEach { loadParameter(it.slot, it.cls) }
    invokespecial(
        domainKlass.descriptor(),
        INIT_NAME,
        MethodTypeDesc.of(CD_void, domainKlass.primaryCtorArgs().map { it.type.descriptor() }),
    )
    areturn()

    labelBinding(labelNoKeys)
    createSqlException("No generated keys")
    athrow()
}

private fun CodeBuilder.buildDomainAndReturn(
    domainKlass: KClass<*>,
    insertParams: List<ParamInfo>,
) {
    new_(domainKlass.descriptor())
    dup()
    val orderedParameters = insertParams.sortedBy { it.ctorArg.index }
    orderedParameters.forEach { loadParameter(it.slot, it.cls) }
    invokespecial(
        domainKlass.descriptor(),
        INIT_NAME,
        MethodTypeDesc.of(CD_void, domainKlass.primaryCtorArgs().map { it.type.descriptor() }),
    )
    areturn()
}

private data class ParamSlot(
    val slot: Int,
    val type: KClass<*>,
)

private fun CodeBuilder.withInit(
    generatedClassDesc: ClassDesc,
    superClassDesc: ClassDesc,
    connectionDesc: ClassDesc,
    repositories: Map<KClass<*>, BaseRepository<Any, Any>>,
) {
    val connectionSlot = 1

    aload(THIS_SLOT)
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
    loadVals(generatedClassDesc, repositories)
    return_()
}

private fun CodeBuilder.loadVals(
    generatedClassDesc: ClassDesc,
    repositories: Map<KClass<*>, BaseRepository<Any, Any>>,
) {
    val pkSlot = 2
    val tableNameSlot = 3
    val propertiesSlot = 4
    val reposStartSlot = 5

    aload(THIS_SLOT)
    aload(pkSlot)
    putfield(
        generatedClassDesc,
        "pk",
        KPROPERTY_DESC,
    )

    aload(THIS_SLOT)
    aload(tableNameSlot)
    putfield(
        generatedClassDesc,
        "tableName",
        CD_String,
    )

    aload(THIS_SLOT)
    aload(propertiesSlot)
    putfield(
        generatedClassDesc,
        "properties",
        CD_Map,
    )

    repositories.values.forEachIndexed { index, repo ->
        val slot = reposStartSlot + index
        aload(THIS_SLOT)
        aload(slot)
        putfield(
            generatedClassDesc,
            repo::class.simpleName,
            SUPER_CLASS_DESC,
        )
    }
}

/**
 * Helper function that generates bytecode for the mapToRowEntity function.
 *
 * @param domainKlassDesc Descriptor for the domain class
 * @param constructor Constructor of the domain class
 * @param props Map with the column names and respective kotlin class representatives
 */
private fun CodeBuilder.withMapRowToEntity(
    domainKlassDesc: ClassDesc,
    constructor: KFunction<Any>,
    props: List<GetPropInfo>,
) {
    // constants for special local variable slots
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

    props.forEachIndexed { index, getPropInfo ->
        val targetSlot = paramSlots[index].slot
        getPropInfo as GetPropInfo.Dynamic
        val classifier = getPropInfo.kParam.type.classifier as KClass<*>
        val getter = getPropInfo.getter
        getter()
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
 * Helper function to store the value in the local variable slot.
 * @param type The type of the value to be stored
 * @param targetSlot The slot where the value will be stored
 */
fun CodeBuilder.storeValueInSlot(
    type: KClass<*>,
    targetSlot: Int,
) {
    // store the result according to its type
    when (type) {
        Long::class -> lstore(targetSlot)
        Double::class -> dstore(targetSlot)
        Int::class, Boolean::class, Byte::class, Short::class, Char::class -> istore(targetSlot)
        Float::class -> fstore(targetSlot)
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
        loadParameter(slot, type)
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
