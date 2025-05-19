package pt.isel

import java.sql.Connection
import java.sql.ResultSet
import kotlin.reflect.KProperty1

class QueryableBuilder<T>(
    private val connection: Connection,
    private val sqlQuery: String,
    private val properties: List<String>, // Map/ list with the information about the propertis of T
    private val mapper: (rs: ResultSet) -> T
) : Queryable<T>{
    override fun <V> whereEquals(prop: KProperty1<T, V>, value: V): Queryable<T> {
        TODO("Not yet implemented")
    }

    override fun <V> orderBy(prop: KProperty1<T, V>): Queryable<T> {
        TODO("Not yet implemented")
    }

    override fun iterator(): Iterator<T> {
        TODO("Not yet implemented")
    }

}
