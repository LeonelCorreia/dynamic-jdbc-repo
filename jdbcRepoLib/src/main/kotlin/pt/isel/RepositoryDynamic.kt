@file:Suppress("ktlint:standard:no-wildcard-imports")

package pt.isel

import java.io.File
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassFile.ACC_PUBLIC
import java.lang.classfile.CodeBuilder
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs.*
import java.lang.constant.MethodTypeDesc
import java.net.URLClassLoader
import java.sql.Connection
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KType
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotations

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
private val repositories = mutableMapOf<KClass<*>, RepositoryReflect<Any, Any>>()

/**
 * Loads a dynamic repo instance for the given domain class using its Java `Class` representation.
 * Delegates to the `loadDynamicRepo` function with the Kotlin class representation.
 */

fun <K : Any, T : Any> loadDynamicRepo(
    connection: Connection,
    domainKlass: Class<T>,
) = loadDynamicRepo<K, T>(connection, domainKlass.kotlin)

/**
 * Loads or creates a dynamic repository for the given domain class.
 * If a repository already exists, it returns the existing one, otherwise
 * it generates a new one using the buildRepositoryClassfile, loads it and instantiates it.
 */
fun <K : Any, T : Any> loadDynamicRepo(
    connection: Connection,
    domainKlass: KClass<T>,
) = repositories.getOrPut(domainKlass) {
    buildRepositoryClassfile(domainKlass)
        .constructors
        .first()
        .call(connection) as RepositoryReflect<Any, Any>
} as RepositoryReflect<K, T>

/**
 * Generates the class file for a dynamic repository based on the given domain class.
 * Using Class-File API to build the repository implementation at runtime.
 */
private fun <T : Any> buildRepositoryClassfile(domainKlass: KClass<T>): KClass<out Any> {
    val className = "RepositoryDyn${domainKlass.simpleName}"
    buildRepositoryByteArray(className, domainKlass)
    return rootLoader
        .loadClass("$PACKAGE_NAME.$className")
        .kotlin
}

/**
 * Generates a byte array representing a dynamically created
 * class that extends RepositoryReflect, and then saves it to the
 * corresponding class file.
 */
fun <T : Any> buildRepositoryByteArray(
    className: String,
    domainKlass: KClass<T>,
) {
    val fullClassName = "$PACKAGE_NAME.$className"
    val domainKlassDesc = domainKlass.descriptor()
    val superclassDesc = RepositoryReflect::class.descriptor()
    val connectionDesc = Connection::class.descriptor()
    val constructor = domainKlass.constructors.first { it.parameters.isNotEmpty() }
    val props = buildProps(domainKlass, constructor)
    val bytes =
        ClassFile
            .of()
            .build(ClassDesc.of(fullClassName)) { clb ->
                // the class generated is a subclass of RepositoryReflect
                clb.withSuperclass(superclassDesc)

                clb.withMethod(
                    INIT_NAME,
                    MethodTypeDesc.of(
                        CD_void,
                        connectionDesc,
                    ),
                    ACC_PUBLIC,
                ) { mb ->
                    mb.withCode { cb ->
                        cb
                            .aload(0)
                            .aload(1)
                            .ldc(cb.constantPool().classEntry(domainKlassDesc))
                            .toKClass()
                            .invokespecial(
                                superclassDesc,
                                INIT_NAME,
                                MethodTypeDesc.of(
                                    CD_void,
                                    connectionDesc,
                                    KClass::class.descriptor(),
                                ),
                            ).return_()
                    }
                }

                // redefine the mapToRow method to no longer use reflection at runtime
                clb.withMethod("mapRowToEntity", MethodTypeDesc.of(CD_Object, CD_Object), ACC_PUBLIC) { mb ->
                    mb.withCode { cb ->
                        cb.withMapRowToEntity(domainKlassDesc, superclassDesc, constructor, props)
                    }
                }
            }
    File(root, fullClassName.replace(".", "/") + ".class")
        .also { it.parentFile.mkdirs() }
        .writeBytes(bytes)
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
        ClassDesc.of(this.java.name)
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
            KClass::class.descriptor(),
            Class::class.descriptor(),
        ),
    )
    return this
}

/**
 * Helper function to build the props with the name of the columns and its kotlin class representative.
 *
 * @param klass The kotlin representative of the domain class of the Repository
 * @param constructor The constructor for the domain class
 * @return A map of the column names for the ResultSet and their respective kotlin class representative
 */
private fun buildProps(
    klass: KClass<*>,
    constructor: KFunction<Any>,
): Map<String, KClass<Any>> =
    constructor.parameters.let {
        it
            .associate { constParam ->
                val columnName =
                    constParam.findAnnotations(Column::class).firstOrNull()?.name
                        ?: constParam.name
                        ?: throw IllegalStateException("Missing name for column in table.")
                val classifier =
                    klass
                        .declaredMemberProperties
                        .first { prop ->
                            prop.name == constParam.name
                        }.let { prop ->
                            prop.returnType.classifier as? KClass<Any>
                        }
                checkNotNull(classifier)

                columnName to classifier
            }
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
    props: Map<String, KClass<Any>>,
) {
    // constants for special local variable slots
    val thisSlot = 0
    val resultSetSlot = 1
    val firstConstructorParamSlot = 2

    // stores the indexes for the local variable slots
    val paramLocals = mutableListOf<Int>()
    // stores the types of the local variables
    val paramTypes = mutableListOf<KClass<*>>()
    // starts at 2 because 0 is this, 1 is ResultSet
    var localIndex = firstConstructorParamSlot

    // run through constructor parameters to get all the indexes for the localVariables and the types
    constructor.parameters.forEach { param ->
        paramLocals += localIndex
        val klass = param.type.classifier as KClass<*>
        paramTypes.add(klass)

        // Long and Double require 2 slots, others only 1
        // for reference go to: https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-2.html#jvms-2.6.1
        localIndex +=
            when (klass) {
                Long::class, Double::class -> 2
                else -> 1
            }
    }

    props.toList().forEachIndexed { index, (columnName, classifier) ->
        val targetSlot = paramLocals[index]

        // get the descriptor of the boxed type
        val boxedDesc = classifier.javaObjectType.kotlin.descriptor()
        // push superclass to be able to call getValue that is from the extended class
        aload(thisSlot)
        // push classifier to the stack
        ldc(constantPool().classEntry(boxedDesc))

        // create entry in constant pool with the name of the parameter for the result set
        ldc(constantPool().stringEntry(columnName))

        // call superclass.getValue(KClass, String)
        invokevirtual(
            superClassDesc,
            "getValue",
            MethodTypeDesc.of(
                ClassDesc.of("kotlin.Function1"),
                KClass::class.descriptor(),
                CD_String,
            ),
        )

        // push the resultSet to the stack
        aload(resultSetSlot)
        // call the function returned by getValue, (ResultSet) -> Any implements Function<ResultSet, Object>
        invokeinterface(
            ClassDesc.of("kotlin.Function1"),
            "invoke",
            MethodTypeDesc.of(CD_Object, CD_Object),
        )

        // cast to the expected boxed type
        checkcast(boxedDesc)

        // store the result according to its type, also primitive values need unboxing
        when (classifier) {
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
            else -> astore(targetSlot)
        }
    }

    // create instance of final object
    new_(domainKlassDesc)
    // duplicate the reference for the constructor call
    dup()

    // push to the stack every parameter of the constructor obtained previously in order
    for (index in paramLocals.indices) {
        val slot = paramLocals[index]
        val type = paramTypes[index]

        when (type) {
            Long::class -> lload(slot)
            Double::class -> dload(slot)
            Int::class -> iload(slot)
            else -> aload(slot)
        }
    }

    val constructorDesc =
        MethodTypeDesc.of(
            CD_void,
            paramTypes.map {
                when (it) {
                    Long::class -> CD_long
                    Double::class -> CD_double
                    Int::class -> CD_int
                    else -> it.javaObjectType.kotlin.descriptor()
                }
            },
        )
    // with the values in the stack, call the constructor
    invokespecial(
        domainKlassDesc,
        INIT_NAME,
        constructorDesc,
    )

    // return from the function
    areturn()
}
