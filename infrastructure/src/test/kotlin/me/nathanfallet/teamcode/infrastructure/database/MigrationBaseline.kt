package me.nathanfallet.teamcode.infrastructure.database

/** Development-only entry point for the owned database created by scripts/generate-migrations.py. */
fun main(args: Array<String>) {
    val port = args.single().toInt()
    require(port in 1..65535)
    Database("jdbc:mysql://127.0.0.1:$port/teamcode", "root", "teamcode-migration-fixture").use { it.migrate() }
}
