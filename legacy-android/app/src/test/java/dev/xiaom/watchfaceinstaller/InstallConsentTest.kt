package dev.xiaom.watchfaceinstaller

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InstallConsentTest {
    @get:Rule val temp = TemporaryFolder()
    private fun bin(root: File, name: String = "private.bin") = File(root, name).apply {
        writeBytes(ByteArray(512).also {
            byteArrayOf(0x5a, 0xa5.toByte(), 0x34, 0x12).copyInto(it)
            "120917345196".toByteArray().copyInto(it, 0x28)
        })
    }
    @Test fun capturesPrivateFileIdentityAndVerifiesUnchanged() {
        val root = temp.newFolder(); val file = bin(root)
        val consent = InstallConsent.capture(root, file)
        assertEquals("120917345196", consent.id)
        assertEquals(512L, consent.size)
        assertEquals(file.name, consent.name)
        assertEquals(WatchfaceIdEditor.sha256(file), consent.sha256)
        consent.verify(root, file)
    }
    @Test fun changedPayloadSameSizeAndIdIsRejected() {
        val root = temp.newFolder(); val file = bin(root)
        val consent = InstallConsent.capture(root, file)
        file.writeBytes(file.readBytes().also { it[300] = 9 })
        assertThrows(IllegalArgumentException::class.java) { consent.verify(root, file) }
    }
    @Test fun changingIdOrSizeOrPathRequiresNewConsent() {
        val root = temp.newFolder(); val file = bin(root)
        val consent = InstallConsent.capture(root, file)
        assertThrows(IllegalArgumentException::class.java) { consent.verify(root, bin(root, "other.bin")) }
        file.writeBytes(file.readBytes().also { it[0x28] = '2'.code.toByte() })
        assertThrows(IllegalArgumentException::class.java) { consent.verify(root, file) }
        bin(root); file.appendBytes(byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { consent.verify(root, file) }
    }
    @Test fun rejectsOutsideOriginalCacheAndSymlink() {
        val root = temp.newFolder(); val original = bin(temp.newFolder())
        assertThrows(IllegalArgumentException::class.java) { InstallConsent.capture(root, original) }
        val source = bin(root); val link = File(root, "alias.bin")
        Files.createSymbolicLink(link.toPath(), source.toPath())
        assertThrows(IllegalArgumentException::class.java) { InstallConsent.capture(root, link) }
    }
    @Test fun rejectsTruncatedAndUnknownFormats() {
        val root = temp.newFolder(); val file = bin(root)
        file.writeBytes(ByteArray(100))
        assertThrows(IllegalArgumentException::class.java) { InstallConsent.capture(root, file) }
        file.writeBytes(ByteArray(512))
        assertThrows(IllegalArgumentException::class.java) { InstallConsent.capture(root, file) }
    }
}
