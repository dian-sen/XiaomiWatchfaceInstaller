package org.zxor.oronbox

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WatchfaceCacheRootPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = WatchfaceCacheFixture(temporary.newFolder())
    private fun scan(fixture: WatchfaceCacheFixture, shell: WatchfaceCacheRootShell = fixture.shell()) =
        WatchfaceCacheScanProtocol.parse(shell.read(WatchfaceCacheRootPolicy.scanCommand(), WatchfaceCacheRootPolicy.MAX_SCAN_BYTES))

    @Test fun onlyFixedRootsBinDescendantsAndFixedOptionalPathsAreAllowed() {
        val root = WatchfaceCacheRootPolicy.roots.first()
        for (bad in listOf("/etc/a.bin", "$root-other/a.bin", root, "$root/../a.bin", "$root/./a.bin",
            "$root//a.bin", "$root/a.txt", "$root/a.bin\u0000", "$root/" + "a".repeat(4096) + ".bin")) {
            assertThrows(IllegalArgumentException::class.java) { WatchfaceCacheRootPolicy.readBinCommand(bad) }
        }
        val bin = "$root/120917345196/resource.bin"
        WatchfaceCacheMetadata.XML_PATHS.forEach { WatchfaceCacheRootPolicy.metadataCommand(bin, it) }
        for (relative in listOf("/etc/passwd", "../description.xml", "other.xml", "resources/../description.xml")) {
            assertThrows(IllegalArgumentException::class.java) { WatchfaceCacheRootPolicy.metadataCommand(bin, relative) }
        }
        for (relative in listOf("resources/_preview/../x.png", "resources/_preview/sub/x.jpg", "_preview/x.png", "/etc/x.png")) {
            assertThrows(IllegalArgumentException::class.java) { WatchfaceCacheRootPolicy.previewCommand(bin, relative) }
        }
        assertThrows(IllegalArgumentException::class.java) { WatchfaceCacheRootPolicy.metadataCommand("$root/resources/resource.bin", "description.xml") }
    }

    @Test fun distinguishesEmptyMissingDeniedAndSymlinkRoots() {
        val fixture = fixture()
        assertEquals(listOf("MISSING", "MISSING"), scan(fixture).diagnostics.map { it.code })
        fixture.roots()
        assertEquals(WatchfaceCacheRecords(emptyList(), emptyList(), false), scan(fixture))
        // A deterministic access-check shim models EACCES even when this JVM runs as root.
        val denied = fixture.shell(prefix = "[() { if test \"\$1\" = -r && test \"\$2\" = ${WatchfaceCacheRootPolicy.quote(fixture.mapped(fixture.root).path)}; then return 1; fi; builtin [ \"\$@\"; }\n")
        assertTrue(scan(fixture, denied).diagnostics.any { it.code == "DENIED" && it.path == fixture.root })
        assertTrue(fixture.mapped(fixture.root).delete())
        fixture.link(fixture.root, temporary.root)
        assertTrue(scan(fixture).diagnostics.any { it.code == "LINK" && it.path == fixture.root })
    }

    @Test fun scansUppercaseHiddenAndQuotedNamesWithoutExpandingShellMetacharacters() {
        val fixture = fixture().apply { roots() }
        val marker = File(fixture.directory, "injected")
        val special = fixture.root + "/中'\" \$(touch injected); `touch injected`\n.BIN"
        fixture.file(special, "actual".toByteArray())
        fixture.file(fixture.root + "/.hidden.BIN", "hidden".toByteArray())
        fixture.file(fixture.root + "/other.txt", "text".toByteArray())
        val result = scan(fixture)
        assertEquals(2, result.files.size)
        assertFalse(marker.exists())
        assertEquals("actual", fixture.shell().read(WatchfaceCacheRootPolicy.readBinCommand(special), 100).toString(Charsets.UTF_8))
        assertFalse(marker.exists())
    }

    @Test fun intermediateAndFileLinksAreRejectedForBinXmlAndPreviews() {
        val fixture = fixture().apply { roots() }
        val outside = File(fixture.directory, "outside").apply { mkdirs() }
        File(outside, "secret.bin").writeText("secret")
        fixture.link(fixture.root + "/linked", outside)
        fixture.link(fixture.root + "/link.bin", File(outside, "secret.bin"))
        assertEquals(2, scan(fixture).diagnostics.count { it.code == "LINK" })
        assertTrue(scan(fixture).files.isEmpty())
        for (path in listOf(fixture.root + "/link.bin", fixture.root + "/linked/secret.bin")) {
            val error = assertThrows(WatchfaceCacheException::class.java) {
                fixture.shell().read(WatchfaceCacheRootPolicy.readBinCommand(path), 100)
            }
            assertEquals("LINK", error.code)
        }
        fixture.file(fixture.bin, fixture.binBytes())
        fixture.link(fixture.bin.substringBeforeLast('/') + "/resources", outside)
        val xmlError = assertThrows(WatchfaceCacheException::class.java) {
            fixture.shell().read(WatchfaceCacheRootPolicy.metadataCommand(fixture.bin, "resources/manifest.xml"), 100)
        }
        assertEquals("LINK", xmlError.code)
    }

    @Test fun allowsOnlyTheStorageAliasAndRejectsSymlinksAboveFixedRoot() {
        val fixture = fixture().apply { roots() }
        val original = fixture.storage
        val backing = File(fixture.directory, "backing")
        assertTrue(original.renameTo(backing))
        java.nio.file.Files.createSymbolicLink(original.toPath(), backing.toPath())
        fixture.file(fixture.bin, fixture.binBytes())
        assertEquals(1, scan(fixture).files.size)
        val health = fixture.mapped(fixture.root).parentFile!!.parentFile!!
        val moved = File(fixture.directory, "health")
        assertTrue(health.renameTo(moved))
        java.nio.file.Files.createSymbolicLink(health.toPath(), moved.toPath())
        assertTrue(scan(fixture).diagnostics.any { it.code == "LINK" })
        assertThrows(WatchfaceCacheException::class.java) {
            fixture.shell().read(WatchfaceCacheRootPolicy.readBinCommand(fixture.bin), 1024)
        }
    }

    @Test fun limitDiagnosticsDistinguishSizeDepthAndFileCount() {
        val fixture = fixture().apply { roots() }
        val oversized = fixture.file(fixture.root + "/oversized.bin", ByteArray(0))
        RandomAccessFile(oversized, "rw").use { it.setLength(WatchfaceCacheRootPolicy.MAX_BIN_BYTES + 1) }
        val deep = fixture.root + "/" + (1..9).joinToString("/") { "d$it" }
        fixture.file("$deep/deep.bin", "data".toByteArray())
        val sizeDepth = scan(fixture)
        assertTrue(sizeDepth.truncated)
        assertTrue(sizeDepth.diagnostics.any { it.code == "LIMIT" && it.reason == "size" })
        assertTrue(sizeDepth.diagnostics.any { it.code == "LIMIT" && it.reason == "depth" })
        assertFalse(sizeDepth.files.any { it.path.endsWith("deep.bin") })
        for (index in 0..200) fixture.file(fixture.root + "/$index.bin", "data".toByteArray())
        val limited = scan(fixture)
        assertEquals(200, limited.files.size)
        assertTrue(limited.diagnostics.any { it.code == "LIMIT" && it.reason == "files" })
    }

    @Test fun pinsDescriptorAndChecksRegularityAndSizeBeforePublishing() {
        val fixture = fixture().apply { roots() }
        val path = fixture.root + "/pinned.bin"
        val file = fixture.file(path, "original".toByteArray())
        val outside = File(fixture.directory, "outside.bin").apply { writeText("secret") }
        val swap = "stat() { rm -f ${WatchfaceCacheRootPolicy.quote(file.path)}; ln -s ${WatchfaceCacheRootPolicy.quote(outside.path)} ${WatchfaceCacheRootPolicy.quote(file.path)}; command stat \"\$@\"; }\n"
        assertEquals("original", fixture.shell(prefix = swap).read(WatchfaceCacheRootPolicy.readBinCommand(path), 100).toString(Charsets.UTF_8))
        assertTrue(java.nio.file.Files.isSymbolicLink(file.toPath()))
        val large = fixture.file(fixture.root + "/large.bin", ByteArray(0))
        RandomAccessFile(large, "rw").use { it.setLength(WatchfaceCacheRootPolicy.MAX_BIN_BYTES + 1) }
        assertEquals("LIMIT", assertThrows(WatchfaceCacheException::class.java) {
            fixture.shell().read(WatchfaceCacheRootPolicy.readBinCommand(fixture.root + "/large.bin"), 100)
        }.code)
    }

    @Test fun previewEnumerationIsShallowBoundedAndRefusesLinks() {
        val fixture = fixture().apply { roots() }
        fixture.file(fixture.bin, fixture.binBytes())
        val base = fixture.bin.substringBeforeLast('/') + "/resources/_preview/"
        fixture.file(base + "a.png", fixture.png())
        fixture.file(base + ".hidden.JPG", fixture.jpeg())
        fixture.file(base + "sub/no.png", fixture.png())
        fixture.link(base + "linked.png", fixture.mapped(base + "a.png"))
        val listing = fixture.shell().read(WatchfaceCacheRootPolicy.previewListCommand(fixture.bin), 32768).toString(Charsets.UTF_8)
        assertTrue(listing.contains("a.png\u0000"))
        assertTrue(listing.contains(".hidden.JPG\u0000"))
        assertTrue(listing.contains("!LINK\u0000"))
        assertFalse(listing.contains("sub/no.png"))
        for (index in 0..40) fixture.file(base + "p$index.png", fixture.png())
        val bounded = fixture.shell().read(WatchfaceCacheRootPolicy.previewListCommand(fixture.bin), 32768).toString(Charsets.UTF_8)
        assertTrue(bounded.contains("!LIMIT\u0000"))
        assertEquals(32, bounded.split('\u0000').count { it.startsWith("resources/_preview/") })
    }

    @Test fun entriesDiagnosticsAndProtocolOutputBudgetsReportTheirOwnLimits() {
        val f = fixture().apply { roots() }
        // Stream many names from a fake find; do not create 10,000 files just to test its budget.
        val name = f.root + "/other.txt"
        val entries = f.shell(prefix = "find() { if test \"\$1\" = ${WatchfaceCacheRootPolicy.quote(f.mapped(f.root).path)}; then i=0; while test \"\$i\" -lt 10005; do printf '%s\\0' ${WatchfaceCacheRootPolicy.quote(f.mapped(name).path)}; i=\$((i+1)); done; else command find \"\$@\"; fi; }\n")
        // Missing-entry diagnostics stop earlier, so model existing ordinary non-BIN files.
        f.file(name, byteArrayOf(1))
        val limited = scan(f, entries)
        assertTrue(limited.diagnostics.any { it.code == "LIMIT" && it.reason == "entries" })
        for (index in 0..105) f.link(f.root + "/link$index", f.mapped(name))
        val diagnostics = scan(f)
        assertTrue(diagnostics.diagnostics.any { it.code == "LIMIT" && it.reason == "diagnostics" })
        assertTrue(diagnostics.diagnostics.size <= 101)
        val deniedFind = f.shell(prefix = "find() { printf 'Permission denied\\n' >&2; return 1; }\n")
        assertTrue(scan(f, deniedFind).diagnostics.any { it.code == "DENIED" && it.reason == "enumeration" })
        val escaped = f.shell(prefix = "readlink() { if test \"\$2\" = ${WatchfaceCacheRootPolicy.quote(f.mapped(f.root).path)}; then printf '/elsewhere\\n'; else command readlink \"\$@\"; fi; }\n")
        assertTrue(scan(f, escaped).diagnostics.any { it.code == "ESCAPE" })
    }

    @Test fun rejectsDamagedDuplicateEscapedOrIncompleteProtocols() {
        val path = WatchfaceCacheRootPolicy.roots.first() + "/a.bin"
        val file = "FILE\u0000$path\u00001\u00000\u0000"
        val done = "DONE\u0000\u0000\u0000\u0000"
        for (text in listOf("", file, file + file + done, "FILE\u0000/etc/a.bin\u00001\u00000\u0000" + done,
            "UNKNOWN\u0000$path\u00000\u00000\u0000" + done, done + file)) {
            assertThrows(WatchfaceCacheException::class.java) { WatchfaceCacheScanProtocol.parse(text.toByteArray()) }
        }
        assertEquals(1, WatchfaceCacheScanProtocol.parse((file + done).toByteArray()).files.size)
        assertThrows(WatchfaceCacheException::class.java) { WatchfaceCacheScanProtocol.parse(byteArrayOf(-1, 0)) }
        assertThrows(WatchfaceCacheException::class.java) {
            WatchfaceCacheScanProtocol.parse(ByteArray(WatchfaceCacheRootPolicy.MAX_SCAN_BYTES.toInt() + 1))
        }
    }
}
