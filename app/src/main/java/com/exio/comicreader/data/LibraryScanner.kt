package com.exio.comicreader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 漫画库目录扫描器：输入一个目录树 Uri，输出其中所有 .cbz/.zip 文件。
 * 只负责遍历文件系统，不接触数据库——diff 同步逻辑在 ComicRepository。
 */
class LibraryScanner(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    data class ScannedFile(
        val uri: String,
        val fileKey: String,
        val displayName: String,
    )

    /** 目录本身打不开（权限被撤销/目录被删）时抛出，调用方按目录粒度处理 */
    class FolderAccessException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /**
     * 递归列出 treeUri 下所有漫画压缩包；跳过 . 开头的隐藏目录和文件。
     *
     * 为什么不用 DocumentFile.listFiles() 递归？SAF 底层是 ContentProvider，
     * DocumentFile 的每个属性访问（.name / .isDirectory）都是一次跨进程查询，
     * 一个文件要 2~3 次 IPC，几百个文件就要慢上数秒。直接用 DocumentsContract
     * query 一次 cursor 就能拿到整个目录所有孩子的名字和类型。
     */
    suspend fun scanFolder(treeUri: Uri): List<ScannedFile> = withContext(Dispatchers.IO) {
        val found = mutableListOf<ScannedFile>()
        // (目录 docId, 深度) 队列做迭代式递归：不爆栈、好限深、好取消
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(DocumentsContract.getTreeDocumentId(treeUri) to 0)

        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (docId, depth) = queue.removeFirst()

            val children = try {
                queryChildren(treeUri, docId)
            } catch (e: Exception) {
                if (depth == 0) {
                    // 根目录都打不开 = 整个目录失效。必须抛错而不是返回空列表，
                    // 否则调用方会把该目录的所有书误标为"已失效"
                    throw FolderAccessException("无法访问该目录", e)
                }
                continue // 个别子目录查询失败：跳过，不影响其余部分
            }

            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.isDirectory) {
                    if (depth < MAX_DEPTH) queue.add(child.docId to depth + 1)
                } else if (COMIC_EXT.matches(child.name)) {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.docId)
                    found.add(
                        ScannedFile(
                            uri = fileUri.toString(),
                            fileKey = ComicIdentity.fileKey(treeUri.authority, child.docId),
                            displayName = child.name,
                        )
                    )
                }
            }
        }
        found
    }

    private data class Child(val docId: String, val name: String, val isDirectory: Boolean)

    /** 一次跨进程查询拿到一个目录的全部孩子 */
    private fun queryChildren(treeUri: Uri, parentDocId: String): List<Child> {
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val result = mutableListOf<Child>()
        val cursor = resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        ) ?: throw IllegalStateException("provider 返回了 null cursor")
        cursor.use {
            while (it.moveToNext()) {
                val docId = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val mime = it.getString(2)
                result.add(
                    Child(docId, name, mime == DocumentsContract.Document.MIME_TYPE_DIR)
                )
            }
        }
        return result
    }

    companion object {
        private val COMIC_EXT = Regex(".*\\.(cbz|zip)$", RegexOption.IGNORE_CASE)
        private const val MAX_DEPTH = 15 // 防御环形/超深目录结构
    }
}
