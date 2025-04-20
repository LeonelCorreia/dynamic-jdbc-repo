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
import kotlin.reflect.KType

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
private val repositories = mutableMapOf<KClass<*>, RepositoryReflect<*, *>>()

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
        .call(connection) as RepositoryReflect<*, *>
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
    val bytes =
        ClassFile
            .of()
            .build(ClassDesc.of("$PACKAGE_NAME.$className")) { clb ->
                // the class generated is a subclass of RepositoryReflect
                clb.withSuperclass(RepositoryReflect::class.descriptor())

                clb.withMethod(
                    INIT_NAME,
                    MethodTypeDesc.of(
                        CD_void,
                        Connection::class.descriptor(),
                    ),
                    ACC_PUBLIC,
                ) { mb ->
                    mb.withCode { cb ->
                        cb
                            .aload(0)
                            .aload(1)
                            .ldc(cb.constantPool().classEntry(domainKlass.descriptor()))
                            .toKClass()
                            .invokespecial(
                                RepositoryReflect::class.descriptor(),
                                INIT_NAME,
                                MethodTypeDesc.of(
                                    CD_void,
                                    Connection::class.descriptor(),
                                    KClass::class.descriptor(),
                                ),
                            ).return_()
                    }
                }
            }
    File(root, "$PACKAGE_NAME.$className".replace(".", "/") + ".class")
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
