package pt.isel

import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotations

fun KClass<*>.getTableName(): String =
    findAnnotations(Table::class).firstOrNull()?.name ?: simpleName
        ?: error("Missing table name")

fun KClass<*>.getPkProp(): KProperty<*> =
    declaredMemberProperties.firstOrNull { it.findAnnotations(Pk::class).isNotEmpty() }
        ?: error("No primary key found for class $this")

fun KProperty1<out Any, *>.contains(annot: KClass<out Annotation>): Boolean = this.annotations.any { it.annotationClass == annot }

fun KParameter.columnName(): String =
    findAnnotations(Column::class).firstOrNull()?.name
        ?: name ?: error("No name found for parameter $name in ${this::class}")
