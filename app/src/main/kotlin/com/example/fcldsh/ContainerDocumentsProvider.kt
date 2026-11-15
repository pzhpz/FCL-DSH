package com.example.fcldsh

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/**
 * 把沙箱容器暴露进**系统文件选择器**（SAF），像 Termux / FCL / CodeAssist 那样。
 *
 * ## 能做到什么、做不到什么（先看这段，别走回头路）
 *
 * **做不到**：把容器**真的挂到 `/` 下面**让全系统按路径访问。
 * - 没有 root，`mount` 是不可能的；
 * - 就算用 proot 伪出一个 `/`，那也只是**该进程自己的**路径空间
 *   （FCL 启动的 JVM 看到的 `/` 是 proot 造的），别的应用、系统文件管理器
 *   根本看不到，它不进任何全局命名空间。
 *
 * **能做到**：在系统文件选择器（DocumentsUI）的根列表里出现一项，
 * 点进去就是容器里的文件。截图里 CodeAssist 的「App files」、FCL、
 * ZeroTermux 的「可用空间: 32.89 GB」全是同一个机制 ——
 * **DocumentsProvider**（SAF）。它们也不是挂载，只是各写了一个 provider。
 *
 * 关键点：DocumentsProvider 跑在**本应用自己的进程/UID** 里，读的是
 * 私有目录（`/data/user/0/<包名>/files`），它把打开好的
 * `ParcelFileDescriptor` 交给调用方。跨应用读文件靠的是**fd 授权**，
 * 不是路径权限，所以不需要任何存储权限，也不需要 root。
 *
 * ## 暴露了什么
 *
 * 一个根 `FCL-DSH 容器`，下列两个子目录（顶层固定，其他一律不给）：
 * - `home` → `filesDir/home`：dsh 的 HOME、`.dsh-<版本>`、插件、会话、`.env`
 * - `usr`  → `filesDir/usr` ：Termux 风格的 prefix（bin / lib / etc / node_modules）
 *
 * 顶层白名单是硬编码的：`cacheDir`、`code_cache`、`files/` 下的其他东西
 * 都不会因为 id 猜得出来而外泄（[fileFor] 只放行 `home` / `usr` 两棵树）。
 *
 * ## 为什么 manifest 里要 `exported="true"` + `MANAGE_DOCUMENTS`
 *
 * DocumentsUI 是**独立进程**，只有它（持有签名级 `MANAGE_DOCUMENTS`）能调过来。
 * 所以「导出」不等于「谁都能读」：普通第三方应用没有该权限，调不动本 provider；
 * 唯一入口是用户在系统选择器里主动点选，之后拿到的只是被授权的那个 URI。
 *
 * ## 性能注意
 *
 * `usr` 下有整棵 `node_modules`（几万个小文件）。这里只做**逐层列举**，
 * 绝不预扫整棵树；唯一的递归是 [querySearchDocuments]，带深度与条数上限，
 * 且不跟随符号链接。
 */
class ContainerDocumentsProvider : DocumentsProvider() {

    private companion object {
        /** 与 manifest 里的 `${applicationId}.documents` 对应。 */
        const val AUTHORITY_SUFFIX = ".documents"

        /** 唯一根的 id，同时也是根的 documentId。 */
        const val ROOT_ID = "container"

        /** documentId 的分隔符：`container/home/.dsh-1.2.0`。 */
        const val SEP = "/"

        /** 系统选择器里显示的根标题。 */
        const val ROOT_TITLE = "FCL-DSH 容器"

        /** 顶层只放行这两棵子树（见 [fileFor]）。 */
        val TOP_LEVEL = listOf("home", "usr")

        /** 目录里最多列多少项，防止误点进超大目录把系统选择器拖死。 */
        const val MAX_CHILDREN = 2000

        /** 搜索的深度/结果上限（`node_modules` 那种树只能浅尝）。 */
        const val MAX_SEARCH_DEPTH = 6
        const val MAX_SEARCH_RESULTS = 200

        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_AVAILABLE_BYTES,
        )

        val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SUMMARY,
        )

        /** provider 的 authority，与 manifest 占位符保持一致。 */
        fun authority(context: Context): String = context.packageName + AUTHORITY_SUFFIX

        /** 这个根对应的 `content://` 根集合 Uri（给界面做「打开容器」入口用）。 */
        fun rootsUri(context: Context): Uri = DocumentsContract.buildRootsUri(authority(context))

        /** 容器根目录对应的文档 Uri（给界面做「打开容器」入口用）。 */
        fun rootDocumentUri(context: Context): Uri =
            DocumentsContract.buildDocumentUri(authority(context), ROOT_ID)
    }

    /** 私有目录根（`/data/user/0/<包名>/files`）。 */
    private val baseDir: File
        get() = requireNotNull(context).filesDir

    override fun onCreate(): Boolean = true

    // ────────────────────────────── 根 ──────────────────────────────

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: ROOT_COLUMNS
        val cursor = MatrixCursor(cols)
        val values = arrayOfNulls<Any?>(cols.size)
        for (i in cols.indices) {
            values[i] = when (cols[i]) {
                Root.COLUMN_ROOT_ID -> ROOT_ID
                Root.COLUMN_DOCUMENT_ID -> ROOT_ID
                Root.COLUMN_TITLE -> ROOT_TITLE
                Root.COLUMN_SUMMARY -> "沙箱：home / usr"
                Root.COLUMN_ICON -> R.mipmap.ic_launcher
                Root.COLUMN_AVAILABLE_BYTES -> availableBytes()
                Root.COLUMN_FLAGS ->
                    // 不给 FLAG_ADVANCED：给了会被折叠进「更多」里，用户以为没生效。
                    Root.FLAG_LOCAL_ONLY or
                        Root.FLAG_SUPPORTS_CREATE or
                        Root.FLAG_SUPPORTS_IS_CHILD
                else -> null
            }
        }
        cursor.addRow(values)
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cols = projection ?: DOC_COLUMNS
        val cursor = MatrixCursor(cols)
        cursor.addRow(documentValues(documentId, cols))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cols = projection ?: DOC_COLUMNS
        val cursor = MatrixCursor(cols)

        if (parentDocumentId == ROOT_ID) {
            // 顶层固定两项：home / usr（首次调用时就建好，否则点进去是空的）
            for (name in TOP_LEVEL) {
                val dir = File(baseDir, name)
                runCatching { dir.mkdirs() }
                cursor.addRow(documentValues(docIdOf(dir), cols))
            }
            return cursor
        }

        val parent = fileFor(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("不是目录：$parentDocumentId")

        val kids = parent.listFiles() ?: return cursor
        // 目录在前、同名大小写无关排序，接近普通文件管理器的观感
        val sorted = kids.sortedWith(
            compareBy<File> { if (it.isDirectory) 0 else 1 }
                .thenBy { it.name.lowercase() },
        )
        for (child in sorted.take(MAX_CHILDREN)) {
            cursor.addRow(documentValues(docIdOf(child), cols))
        }
        return cursor
    }

    /**
     * 选择器里的搜索框。
     *
     * 只从**当前目录**往下浅搜（深度 [MAX_SEARCH_DEPTH]），**不跟随符号链接**
     * —— `usr` 下整棵 `node_modules` 递归到底会让 DocumentsUI 卡住甚至 ANR。
     */
    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?,
    ): Cursor {
        val cols = projection ?: DOC_COLUMNS
        val cursor = MatrixCursor(cols)
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return cursor

        val hits = ArrayList<File>()
        for (name in TOP_LEVEL) {
            searchInto(File(baseDir, name), needle, hits, 0)
            if (hits.size >= MAX_SEARCH_RESULTS) break
        }
        for (f in hits) cursor.addRow(documentValues(docIdOf(f), cols))
        return cursor
    }

    // ────────────────────────────── 读写 ──────────────────────────────

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = fileFor(documentId)
        if (file.isDirectory) throw FileNotFoundException("不能以文件方式打开目录：$documentId")
        // 写模式下先确保文件存在（"w" 不创建，"rwt" 才会，统一在这里建）
        if (mode.contains('w') || mode.contains('a')) {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                if (!file.createNewFile()) throw FileNotFoundException("无法创建：$documentId")
            }
        }
        signal?.throwIfCanceled()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        // 顶层只允许 home / usr 两项，不给新建
        if (parentDocumentId == ROOT_ID) throw FileNotFoundException("容器根目录下不能新建")
        val parent = fileFor(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("不是目录：$parentDocumentId")

        val name = sanitizeName(displayName)
        val isDir = mimeType == Document.MIME_TYPE_DIR
        val target = uniqueChild(parent, name, isDir)
        val ok = if (isDir) target.mkdirs() else {
            target.parentFile?.mkdirs()
            runCatching { target.createNewFile() }.getOrDefault(false)
        }
        if (!ok) throw FileNotFoundException("创建失败：${target.name}")

        notifyChildren(parent)
        return docIdOf(target)
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == ROOT_ID) throw FileNotFoundException("不能删除容器根目录")
        val file = fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException("不存在：$documentId")
        if (!deleteTree(file)) throw FileNotFoundException("删除失败：${file.name}")
        revokeDocumentPermission(documentId)
        file.parentFile?.let { notifyChildren(it) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (documentId == ROOT_ID) throw FileNotFoundException("不能重命名容器根目录")
        val file = fileFor(documentId)
        if (!file.exists()) throw FileNotFoundException("不存在：$documentId")
        val name = sanitizeName(displayName)
        val dst = File(file.parentFile ?: throw FileNotFoundException("无父目录"), name)
        if (dst.exists()) throw FileNotFoundException("同名已存在：$name")
        if (!file.renameTo(dst)) throw FileNotFoundException("重命名失败：${file.name} → $name")
        notifyChildren(dst.parentFile ?: baseDir)
        return docIdOf(dst)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId ||
            documentId.startsWith(parentDocumentId + SEP)

    // ────────────────────────────── 内部 ──────────────────────────────

    /** 文件 → documentId。 */
    private fun docIdOf(file: File): String {
        val root = baseDir.absolutePath
        val path = file.absolutePath
        val rel = if (path == root) "" else path.removePrefix(root).trimStart('/')
        return if (rel.isEmpty()) ROOT_ID else ROOT_ID + SEP + rel
    }

    /**
     * documentId → 文件。**这里是唯一的越权防线**。
     *
     * 三重检查：不含 `.` / `..` / 空段（挡目录穿越），顶层必须是
     * `home` 或 `usr`（挡 `cacheDir` 与其他私有目录），最后拼出来的路径
     * 必须仍在 filesDir 之内。
     */
    private fun fileFor(documentId: String): File {
        if (documentId == ROOT_ID) return baseDir
        if (!documentId.startsWith(ROOT_ID + SEP)) {
            throw FileNotFoundException("非法文档 id：$documentId")
        }
        val rel = documentId.substring(ROOT_ID.length + SEP.length)
        val segments = rel.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw FileNotFoundException("非法文档 id：$documentId")
        }
        if (segments.first() !in TOP_LEVEL) {
            throw FileNotFoundException("不允许访问：$documentId")
        }
        val file = File(baseDir, rel)
        val root = baseDir.absolutePath + File.separator
        if (file.absolutePath != baseDir.absolutePath &&
            !file.absolutePath.startsWith(root)
        ) {
            throw FileNotFoundException("越界路径：$documentId")
        }
        return file
    }

    /** 一行文档记录（按调用方给的列顺序填，缺的列留 null）。 */
    private fun documentValues(documentId: String, cols: Array<out String>): Array<Any?> {
        val file = fileFor(documentId)
        val isRoot = documentId == ROOT_ID
        val isDir = file.isDirectory
        val values = arrayOfNulls<Any?>(cols.size)
        for (i in cols.indices) {
            values[i] = when (cols[i]) {
                Document.COLUMN_DOCUMENT_ID -> documentId
                Document.COLUMN_DISPLAY_NAME ->
                    if (isRoot) ROOT_TITLE else file.name
                Document.COLUMN_MIME_TYPE ->
                    if (isDir) Document.MIME_TYPE_DIR else mimeOf(file)
                Document.COLUMN_SIZE -> if (isDir) null else file.length()
                Document.COLUMN_LAST_MODIFIED -> if (file.exists()) file.lastModified() else null
                Document.COLUMN_FLAGS -> flagsOf(documentId, file)
                Document.COLUMN_SUMMARY -> if (isRoot) "home / usr" else null
                else -> null
            }
        }
        return values
    }

    private fun flagsOf(documentId: String, file: File): Int {
        if (documentId == ROOT_ID) {
            // 根是虚拟目录，只给「能不能往里放」的信号，实际 create 会拒绝
            return Document.FLAG_DIR_PREFERS_GRID
        }
        return if (file.isDirectory) {
            Document.FLAG_DIR_SUPPORTS_CREATE or
                Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_RENAME
        } else {
            Document.FLAG_SUPPORTS_WRITE or
                Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_RENAME
        }
    }

    private fun mimeOf(file: File): String {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    /** 目录可用空间（DocumentsUI 会显示成「可用空间: xx GB」）。 */
    private fun availableBytes(): Long = runCatching {
        StatFs(baseDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    /** 文件名消毒：只挡会让路径语义崩掉的东西，其余原样保留。 */
    private fun sanitizeName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty() || name == "." || name == ".." || name.contains('/') || name.contains('\\')) {
            throw FileNotFoundException("非法文件名：$raw")
        }
        return name
    }

    /** 同名时追加 ` (1)`、` (2)`…，与系统文件管理器一致。 */
    private fun uniqueChild(parent: File, name: String, isDir: Boolean): File {
        var candidate = File(parent, name)
        if (!candidate.exists()) return candidate
        val base = if (isDir) name else name.substringBeforeLast('.', name)
        val ext = if (isDir) "" else name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (candidate.exists() && i < 1000) {
            candidate = File(parent, "$base ($i)$ext")
            i++
        }
        return candidate
    }

    private fun searchInto(dir: File, needle: String, hits: MutableList<File>, depth: Int) {
        if (depth > MAX_SEARCH_DEPTH || hits.size >= MAX_SEARCH_RESULTS) return
        val kids = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (f in kids) {
            if (hits.size >= MAX_SEARCH_RESULTS) return
            if (f.name.lowercase().contains(needle)) hits.add(f)
            // 不跟随符号链接：usr 里可能有指向别处的链接，跟下去会绕圈
            if (f.isDirectory && !isSymlink(f)) {
                searchInto(f, needle, hits, depth + 1)
            }
        }
    }

    /** 递归删除（自底向上），返回是否全部删掉。 */
    private fun deleteTree(file: File): Boolean {
        if (file.isDirectory && !isSymlink(file)) {
            val kids = runCatching { file.listFiles() }.getOrNull()
            if (kids != null) {
                var ok = true
                for (child in kids) {
                    if (!deleteTree(child)) ok = false
                }
                if (!ok) return false
            }
        }
        return runCatching { file.delete() }.getOrDefault(false)
    }

    /** 目录内容变了，通知系统选择器刷新。 */
    private fun notifyChildren(dir: File) {
        runCatching {
            requireNotNull(context).contentResolver.notifyChange(
                DocumentsContract.buildChildDocumentsUri(authority(requireNotNull(context)), docIdOf(dir)),
                null,
            )
        }
    }

    /** 这一项**本身**是不是符号链接（不跟随）。失败时保守地当作不是。 */
    private fun isSymlink(f: File): Boolean = runCatching {
        val st = android.system.Os.lstat(f.absolutePath)
        android.system.OsConstants.S_ISLNK(st.st_mode)
    }.getOrDefault(false)
}
