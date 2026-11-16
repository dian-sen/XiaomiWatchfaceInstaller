package dev.xiaom.watchfaceinstaller

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException

/** A user-picked ordinary SAF directory; no broad storage permissions or persisted grants. */
class SafExportStore(private val resolver: ContentResolver, tree: Uri) : ExportStore {
    private val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    private val knownIds = mutableSetOf<String>()
    private val owned = mutableSetOf<String>()
    init {
        val id = DocumentsContract.getTreeDocumentId(tree)
        require(!Regex("(?i)(^|[:/])Android/(data|obb)(/|$)").containsMatchIn(id)) { "请选择普通目录，不能导出到 Android/data 或 Android/obb" }
    }
    override fun names(): Set<String> {
        val result = mutableSetOf<String>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            var count = 0
            while (cursor.moveToNext()) {
                require(++count <= 10000) { "目标目录条目过多，请换一个目录" }
                knownIds += cursor.getString(0)
                result += cursor.getString(1)
            }
        } ?: throw IOException("无法列出目标目录；为避免覆盖已停止")
        return result
    }
    override fun create(name: String, mime: String): String {
        require(names().none { it.equals(name, true) }) { "目录已出现同名文件，拒绝覆盖" }
        val uri = DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw IOException("无法新建 $name")
        require(DocumentsContract.getDocumentId(uri) !in knownIds) { "提供程序返回已有文件，拒绝写入或清理" }
        val handle = uri.toString()
        owned += handle
        knownIds += DocumentsContract.getDocumentId(uri)
        return handle
    }
    override fun name(handle: String): String {
        resolver.query(Uri.parse(handle), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        throw IOException("无法核实新文件名称")
    }
    override fun write(handle: String, file: File) {
        require(handle in owned)
        resolver.openOutputStream(Uri.parse(handle), "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw IOException("无法写入新文件")
    }
    override fun delete(handle: String): Boolean {
        if (handle !in owned) return false
        return DocumentsContract.deleteDocument(resolver, Uri.parse(handle)).also { if (it) owned.remove(handle) }
    }
}
