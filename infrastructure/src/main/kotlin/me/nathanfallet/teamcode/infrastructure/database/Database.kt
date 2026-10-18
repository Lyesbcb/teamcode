package me.nathanfallet.teamcode.infrastructure.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway

/** Explicit JDBC transactions keep row locks and affected-row checks visible at admission boundaries. */
class Database(url: String, username: String, password: String) : AutoCloseable {
    private val source = HikariDataSource(HikariConfig().apply {
        jdbcUrl = url; this.username = username; this.password = password
        maximumPoolSize = 8; connectionTimeout = 5_000; transactionIsolation = "TRANSACTION_READ_COMMITTED"
    })
    fun migrate() { Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate() }
    suspend fun <T> transaction(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        source.connection.use { connection ->
            connection.autoCommit = false
            try { val result = block(connection); connection.commit(); result }
            catch (error: Throwable) { connection.rollback(); throw error }
        }
    }
    override fun close() = source.close()
}
internal fun PreparedStatement.bind(values: Array<out Any?>) { values.forEachIndexed { index, value -> setObject(index + 1, value) } }
internal fun Connection.execute(sql: String, vararg values: Any?): Int = prepareStatement(sql).use { it.bind(values); it.executeUpdate() }
internal fun <T> Connection.rows(sql: String, vararg values: Any?, map: (ResultSet) -> T): List<T> = prepareStatement(sql).use {
    it.bind(values); it.executeQuery().use { result -> buildList { while (result.next()) add(map(result)) } }
}
internal fun Connection.lockTeam(team: String) {
    check(rows("SELECT id FROM teams WHERE id = ? FOR UPDATE", team) { it.getString(1) }.size == 1)
}
internal fun Connection.audit(team: String, actor: String, action: String, subject: String) {
    execute("INSERT INTO audit_events (team_id, actor_id, action, subject_id, occurred_at) VALUES (?, ?, ?, ?, ?)", team, actor, action, subject, System.currentTimeMillis())
}
