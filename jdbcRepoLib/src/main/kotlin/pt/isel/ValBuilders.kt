package pt.isel

import java.sql.Connection
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotations
import kotlin.reflect.full.primaryConstructor

fun <T : Any> buildClassifiers(domainKlass: KClass<T>) =
    mutableMapOf<KProperty<*>, KClass<*>>().apply {
        domainKlass
            .declaredMemberProperties
            .forEach { prop ->
                val classifier =
                    (prop.returnType.classifier as? KClass<Any>)
                        ?: throw IllegalStateException("Invalid classifier for property: ${prop.name}")

                this[prop] = classifier
            }
    }

fun <T : Any> buildPk(domainKlass: KClass<T>): KProperty<*> =
    domainKlass
        .declaredMemberProperties
        .first { prop -> prop.findAnnotations(Pk::class).isNotEmpty() }

fun <T : Any> buildTableName(domainKlass: KClass<T>): String =
    domainKlass
        .findAnnotations(Table::class)
        .firstOrNull()
        ?.name
        ?: throw IllegalArgumentException("Missing @Table annotation in class $domainKlass")

fun <T : Any> buildConstructor(domainKlass: KClass<T>): KFunction<T> =
    domainKlass
        .primaryConstructor
        ?: throw IllegalStateException("No suitable constructor found for $domainKlass")

fun buildPropsMap(
    constructor: KFunction<*>,
    classifiers: MutableMap<KProperty<*>, KClass<*>>,
    pk: KProperty<*>,
    tableName: String,
    auxRepos: MutableMap<KClass<*>, BaseRepository<Any, Any>>,
): Map<GetPropInfo, SetPropInfo?> =
    constructor.parameters.let {
        it
            .withIndex()
            .associate { (index, constParam) ->
                val columnName =
                    constParam.findAnnotations(Column::class).firstOrNull()?.name
                        ?: constParam.name
                        ?: throw IllegalStateException("Missing name for column in table $tableName.")
                val prop = classifiers.keys.firstOrNull { prop -> prop.name == constParam.name }
                checkNotNull(prop)

                val classifier = classifiers[prop]
                checkNotNull(classifier)

                val getProp =
                    GetPropInfo.Reflect(
                        constParam,
                        getValue(classifier, columnName, auxRepos),
                        columnName,
                    )

                val setProp =
                    if (constParam.name == pk.name) {
                        null
                    } else {
                        SetPropInfo(columnName, constParam) { entity ->
                            val value = prop.call(entity)
                            setValue(value, index, classifier, auxRepos)
                        }
                    }

                getProp to setProp
            }
    }

fun addAuxRepos(
    domainKlass: KClass<*>,
    connection: Connection,
    auxRepos: MutableMap<KClass<*>, BaseRepository<Any, Any>>,
) {
    domainKlass
        .declaredMemberProperties
        .forEach { prop ->
            val classifier =
                (prop.returnType.classifier as? KClass<Any>)
                    ?: throw IllegalStateException("Invalid classifier for property: ${prop.name}")

            if (!classifier.isPrimitiveOrStringOrDate() && !classifier.isEnum()) {
                auxRepos[classifier] = RepositoryReflect(connection, classifier)
            }
        }
}

fun addDynAuxRepos(
    domainKlass: KClass<*>,
    connection: Connection,
    auxRepos: MutableMap<KClass<*>, BaseRepository<Any, Any>>,
) {
    domainKlass
        .declaredMemberProperties
        .forEach { prop ->
            val classifier =
                (prop.returnType.classifier as? KClass<Any>)
                    ?: throw IllegalStateException("Invalid classifier for property: ${prop.name}")

            if (!classifier.isPrimitiveOrStringOrDate() && !classifier.isEnum()) {
                auxRepos[classifier] = loadDynamicRepo(connection, classifier)
            }
        }
}

fun buildPropList(
    constructor: KFunction<*>,
    classifiers: MutableMap<KProperty<*>, KClass<*>>,
    tableName: String,
): Map<KProperty<*>, String> =
    constructor.parameters.let {
        it
            .associate { constParam ->
                val columnName =
                    constParam.findAnnotations(Column::class).firstOrNull()?.name
                        ?: constParam.name
                        ?: throw IllegalStateException("Missing name for column in table $tableName.")
                val prop = classifiers.keys.firstOrNull { prop -> prop.name == constParam.name }
                checkNotNull(prop)

                prop to columnName
            }
    }
