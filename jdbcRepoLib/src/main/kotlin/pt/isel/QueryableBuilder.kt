package pt.isel

import java.sql.Connection
import java.sql.ResultSet
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

data class SeqProps(
    val oper: Operation,
    val columnName: String,
    val value: Any?,
    val classifier: KClass<*>,
)
enum class Operation {
    WHERE,
    ORDER,
}

class QueryableBuilder<T>(
    private val connection: Connection,
    private val sqlQuery: String,
    private val properties: Map<KProperty<*>, String>,
    private val mapper: (rs: ResultSet) -> T
) : Queryable<T>{
    val usedProps = mutableListOf<SeqProps>()

    override fun <V> whereEquals(prop: KProperty1<T, V>, value: V): Queryable<T> {
        TODO()
    }

    override fun <V> orderBy(prop: KProperty1<T, V>): Queryable<T> {
        TODO("Not yet implemented")
    }


    override fun iterator(): Iterator<T> =
        sequence {
            connection.prepareStatement(queryBuilder(sqlQuery)).use { stmt ->
                usedProps.forEachIndexed {  index, (_, _, value, classifier) ->
                    stmt.seqSetter(value, index, classifier)
                }

                stmt.executeQuery().use{ rs ->
                    while (rs.next()) {
                        yield(mapper(rs))
                    }
                }
            }
        }.iterator()

    private fun queryBuilder(sql: String): String {
        TODO()
    }
}
