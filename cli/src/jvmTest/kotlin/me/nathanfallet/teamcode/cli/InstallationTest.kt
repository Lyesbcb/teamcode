package me.nathanfallet.teamcode.cli

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import me.nathanfallet.teamcode.cli.config.*
import kotlin.test.*

class InstallationTest {
    @Test fun concurrentFirstUsePublishesOneCompletePrivateIdentityAndLogoutPreservesIt() {
        val root = Files.createTempDirectory("teamcode-installation-test-")
        try {
            val identity = root.resolve("teamcode/installation-id")
            val start = CountDownLatch(1)
            val threads = Executors.newFixedThreadPool(8)
            val ids = try {
                val futures = (1..8).map { threads.submit<String> { start.await(); InstallationStore(identity.toString()).id() } }
                start.countDown()
                futures.map { it.get(10, TimeUnit.SECONDS) }
            } finally { threads.shutdown() }
            assertEquals(1, ids.toSet().size)
            assertEquals(ids.first(), Files.readString(identity))
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(identity)))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(identity.parent)))
            val config = ConfigStore(root.resolve("teamcode/config.json").toString())
            config.save(CliConfig("https://team.example", "synthetic-token"))
            config.delete()
            assertEquals(ids.first(), InstallationStore(identity.toString()).id())
            assertEquals(listOf(identity), Files.list(identity.parent).use { it.toList() }, "No partial/temp file may remain")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun malformedOrLinkedIdentityIsRejectedAndNeverOverwritten() {
        val root = Files.createTempDirectory("teamcode-invalid-installation-")
        try {
            val identity = root.resolve("installation-id")
            Files.writeString(identity, "corrupt")
            assertFailsWith<IllegalArgumentException> { InstallationStore(identity.toString()).id() }
            assertEquals("corrupt", Files.readString(identity))
            Files.delete(identity)
            val target = root.resolve("target")
            Files.writeString(target, "00000000-0000-0000-0000-000000000001")
            Files.createSymbolicLink(identity, target)
            assertFailsWith<IllegalArgumentException> { InstallationStore(identity.toString()).id() }
            assertTrue(Files.isSymbolicLink(identity))
        } finally { root.toFile().deleteRecursively() }
    }
}
