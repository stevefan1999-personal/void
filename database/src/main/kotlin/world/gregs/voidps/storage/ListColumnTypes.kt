package world.gregs.voidps.storage

import org.jetbrains.exposed.sql.ArrayColumnType
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.IntegerColumnType
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.jetbrains.exposed.sql.vendors.currentDialect
import java.util.Base64

/**
 * List columns persist natively on PostgreSQL (as `INT[]` / `TEXT[]`) so existing
 * PG deployments keep their schema untouched. Dialects that do not support array
 * types – MySQL, MariaDB, Oracle, SQL Server – fall back to a single TEXT/CLOB
 * column with a portable text encoding:
 *   - List<String>: newline-separated Base64 (UTF-8) entries.
 *   - List<Int>:    comma-separated decimal entries.
 *
 * Empty lists serialise to the empty string and round-trip cleanly.
 */
internal object ListEncoding {
    private val encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    fun encodeStrings(list: List<String>): String =
        if (list.isEmpty()) "" else list.joinToString("\n") { encoder.encodeToString(it.toByteArray(Charsets.UTF_8)) }

    fun decodeStrings(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split('\n').map { String(decoder.decode(it), Charsets.UTF_8) }

    fun encodeInts(list: List<Int>): String = list.joinToString(",")

    fun decodeInts(text: String): List<Int> =
        if (text.isEmpty()) emptyList() else text.split(',').map(String::toInt)
}

internal abstract class DialectAwareListColumnType<T>(
    private val pgDelegate: ArrayColumnType,
) : ColumnType() {

    private val onPostgres: Boolean
        get() = currentDialect is PostgreSQLDialect

    protected abstract fun encode(list: List<T>): String
    protected abstract fun decode(text: String): List<T>

    override fun sqlType(): String =
        if (onPostgres) pgDelegate.sqlType() else currentDialect.dataTypeProvider.textType()

    @Suppress("UNCHECKED_CAST")
    override fun valueFromDB(value: Any): List<T> {
        if (onPostgres) return pgDelegate.valueFromDB(value) as List<T>
        val text = when (value) {
            is String -> value
            is java.sql.Clob -> value.getSubString(1L, value.length().toInt())
            else -> value.toString()
        }
        return decode(text)
    }

    @Suppress("UNCHECKED_CAST")
    override fun notNullValueToDB(value: Any): Any {
        val list = value as List<T>
        return if (onPostgres) pgDelegate.notNullValueToDB(list) else encode(list)
    }

    override fun setParameter(stmt: PreparedStatementApi, index: Int, value: Any?) {
        if (onPostgres) {
            pgDelegate.setParameter(stmt, index, value)
        } else {
            super.setParameter(stmt, index, value)
        }
    }

    // On PostgreSQL we forward to ArrayColumnType which formats the list the
    // native way. On other dialects we short-circuit the default IColumnType
    // logic – it iterates Collections and calls nonNullValueToString on each
    // element, which would reinterpret each list item as a List and fail.
    override fun valueToString(value: Any?): String = when {
        value == null -> "NULL"
        onPostgres -> pgDelegate.valueToString(value)
        else -> nonNullValueToString(value)
    }

    @Suppress("UNCHECKED_CAST")
    override fun nonNullValueToString(value: Any): String =
        if (onPostgres) pgDelegate.nonNullValueToString(value) else "'${encode(value as List<T>)}'"
}

internal class StringListColumnType :
    DialectAwareListColumnType<String>(ArrayColumnType(TextColumnType())) {
    override fun encode(list: List<String>) = ListEncoding.encodeStrings(list)
    override fun decode(text: String) = ListEncoding.decodeStrings(text)
}

internal class IntListColumnType :
    DialectAwareListColumnType<Int>(ArrayColumnType(IntegerColumnType())) {
    override fun encode(list: List<Int>) = ListEncoding.encodeInts(list)
    override fun decode(text: String) = ListEncoding.decodeInts(text)
}

internal fun Table.stringList(name: String): Column<List<String>> =
    registerColumn(name, StringListColumnType())

internal fun Table.intList(name: String): Column<List<Int>> =
    registerColumn(name, IntListColumnType())
