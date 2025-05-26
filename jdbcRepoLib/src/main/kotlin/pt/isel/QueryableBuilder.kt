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
    private val mapper: (rs: ResultSet) -> T,
) : Queryable<T> {
    val usedProps = mutableListOf<SeqProps>()

    override fun <V> whereEquals(
        prop: KProperty1<T, V>,
        value: V,
    ): Queryable<T> {
        TODO()
    }

    override fun <V> orderBy(prop: KProperty1<T, V>): Queryable<T> {
        val columnName = properties[prop] ?: throw IllegalArgumentException()
        usedProps.add(
            SeqProps(
                Operation.ORDER,
                columnName,
                null,
                prop.returnType.classifier as KClass<*>,
            ),
        )
        return this
    }
    /*
    override fun iterator(): Iterator<T> =
        sequence {
            connection.prepareStatement(queryBuilder(sqlQuery)).use { stmt ->
                usedProps.forEachIndexed { index, (_, _, value, classifier) ->
                    stmt.seqSetter(value, index + 1, classifier)
                }

                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        yield(mapper(rs))
                    }
                }
            }
        }.iterator()*/

    override fun iterator(): Iterator<T> =
        object : Iterator<T> {
            val stmt =
                connection.prepareStatement(queryBuilder(sqlQuery)).apply {
                    setStatementParameters(usedProps, this)
                }
            val rs: ResultSet = stmt.executeQuery()
            var nextResult: Boolean? = null

            override fun hasNext(): Boolean {
                if (nextResult == null) {
                    nextResult = rs.next()
                    if (nextResult == false) {
                        rs.close()
                        stmt.close()
                    }
                }
                return nextResult!!
            }

            override fun next(): T {
                if (!hasNext()) throw NoSuchElementException()
                val result = mapper(rs)
                nextResult = null
                return result
            }
        }

    fun queryBuilder(sql: String): String {
        val whereClauses = usedProps.filter { it.oper == Operation.WHERE }
        val orderByClauses = usedProps.filter { it.oper == Operation.ORDER }
        val whereAndOrderClauses = StringBuilder()
        val orderedProps = mutableListOf<SeqProps>()

        if (whereClauses.isNotEmpty()) {
            whereAndOrderClauses.append(" WHERE ")
            whereClauses.forEachIndexed { index, (_, columnName, value, _) ->
                if (index > 0) whereAndOrderClauses.append(" AND ")
                val formattedValue =
                    when (value) {
                        null -> "IS NULL"
                        is String -> "'$value'"
                        else -> value.toString()
                    }
                if (value == null) {
                    whereAndOrderClauses.append("$columnName $formattedValue")
                } else {
                    whereAndOrderClauses.append("$columnName = $formattedValue")
                }
                orderedProps.add(whereClauses[index])
            }
        }

        if (orderByClauses.isNotEmpty()) {
            whereAndOrderClauses.append(" ORDER BY ")
            orderByClauses.forEachIndexed { index, (_, columnName, _, _) ->
                if (index > 0) whereAndOrderClauses.append(", ")
                whereAndOrderClauses.append(columnName)
                orderedProps.add(orderByClauses[index])
            }
        }

        usedProps.clear()
        usedProps.addAll(orderedProps)

        return sql.replace("...", "$whereAndOrderClauses")
    }
}
