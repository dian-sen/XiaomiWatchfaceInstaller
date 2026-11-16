package dev.xiaom.watchfaceinstaller

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkingCopyPathTest {
    @get:Rule val temp = TemporaryFolder()
    private fun bin(root: File, name: String = "source.bin", id: String = "120917345196") = File(root, name).apply {
        parentFile.mkdirs()
        writeBytes(ByteArray(512).also {
            byteArrayOf(0x5a, 0xa5.toByte(), 0x34, 0x12).copyInto(it)
            id.toByteArray().copyInto(it, 0x28)
        })
    }
    private fun alias(target: File, name: String) = File(temp.root, name).also {
        Files.createSymbolicLink(it.toPath(), target.toPath())
    }
    @Test fun rootAliasAndCanonicalInputBothReturnCanonicalFile() {
        val root = temp.newFolder("real")
        val source = bin(root, "nested/source.bin")
        val linkedRoot = alias(root, "alias")
        assertEquals(source.canonicalFile, WorkingCopyResult.privateFile(linkedRoot, File(linkedRoot, "nested/source.bin").path))
        assertEquals(source.canonicalFile, WorkingCopyResult.privateFile(linkedRoot, source.path))
    }
    @Test fun aliasInRootAncestorIsAllowed() {
        val real = temp.newFolder("user0")
        val root = File(real, "app/files").apply { mkdirs() }
        val source = bin(root)
        val aliasRoot = File(alias(real, "data"), "app/files")
        assertEquals(source.canonicalFile, WorkingCopyResult.privateFile(aliasRoot, File(aliasRoot, source.name).path))
    }
    @Test fun acceptValidatesAliasedOriginalAndCanonicalReturn() {
        val root = temp.newFolder("real")
        val source = bin(root)
        val linkedRoot = alias(root, "alias")
        val original = WatchfaceParser.inspect(File(linkedRoot, source.name))
        val copy = bin(root, "copy.bin", "120917345197")
        val accepted = WorkingCopyResult.accept(linkedRoot, copy.path, original)
        assertEquals(copy.canonicalFile, accepted.file)
        assertEquals("120917345197", accepted.id)
        assertThrows(IllegalArgumentException::class.java) { WorkingCopyResult.accept(linkedRoot, source.path, original) }
    }
    @Test fun acceptValidatesCanonicalOriginalAndAliasedReturn() {
        val root = temp.newFolder("real")
        val source = bin(root)
        val linkedRoot = alias(root, "alias")
        val copy = bin(root, "copy.bin", "120917345197")
        assertEquals(copy.canonicalFile, WorkingCopyResult.accept(linkedRoot, File(linkedRoot, copy.name).path, WatchfaceParser.inspect(source)).file)
    }
    @Test fun internalDirectorySymlinkRejectedEvenWhenTargetIsInsideRoot() {
        val root = temp.newFolder("real")
        val source = bin(root, "nested/source.bin")
        Files.createSymbolicLink(File(root, "shortcut").toPath(), source.parentFile.toPath())
        val linkedRoot = alias(root, "alias")
        for (r in listOf(root, linkedRoot)) {
            assertThrows(IllegalArgumentException::class.java) { WorkingCopyResult.privateFile(r, File(r, "shortcut/source.bin").path) }
        }
    }
    @Test fun internalFileSymlinkRejectedUnderAliasRoot() {
        val root = temp.newFolder("real")
        val source = bin(root)
        Files.createSymbolicLink(File(root, "link.bin").toPath(), source.toPath())
        val linkedRoot = alias(root, "alias")
        assertThrows(IllegalArgumentException::class.java) { WorkingCopyResult.privateFile(linkedRoot, File(linkedRoot, "link.bin").path) }
    }
    @Test fun traversalOutsideRootPrefixSiblingAndRelativePathsRejected() {
        val root = temp.newFolder("real")
        bin(root)
        File(root, "nested").mkdir()
        val sibling = temp.newFolder("real-other")
        val outside = bin(sibling)
        for (path in listOf(File(root, "nested/../source.bin").path, outside.path, "source.bin", root.path)) {
            assertThrows(IllegalArgumentException::class.java) { WorkingCopyResult.privateFile(root, path) }
        }
        Files.createSymbolicLink(File(root, "escape").toPath(), sibling.toPath())
        assertThrows(IllegalArgumentException::class.java) { WorkingCopyResult.privateFile(root, File(root, "escape/source.bin").path) }
    }
    @Test fun acceptRejectsSymlinkInOriginalOrReturnedDirectory() {
        val root = temp.newFolder("real")
        val source = bin(root, "nested/source.bin")
        val copy = bin(root, "nested/copy.bin", "120917345197")
        Files.createSymbolicLink(File(root, "link").toPath(), source.parentFile.toPath())
        val original = WatchfaceParser.inspect(source)
        assertThrows(IllegalArgumentException::class.java) { WorkingCopyResult.accept(root, copy.path, original.copy(file = File(root, "link/source.bin"))) }
        assertThrows(IllegalArgumentException::class.java) { WorkingCopyResult.accept(root, File(root, "link/copy.bin").path, original) }
    }
    @Test fun editorAllowsRootAliasButRejectsDirectoryLink() {
        val root = temp.newFolder("real")
        val source = bin(root, "nested/source.bin")
        val linkedRoot = alias(root, "alias")
        val result = WatchfaceIdEditor.createCopy(linkedRoot, File(linkedRoot, "nested/source.bin"), "120917345197")
        assertEquals(result.file.canonicalFile, result.file)
        assertEquals("120917345196", WatchfaceParser.inspect(source).id)
        Files.createSymbolicLink(File(root, "link").toPath(), source.parentFile.toPath())
        assertThrows(IllegalArgumentException::class.java) { WatchfaceIdEditor.createCopy(root, File(root, "link/source.bin"), "120917345198") }
    }
}
