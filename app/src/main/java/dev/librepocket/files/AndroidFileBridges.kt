package dev.librepocket.files

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * S1-A 已授權 SAF 樹的 Android 落地（main 源集，D05「SAF 直行」）。
 *
 * - [treeUris]：正規化樹前綴 → 使用者已持久授權的 tree Uri
 *  （`takePersistableUriPermission` 由後續切面的 grant store 持有；
 *   本類只消費，不申請、不新增任何權限）。
 * - 路徑 ↔ 文件映射：以前綴內剩餘段逐層走
 *   `buildChildDocumentsUriUsingTree` 查 `DISPLAY_NAME`（零新依賴，
 *   純 framework API）。
 * - 寫入：已存在文件截斷覆寫（`"wt"`）；不存在則逐層建目錄後
 *   `createDocument`；樹根本體不可覆寫。
 */
class AndroidSafFileBridge(
    private val resolver: ContentResolver,
    private val treeUris: Map<String, Uri>,
) : SafFileBridge {

    override fun exists(absolutePath: String): Boolean =
        runCatching { documentUri(absolutePath) != null }.getOrDefault(false)

    override fun read(absolutePath: String): ByteArray {
        val uri = documentUri(absolutePath) ?: throw FileNotFoundException(absolutePath)
        return resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException(absolutePath)
    }

    override fun write(absolutePath: String, bytes: ByteArray) {
        val existing = documentUri(absolutePath)
        if (existing != null) {
            resolver.openOutputStream(existing, "wt")?.use { it.write(bytes) }
                ?: throw FileNotFoundException(absolutePath)
            return
        }
        val (prefix, treeUri) = treeFor(absolutePath)
            ?: throw FileNotFoundException("SAF grant missing: $absolutePath")
        val segments = relativeSegments(absolutePath, prefix)
        if (segments.isEmpty()) throw FileNotFoundException("cannot overwrite SAF tree root: $absolutePath")
        var parentId = DocumentsContract.getTreeDocumentId(treeUri)
        for (seg in segments.dropLast(1)) {
            parentId = findChild(treeUri, parentId, seg)
                ?: createDir(treeUri, parentId, seg)
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val created = DocumentsContract.createDocument(
            resolver,
            parentUri,
            "application/octet-stream",
            segments.last(),
        ) ?: throw FileNotFoundException("cannot create document: $absolutePath")
        resolver.openOutputStream(created, "wt")?.use { it.write(bytes) }
            ?: throw FileNotFoundException(absolutePath)
    }

    override fun list(absolutePrefix: String): List<String> {
        val (_, treeUri) = treeFor(absolutePrefix) ?: return emptyList()
        val start = documentUri(absolutePrefix) ?: return emptyList()
        val out = mutableListOf<String>()
        walk(treeUri, DocumentsContract.getDocumentId(start), FileScope.normalize(absolutePrefix), out)
        return out.sorted()
    }

    private fun walk(treeUri: Uri, docId: String, absPath: String, out: MutableList<String>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val childId = cursor.getString(idCol)
                val childAbs = "$absPath/${cursor.getString(nameCol)}"
                if (cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(treeUri, childId, childAbs, out)
                } else {
                    out.add(childAbs)
                }
            }
        }
    }

    private fun treeFor(path: String): Pair<String, Uri>? {
        val norm = FileScope.normalize(path)
        return treeUris.entries
            .map { (prefix, uri) -> FileScope.normalize(prefix) to uri }
            .filter { (prefix, _) -> norm == prefix || norm.startsWith("$prefix/") }
            .maxByOrNull { (prefix, _) -> prefix.length }
    }

    private fun relativeSegments(path: String, prefix: String): List<String> {
        val norm = FileScope.normalize(path)
        if (norm == prefix) return emptyList()
        return norm.removePrefix("$prefix/").split("/")
    }

    private fun documentUri(path: String): Uri? {
        val (prefix, treeUri) = treeFor(path) ?: return null
        var docId = DocumentsContract.getTreeDocumentId(treeUri)
        for (seg in relativeSegments(path, prefix)) {
            docId = findChild(treeUri, docId, seg) ?: return null
        }
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    }

    private fun findChild(treeUri: Uri, parentDocId: String, displayName: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        return query(children)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            var found: String? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) == displayName) {
                    found = cursor.getString(idCol)
                    break
                }
            }
            found
        }
    }

    private fun createDir(treeUri: Uri, parentDocId: String, name: String): String {
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val created = DocumentsContract.createDocument(
            resolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name,
        ) ?: throw FileNotFoundException("cannot create directory: $name")
        return DocumentsContract.getDocumentId(created)
    }

    private fun query(uri: Uri): Cursor? =
        resolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )
}

/** `file.attach` 的 ContentResolver 來源（Photo Picker / SAF 回傳 Uri 包裝）。 */
class UriAttachmentSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : AttachmentSource {

    override fun displayName(): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    override fun mimeType(): String? = resolver.getType(uri)

    override fun sizeBytes(): Long? =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0).takeIf { it >= 0 } else null
        }

    override fun openInputStream(): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
}
