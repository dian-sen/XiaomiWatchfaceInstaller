package dev.xiaom.watchfaceinstaller

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** All shell commands run via ordinary host sh; storage is remapped into a temp folder. */
class RootPolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val storage get() = File(temporary.root, "storage").apply { mkdirs() }
    private fun mapped(path: String) = File(path.replace("/storage/emulated/0", storage.path))
    private fun shell() = RootShell(5000) { command ->
        ProcessBuilder("sh", "-c", command.replace("/storage/emulated/0", storage.path)).start()
    }
    private fun makeRoots() = RootPolicy.roots.forEach { mapped(it).mkdirs() }

    @Test fun acceptsOnlyWhitelistedBinDescendants() {
        RootPolicy.roots.forEach { RootPolicy.validatePath("$it/sub/A.BIN") }
    }

    @Test fun rejectsTraversalPrefixConfusionAndInvalidPaths() {
        val root = RootPolicy.roots.first()
        for (path in listOf("/etc/a.bin", "$root-other/a.bin", root, "$root/../a.bin", "$root/./a.bin",
            "$root//a.bin", "$root/a.txt", "$root/a.bin\u0000", "$root/" + "a".repeat(4096) + ".bin")) {
            assertThrows("$path must fail", IllegalArgumentException::class.java) { RootPolicy.readCommand(path) }
        }
    }

    @Test fun quotesAllShellMetacharactersLiterally() {
        val marker = File(temporary.root, "injected")
        val value = "'\" ; touch ${marker.path}; \$(touch ${marker.path}) `touch ${marker.path}` \\ \n中"
        assertEquals(value, shell().text("printf '%s' ${RootPolicy.quote(value)}", 8192))
        assertFalse(marker.exists())
        assertThrows(IllegalArgumentException::class.java) { RootPolicy.quote("\u0000") }
    }

    @Test fun emptyExistingRootsProduceSuccessfulEmptyScan() {
        makeRoots()
        assertEquals(RootScan(emptyList(), emptyList(), false), RootCacheSource(shell()).scan())
    }

    @Test fun missingRootsAreReported() {
        val text = shell().text(RootPolicy.scanCommand(), RootPolicy.MAX_OUTPUT)
        assertEquals(2, text.split('\u0000').count { it == "MISSING" })
    }

    @Test fun readsSpecialQuotedFilenameWithoutShellExpansion() {
        makeRoots()
        val path = RootPolicy.roots.first() + "/中'\" \$(printf bad); `printf bad`\n.BIN"
        mapped(path).writeText("actual bytes")
        assertEquals("actual bytes", shell().text(RootPolicy.readCommand(path), 1024))
    }

    @Test fun rejectsFileAndIntermediateDirectorySymlinks() {
        makeRoots()
        val root = RootPolicy.roots.first()
        val outside = temporary.newFile("outside.bin").apply { writeText("secret") }
        Files.createSymbolicLink(mapped("$root/link.bin").toPath(), outside.toPath())
        Files.createSymbolicLink(mapped("$root/linked").toPath(), temporary.root.toPath())
        for (path in listOf("$root/link.bin", "$root/linked/outside.bin")) {
            assertThrows(IOException::class.java) { shell().text(RootPolicy.readCommand(path), 1024) }
        }
        val scan = shell().text(RootPolicy.scanCommand(), RootPolicy.MAX_OUTPUT)
        assertEquals(2, scan.split('\u0000').count { it == "LINK" })
        assertFalse(scan.split('\u0000').contains("FILE"))
    }

    @Test fun rejectsSymlinkedRoot() {
        makeRoots()
        val root = mapped(RootPolicy.roots.first())
        assertTrue(root.delete())
        Files.createSymbolicLink(root.toPath(), temporary.root.toPath())
        assertThrows(IOException::class.java) { shell().text(RootPolicy.readCommand(RootPolicy.roots.first() + "/a.bin"), 1024) }
        assertTrue(shell().text(RootPolicy.scanCommand(), RootPolicy.MAX_OUTPUT).split('\u0000').contains("LINK"))
    }

    @Test fun scansOnlyBinsIncludingHiddenAndUppercaseFiles() {
        makeRoots()
        val root = RootPolicy.roots.first()
        for (name in listOf("a.bin", ".hidden.BIN", "other.txt")) mapped("$root/$name").writeText("data")
        val fields = shell().text(RootPolicy.scanCommand(), RootPolicy.MAX_OUTPUT).split('\u0000')
        assertEquals(2, fields.count { it == "FILE" })
        assertFalse(fields.any { it.endsWith("other.txt") })
    }

    @Test fun descriptorChecksUseParentShellProcPathNotChildSelf() {
        val command = RootPolicy.readCommand(RootPolicy.roots.first() + "/a.bin")
        val parentFd = "/proc/\$\$/fd/3"
        assertFalse("child commands may close FD 3 on exec", command.contains("/proc/self/fd/3"))
        assertEquals("all three descriptor checks must use the owning shell PID", 3,
            command.windowed(parentFd.length).count { it == parentFd })
        assertTrue(command.contains("readlink -f $parentFd"))
        assertTrue(command.contains("[ -f $parentFd ]"))
        assertTrue(command.contains("stat -L -c %s $parentFd"))
        assertTrue(command.contains("exec cat <&3"))
    }

    @Test fun pinsDescriptorInsteadOfReopeningPathAfterStat() {
        makeRoots()
        val path = RootPolicy.roots.first() + "/pinned.bin"
        val local = mapped(path).apply { writeText("original") }
        val outside = temporary.newFile("secret.bin").apply { writeText("secret") }
        val swap = "stat() { rm -f ${RootPolicy.quote(local.path)}; ln -s ${RootPolicy.quote(outside.path)} ${RootPolicy.quote(local.path)}; command stat \"\$@\"; };\n"
        assertEquals("original", shell().text(swap + RootPolicy.readCommand(path), 1024))
        assertTrue(Files.isSymbolicLink(local.toPath()))
    }

    @Test fun rejectsOversizedFileBeforeCopy() {
        makeRoots()
        val path = RootPolicy.roots.first() + "/large.bin"
        java.io.RandomAccessFile(mapped(path), "rw").use { it.setLength(WatchfaceFiles.MAX_BYTES + 1) }
        assertThrows(IOException::class.java) { shell().text(RootPolicy.readCommand(path), 1024) }
        assertTrue(shell().text(RootPolicy.scanCommand(), RootPolicy.MAX_OUTPUT).split('\u0000').contains("LIMIT"))
    }
    @Test fun xmlWhitelistDoesNotWidenBinPolicy() {
        val bin = RootPolicy.roots.first() + "/120917345196/resource.bin"
        for (relative in WatchfaceXml.paths) {
            assertEquals(bin.substringBeforeLast('/') + "/" + relative, RootPolicy.metadataPath(bin, relative))
            assertThrows(IllegalArgumentException::class.java) { RootPolicy.readCommand(RootPolicy.metadataPath(bin, relative)) }
        }
        for (relative in listOf("../description.xml", "arbitrary.xml", "resources/../description.xml", "/etc/passwd", "description.xml\n")) {
            assertThrows(IllegalArgumentException::class.java) { RootPolicy.metadataCommand(bin, relative) }
        }
        for (bad in listOf(bin.replace("resource.bin", "other.bin"), bin.replace("/120917345196/", "/120917345196/resources/"), "/etc/resource.bin")) {
            assertThrows(IllegalArgumentException::class.java) { RootPolicy.metadataCommand(bad, "description.xml") }
        }
    }

    @Test fun xmlReadPinsFdLimitsOutputAndRejectsLinks() {
        makeRoots()
        val bin = RootPolicy.roots.first() + "/resource.bin"
        val xml = mapped(RootPolicy.metadataPath(bin, "description.xml")).apply { writeText("<watch/>") }
        val command = RootPolicy.metadataCommand(bin, "description.xml")
        assertTrue(command.contains("head -c 524289 <&3"))
        assertEquals("<watch/>", shell().text(command, WatchfaceXml.MAX_BYTES))
        val outside = temporary.newFile("outside.xml").apply { writeText("secret") }
        xml.delete(); Files.createSymbolicLink(xml.toPath(), outside.toPath())
        assertThrows(IOException::class.java) { shell().text(command, WatchfaceXml.MAX_BYTES) }
        xml.delete()
        java.io.RandomAccessFile(xml, "rw").use { it.setLength(WatchfaceXml.MAX_BYTES + 1L) }
        val error = assertThrows(IOException::class.java) { shell().text(command, WatchfaceXml.MAX_BYTES) }
        assertTrue(error.message!!.contains("512 KiB"))
    }

    @Test fun xmlIntermediateLinkAndDescriptorPathSwapAreSafe() {
        makeRoots()
        val bin = RootPolicy.roots.first() + "/resource.bin"
        val directory = mapped(bin).parentFile!!
        Files.createSymbolicLink(File(directory, "resources").toPath(), temporary.root.toPath())
        temporary.newFile("manifest.xml").writeText("secret")
        assertThrows(IOException::class.java) { shell().text(RootPolicy.metadataCommand(bin, "resources/manifest.xml"), WatchfaceXml.MAX_BYTES) }
        val xml = File(directory, "description.xml").apply { writeText("original") }
        val outside = temporary.newFile("outside-metadata.xml").apply { writeText("secret") }
        val swap = "stat() { rm -f ${RootPolicy.quote(xml.path)}; ln -s ${RootPolicy.quote(outside.path)} ${RootPolicy.quote(xml.path)}; command stat \"\$@\"; };\n"
        assertEquals("original", shell().text(swap + RootPolicy.metadataCommand(bin, "description.xml"), WatchfaceXml.MAX_BYTES))
    }

    @Test fun xmlQuotedParentDirectoryCannotInjectCommands() {
        makeRoots()
        val path = RootPolicy.roots.first() + "/中'\" \$(printf bad); `printf bad`\n/120917345196/resource.bin"
        val xml = mapped(RootPolicy.metadataPath(path, "description.xml"))
        xml.parentFile!!.mkdirs(); xml.writeText("<watch/>")
        assertEquals("<watch/>", shell().text(RootPolicy.metadataCommand(path, "description.xml"), WatchfaceXml.MAX_BYTES))
    }

}
