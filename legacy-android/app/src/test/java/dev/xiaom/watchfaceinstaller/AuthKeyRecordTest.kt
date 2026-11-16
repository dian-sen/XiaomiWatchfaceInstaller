package dev.xiaom.watchfaceinstaller

import org.junit.Assert.*
import org.junit.Test

class AuthKeyRecordTest {
    // Entirely synthetic fixtures; no live database or root command is accessed by tests.
    private val hex = "A1".repeat(16)
    private fun json(key: String = hex) = """{"encrypt_key":"$key","mac":"01:23:45:67:89:AB","token":"ignored","beaconkey":"ignored","irq_key":"ignored"}"""
    @Test fun acceptsOnlyTargetFieldAndModel() {
        val record = AuthKeyRecord.parse(AuthKeyRecord.MODEL, json())
        assertEquals(AuthKeyRecord.MODEL, record.model)
        assertEquals("01:23:45:67:89:AB", record.mac)
        assertFalse(record.toString().contains(hex))
        record.close(); record.close()
    }
    @Test fun closeZeroesSyntheticSecretStorage() {
        val record = AuthKeyRecord.parse(AuthKeyRecord.MODEL, json())
        val field = AuthKeyRecord::class.java.getDeclaredField("key").apply { isAccessible = true }
        val buffer = field.get(record) as CharArray
        assertTrue(buffer.any { it != '\u0000' })
        record.close()
        assertTrue(buffer.all { it == '\u0000' })
    }
    @Test fun missingNullAndNonStringFieldsFailClosed() {
        reject(AuthKeyRecord.MODEL, "{}")
        reject(AuthKeyRecord.MODEL, """{"encrypt_key":null,"mac":"01:23:45:67:89:AB"}""")
        reject(AuthKeyRecord.MODEL, """{"encrypt_key":"$hex","mac":123}""")
        reject(AuthKeyRecord.MODEL, """{"encrypt_key":"$hex"}""")
    }
    @Test fun rejectsWrongModelAndKeys() {
        reject("other.model", json())
        for (key in listOf("", "A1".repeat(15), "A1".repeat(17), "G1".repeat(16), " $hex")) reject(AuthKeyRecord.MODEL, json(key))
        reject(AuthKeyRecord.MODEL, """{"token":"$hex","mac":"01:23:45:67:89:AB"}""")
        reject(AuthKeyRecord.MODEL, """{"encrypt_key":123,"mac":"01:23:45:67:89:AB"}""")
        reject(AuthKeyRecord.MODEL, json().replace("01:23:45:67:89:AB", "invalid"))
        reject(AuthKeyRecord.MODEL, "not JSON $hex")
        reject(AuthKeyRecord.MODEL, " ".repeat(65537))
    }
    private fun reject(model: String, detail: String) {
        try { AuthKeyRecord.parse(model, detail); fail("expected rejection") }
        catch (error: IllegalArgumentException) {
            assertEquals("设备记录校验失败", error.message)
            assertNull(error.cause)
            assertFalse(error.toString().contains(hex))
        }
    }
    @Test fun fixedShellScriptsHaveValidSyntaxWithoutExecutingThem() {
        for (namespace in listOf(false, true)) {
            for (script in listOf(AuthKeyCommands.probe(namespace), AuthKeyCommands.snapshot(namespace))) {
                val process = ProcessBuilder("/bin/sh", "-n").start()
                process.outputStream.bufferedWriter().use { it.write(script) }
                assertEquals("Shell parse failed", 0, process.waitFor())
            }
        }
    }
    @Test fun commandsAreFixedAndBounded() {
        for (namespace in listOf(false, true)) {
            val snapshot = AuthKeyCommands.snapshot(namespace)
            assertTrue(snapshot.contains(AuthKeyCommands.DATABASE))
            assertTrue(snapshot.contains(AuthKeyCommands.BASE_LIMIT.toString()))
            assertTrue(snapshot.contains(AuthKeyCommands.WAL_LIMIT.toString()))
            assertTrue(snapshot.contains("head -c"))
            assertFalse(snapshot.contains("encrypt_key"))
            for (forbidden in listOf("setenforce", "magiskpolicy", "supolicy", "chmod", "chown", "sqlite3", "rm ", "cp ")) assertFalse(snapshot.contains(forbidden))
            assertEquals(namespace, snapshot.startsWith("nsenter -t 1 -m -- /system/bin/sh -c "))
            assertTrue(AuthKeyCommands.probe(namespace).contains("id -u"))
        }
    }
}
