package pt.isel

import java.sql.Connection
import java.sql.ResultSet
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty1

data class SeqProps(
    val oper: Operation,
    val columnName: String,
    val value: Any?,
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
        val colName = properties[prop]
        requireNotNull(colName) { "No such property in the class." }

        usedProps.add(SeqProps(Operation.WHERE, colName, value))

        return this
    }

    override fun <V> orderBy(prop: KProperty1<T, V>): Queryable<T> {
        val colName = properties[prop]
        requireNotNull(colName) { "No such property in the class." }

        usedProps.add(SeqProps(Operation.ORDER, colName, null))

        return this
    }

    override fun iterator(): Iterator<T> =
        sequence {
            val query = queryBuilder(sqlQuery)
            connection.prepareStatement(query).executeQuery().use { rs ->
                while (rs.next()) {
                    yield(mapper(rs))
                }
            }
        }.iterator()

    private fun queryBuilder(sql: String): String {
        val whereClauses = usedProps.filter { it.oper == Operation.WHERE }
        val orderByClauses = usedProps.filter { it.oper == Operation.ORDER }
        val whereAndOrderClauses = StringBuilder()
        val orderedProps = mutableListOf<SeqProps>()

        if (whereClauses.isNotEmpty()) {
            whereAndOrderClauses.append(" WHERE ")
            whereClauses.forEachIndexed { index, (_, columnName, value) ->
                if (index > 0) whereAndOrderClauses.append(" AND ")
                val formattedValue =
                    when (value) {
                        null -> "IS NULL"
                        is String -> "'$value'"
                        is Enum<*> -> "'$value'"
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
            orderByClauses.forEachIndexed { index, (_, columnName, _) ->
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
