package me.nathanfallet.teamcode.infrastructure.database

import java.sql.Connection
import java.util.UUID
import kotlinx.coroutines.*

/** Database-clock leases coordinate workers without a pod identity or an in-memory cursor. */
class SqlScheduledWork(private val database: Database) {
    suspend fun runDue(name: String, intervalMillis: Long = 60_000, timeoutMillis: Long = 45_000, block: suspend () -> Unit): Boolean {
        require(name.length in 1..100 && intervalMillis > 0 && timeoutMillis in 1..300_000)
        val owner = UUID.randomUUID().toString()
        val claimed = database.transaction { c ->
            c.execute("INSERT INTO scheduled_work (name) VALUES (?) ON DUPLICATE KEY UPDATE name = name", name)
            c.rows("SELECT name FROM scheduled_work WHERE name = ? FOR UPDATE", name) { it.getString(1) }
            val now = c.nowMillis()
            c.execute("UPDATE scheduled_work SET owner = ?, available_at = ?, last_started_at = ? WHERE name = ? AND available_at <= ?", owner, now + timeoutMillis + 15_000, now, name, now) == 1
        }
        if (!claimed) return false
        try { withTimeout(timeoutMillis) { block() } }
        finally {
            // A stale worker cannot release its successor's lease. A crash leaves a bounded lease.
            withContext(NonCancellable) { database.transaction { c ->
                c.execute("UPDATE scheduled_work SET owner = NULL, available_at = ? WHERE name = ? AND owner = ?", c.nowMillis() + intervalMillis, name, owner)
            } }
        }
        return true
    }

    suspend fun dueAccounts(): List<String> = database.transaction { c ->
        c.rows("SELECT a.id FROM accounts a LEFT JOIN scheduled_work w ON w.name = CONCAT('quota/', a.id) WHERE a.disabled = FALSE AND a.state IN ('READY', 'ACCESS_ONLY') AND (w.available_at IS NULL OR w.available_at <= ?) ORDER BY COALESCE(w.last_started_at, 0), a.id LIMIT 8", c.nowMillis()) { it.getString(1) }
    }
}

internal fun Connection.nowMillis(): Long = rows("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)") { it.getLong(1) }.single()

/** Shared by dashboard and CLI login, including unknown usernames. Denials commit their count. */
internal class SqlLoginBudget(private val database: Database) {
    suspend fun admit(): Boolean = database.transaction { c ->
        val row = c.rows("SELECT window_number, attempts FROM login_budget WHERE id = 1 FOR UPDATE") { it.getLong(1) to it.getInt(2) }.single()
        val window = c.nowMillis() / 60_000
        val attempts = if (row.first == window) row.second else 0
        c.execute("UPDATE login_budget SET window_number = ?, attempts = ? WHERE id = 1", window, minOf(attempts + 1, 121))
        attempts < 120
    }
}
