package com.dhinasuthra.app.database

import com.dhinasuthra.app.core.database.MigrationDb
import com.dhinasuthra.app.core.database.MigrationRow
import java.sql.Connection
import java.sql.DriverManager

/**
 * A [MigrationDb] backed by a real SQLite engine on the JVM.
 *
 * Plan §25 asks for upgrade tests from realistic old databases. An
 * instrumentation test would need a device or emulator and would therefore not
 * run on every push; this does, and it executes the exact same migration code
 * that ships — that's the whole reason `DsMigration.forward` is written against
 * an interface rather than against Android's `SupportSQLiteDatabase`.
 */
class JdbcMigrationDb(private val connection: Connection) : MigrationDb, AutoCloseable {

    override fun exec(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    override fun exec(sql: String, args: Array<Any?>) {
        connection.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.execute()
        }
    }

    override fun <T> query(sql: String, args: Array<Any?>, read: (MigrationRow) -> T): List<T> {
        connection.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs ->
                val row = object : MigrationRow {
                    // JDBC indexes from 1; the migration API indexes from 0.
                    override fun getLong(index: Int) = rs.getLong(index + 1)
                    override fun getInt(index: Int) = rs.getInt(index + 1)
                    override fun getString(index: Int): String? = rs.getString(index + 1)
                }
                val out = mutableListOf<T>()
                while (rs.next()) out.add(read(row))
                return out
            }
        }
    }

    /** The version SQLite itself records — what Room reads to decide whether to migrate. */
    var userVersion: Int
        get() = query("PRAGMA user_version") { it.getInt(0) }.firstOrNull() ?: 0
        set(value) = exec("PRAGMA user_version = $value")

    fun columnTypes(table: String): Map<String, String> =
        query("PRAGMA table_info(`$table`)") { it.getString(1).orEmpty() to it.getString(2).orEmpty() }
            .toMap()

    fun notNullColumns(table: String): Set<String> =
        query("PRAGMA table_info(`$table`)") { it.getString(1).orEmpty() to it.getInt(3) }
            .filter { it.second == 1 }.map { it.first }.toSet()

    fun indexNames(table: String): Set<String> =
        query("PRAGMA index_list(`$table`)") { it.getString(1).orEmpty() }.toSet()

    fun tableNames(): Set<String> =
        query("SELECT name FROM sqlite_master WHERE type='table'") { it.getString(0).orEmpty() }.toSet()

    override fun close() = connection.close()

    companion object {
        fun inMemory(): JdbcMigrationDb =
            JdbcMigrationDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
    }
}
