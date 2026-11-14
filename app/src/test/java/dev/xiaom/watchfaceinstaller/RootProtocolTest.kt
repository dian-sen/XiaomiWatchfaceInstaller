package dev.xiaom.watchfaceinstaller

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RootProtocolTest {
    private val root = RootPolicy.roots.first()
    private fun record(kind: String = "FILE", path: String = "$root/a.bin", a: String = "3", b: String = "12") =
        listOf(kind, path, a, b).joinToString("\u0000", postfix = "\u0000")
    private fun rejects(text: String) {
        assertThrows(IOException::class.java) { RootProtocol.parse(text) }
    }

    @Test fun emptyScanIsSuccessful() {
        assertEquals(RootScan(emptyList(), emptyList(), false), RootProtocol.parse(""))
    }

    @Test fun preservesQuotedUnicodeAndNewlineNames() {
        val name = "中'\"\n\$(printf injected).BIN"
        val entry = RootProtocol.parse(record(path = "$root/$name")).files.single()
        assertEquals(name, entry.name)
        assertEquals("$root/$name", entry.path)
        assertEquals(3L, entry.size)
        assertEquals(12000L, entry.modified)
    }

    @Test fun acceptsBoundaryValues() {
        val files = RootProtocol.parse(record(a = "0", b = "0") +
            record(path = "$root/b.bin", a = WatchfaceFiles.MAX_BYTES.toString(), b = (Long.MAX_VALUE / 1000).toString())).files
        assertEquals(0L, files.first().size)
        assertEquals(WatchfaceFiles.MAX_BYTES, files.last().size)
    }

    @Test fun rejectsIncompleteUnknownAndExtraFields() {
        rejects(record().dropLast(1))
        rejects("\u0000")
        rejects(record() + "extra\u0000")
        rejects(record(kind = "BOGUS"))
    }

    @Test fun rejectsEscapingAndNonBinPaths() {
        for (path in listOf("/etc/secret.bin", "$root/../secret.bin", "$root/a.txt", "$root//a.bin")) {
            rejects(record(path = path))
        }
    }

    @Test fun rejectsInvalidSizesAndTimestamps() {
        for (size in listOf("-1", "no", "33554433", "999999999999999999999")) rejects(record(a = size))
        for (time in listOf("-1", "no", Long.MAX_VALUE.toString())) rejects(record(b = time))
    }

    @Test fun rejectsDuplicatePaths() { rejects(record() + record()) }

    @Test fun enforcesFileAndOutputCaps() {
        assertEquals(200, RootProtocol.parse((0 until 200).joinToString("") { record(path = "$root/$it.bin") }).files.size)
        rejects((0..200).joinToString("") { record(path = "$root/$it.bin") })
        rejects("x".repeat(RootPolicy.MAX_OUTPUT + 1))
    }

    @Test fun reportsDiagnosticsAndTruncation() {
        val result = RootProtocol.parse(listOf("MISSING", "DENIED", "LIMIT", "LINK", "ESCAPE").joinToString("") { record(kind = it) })
        assertTrue(result.files.isEmpty())
        assertEquals(5, result.diagnostics.size)
        assertTrue(result.truncated)
    }

    @Test fun capsDiagnostics() {
        val result = RootProtocol.parse(record(kind = "DENIED").repeat(101))
        assertEquals(100, result.diagnostics.size)
        assertTrue(result.truncated)
    }
}
