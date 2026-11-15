package com.example.fcldsh

import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * `.deb` 归档的纯解析层：**ar → tar → 文件树**，不涉及任何业务判断。
 *
 * ## 为什么自己写，不引一个 commons-compress
 *
 * 其实只需要两种格式：
 * - **ar**：`.deb` 的外壳。8 字节魔数 `!<arch>\n`，之后每个成员是
 *   60 字节定长头 + 数据，数据按偶数补齐。总共不到 60 行。
 * - **tar**：`data.tar` 的内容。ustar 头 512 字节，字段是固定偏移的
 *   NUL 结尾字符串，数据同样补齐到 512。只要认几种类型就够。
 *
 * 为一个 `ar` 引一整个压缩库不值得；而 `data.tar.xz` 的 **xz 解码**是真的
 * 复杂（LZMA2 + 范围解码器），所以只有这一层用现成的
 * `org.tukaani:xz`。
 *
 * ## 只实现真正需要的类型
 *
 * | 类型字节 | 含义 | 处理 |
 * |---|---|---|
 * | `'0'` / `'\0'` / `'7'` | 普通文件 | 写出 |
 * | `'5'` | 目录 | 建目录 |
 * | `'2'` | 软链 | 建软链 |
 * | `'L'` / `'x'` | GNU 长名 / PAX 扩展头 | 读出来当下一项的名字 |
 * | 其它 | 硬链、设备节点、FIFO | **跳过** |
 *
 * 硬链要延迟解析目标（目标可能还没解出来），设备节点在应用沙箱里根本
 * 创不出来 —— 两者都会让「解一个正常包」变成一堆失败日志。
 *
 * ## 前缀是调用方的事
 *
 * 这里只把 tar 里的原始路径交给 [PathMapper]，**不关心**它长什么样。
 * 「剥掉 Termux 前缀、落到我们的 `$PREFIX`」是业务规则，属于 [TermuxPkg]。
 */
internal object DebArchive {

    /** 单个条目解出后的体积上限。防一个畸形包把磁盘写满。 */
    private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L

    /** 扩展头（长文件名 / PAX）读取上限。真实值都是几十字节量级。 */
    private const val MAX_HEADER_TEXT = 64 * 1024

    /**
     * 把路径映射成「要写到哪里」。
     *
     * @return null 表示这一项不该落地（例如不在 `$PREFIX` 下的内容），
     *   调用方会连同数据一起跳过 —— **必须跳过数据**，否则流位置会错。
     */
    fun interface PathMapper {
        fun map(rawPath: String): File?
    }

    /** 解包结果。 */
    data class Result(
        /** 真正写出的文件 / 软链的绝对路径（目录不算）。 */
        val written: List<File>,
        /** ar 里出现的成员名，供排查「这个包里到底有什么」。 */
        val members: List<String>
    )

    /**
     * 解一个 `.deb` 到磁盘。
     *
     * @param onProgress 已处理字节 / 包总字节，用来驱动进度条
     * @param onSoftLink  软链目标改写钩子：tar 里的链接目标可能也带 Termux 前缀，
     *   这里交出去让业务层改。默认原样使用。
     */
    fun extract(
        deb: File,
        mapper: PathMapper,
        onProgress: (Float) -> Unit = {},
        onSoftLink: (String) -> String = { it }
    ): Result {
        val total = deb.length().coerceAtLeast(1L)
        val written = ArrayList<File>(64)
        val members = ArrayList<String>(4)

        deb.inputStream().buffered(64 * 1024).use { input ->
            val magic = ByteArray(8)
            if (readFully(input, magic) != 8 ||
                String(magic, Charsets.US_ASCII) != "!<arch>\n"
            ) {
                throw IllegalStateException("不是 ar 归档（.deb 头不对）")
            }
            while (true) {
                val header = ByteArray(60)
                if (readFully(input, header) < 60) break

                // ar 头的布局：name[16] mtime[12] uid[6] gid[6] mode[8]
                //              size[10] magic[2]
                val name = String(header, 0, 16, Charsets.US_ASCII).trim()
                val size = String(header, 48, 10, Charsets.US_ASCII).trim()
                    .toLongOrNull() ?: 0L
                if (size < 0) throw IllegalStateException("ar 成员长度非法: $name")
                val dataName = name.removeSuffix("/")
                members.add(dataName)

                if (dataName == "data.tar.xz" || dataName == "data.tar.gz") {
                    // 数据区先包一层限长流：解压器读到 size 就必须停，
                    // 否则会把后面的成员（比如下一个 ar 头）当成压缩数据吞掉。
                    val limited = LimitedInputStream(input, size)
                    val plain: InputStream = if (dataName.endsWith(".xz")) {
                        XZInputStream(limited)
                    } else {
                        GZIPInputStream(limited)
                    }
                    plain.use { stream ->
                        readTar(stream, mapper, written, onSoftLink) { read ->
                            onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                    // 解压器可能提前停止读取，剩下的必须消费掉
                    limited.drain()
                } else {
                    skipFully(input, size)
                }
                // ar 成员的数据按偶数对齐
                if (size % 2L == 1L) skipFully(input, 1L)
            }
        }
        return Result(written, members)
    }

    // ────────────────────────── tar ──────────────────────────

    /**
     * 读一个 tar 流并落地。
     *
     * 手写而不是用库：只需要认 6 个类型字节、4 个字段偏移，
     * 而引库要多背一份体积与「它怎么处理长名」的未知行为。
     */
    private fun readTar(
        input: InputStream,
        mapper: PathMapper,
        written: MutableList<File>,
        onSoftLink: (String) -> String,
        onRead: (Long) -> Unit
    ) {
        var pendingName: String? = null
        var processed = 0L
        val header = ByteArray(512)

        while (true) {
            if (readFully(input, header) < 512) break
            processed += 512
            // 一个全零块表示归档结束
            if (header.all { it == 0.toByte() }) break

            val name = cString(header, 0, 100)
            val ustarPrefix = cString(header, 345, 155)
            val sizeField = cString(header, 124, 12).trim()
            // size 是**八进制**字符串，不是十进制
            val size = if (sizeField.isEmpty()) 0L else sizeField.toLongOrNull(8) ?: 0L
            val type = header[156].toInt().toChar()
            val linkName = cString(header, 157, 100)

            var full = if (ustarPrefix.isEmpty()) name else "$ustarPrefix/$name"
            val longName = pendingName
            if (longName != null) {
                full = longName
                pendingName = null
            }

            // GNU 长文件名：数据就是下一项的名字
            if (type == 'L') {
                pendingName = readEntryText(input, size)
                processed += size + padLen(size)
                onRead(processed)
                continue
            }
            // PAX 扩展头：只需要 path= 那一行，其余键忽略
            if (type == 'x') {
                val text = readEntryText(input, size)
                Regex("(?m)^\\d+ path=(.+)$").find(text)?.let {
                    pendingName = it.groupValues[1].trimEnd('\u0000')
                }
                processed += size + padLen(size)
                onRead(processed)
                continue
            }

            val target = if (size > MAX_ENTRY_BYTES) null else mapper.map(full)
            if (target == null) {
                skipFully(input, size + padLen(size))
                processed += size + padLen(size)
                onRead(processed)
                continue
            }

            when (type) {
                '5' -> {
                    runCatching { target.mkdirs() }
                    skipFully(input, padLen(size))
                    processed += padLen(size)
                }

                '2' -> {
                    // 软链：先删旧的再建。目标要交回业务层改写，
                    // 否则会指向别人的私有目录。
                    runCatching { target.parentFile?.mkdirs() }
                    runCatching { target.delete() }
                    val link = onSoftLink(linkName)
                    runCatching {
                        android.system.Os.symlink(link, target.absolutePath)
                        written.add(target)
                    }
                    skipFully(input, padLen(size))
                    processed += padLen(size)
                }

                '0', '\u0000', '7' -> {
                    runCatching { target.parentFile?.mkdirs() }
                    val ok = runCatching {
                        target.outputStream().buffered(32 * 1024).use { out ->
                            copyExactly(input, out, size)
                        }
                        true
                    }.getOrDefault(false)
                    if (ok) written.add(target)
                    processed += size
                    skipFully(input, padLen(size))
                    processed += padLen(size)
                }

                else -> {
                    // '1'(硬链) / '3','4'(设备) / '6'(FIFO)：跳过数据区
                    skipFully(input, size + padLen(size))
                    processed += size + padLen(size)
                }
            }
            onRead(processed)
        }
    }

    // ────────────────────────── 流工具 ──────────────────────────

    /** 读满 [buf]，返回实际读到的字节数（小于长度表示流结束）。 */
    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
        return off
    }

    /**
     * 精确复制 [count] 字节。
     *
     * tar 允许最后一项不足块长，所以流提前结束时**不报错**：
     * 那种情况下文件本来就是坏的，抛异常只是换一种方式失败。
     */
    private fun copyExactly(input: InputStream, out: java.io.OutputStream, count: Long) {
        var left = count
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    /** 丢弃 [count] 字节。 */
    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val buf = ByteArray(8 * 1024)
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            left -= n
        }
    }

    /** tar 数据区补到 512 整数倍所需的字节数。 */
    private fun padLen(size: Long): Long = (512L - size % 512L) % 512L

    /**
     * 读扩展头内容并消费掉它的补零。
     *
     * 上限 [MAX_HEADER_TEXT]：长文件名与 PAX 头都是几十字节量级，
     * 但**超出的部分必须照样丢弃**，否则流位置错位，后面全乱。
     */
    private fun readEntryText(input: InputStream, size: Long): String {
        val want = minOf(size, MAX_HEADER_TEXT.toLong()).toInt()
        if (want <= 0) {
            skipFully(input, padLen(size))
            return ""
        }
        val buf = ByteArray(want)
        val got = readFully(input, buf)
        skipFully(input, size - got + padLen(size))
        return String(buf, 0, got, Charsets.UTF_8)
    }

    /** tar 头里的 NUL 结尾字符串。 */
    private fun cString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = minOf(offset + length, buf.size)
        while (end < limit && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    /**
     * 只允许读 [limit] 字节的包装流。
     *
     * 把「ar 成员的数据区」从 `.deb` 的大流里切出来交给解压器：
     * 解压器读到 `limit` 必须停，否则会把后面的成员当成压缩数据。
     */
    private class LimitedInputStream(
        private val inner: InputStream,
        private var limit: Long
    ) : InputStream() {

        override fun read(): Int {
            if (limit <= 0) return -1
            val b = inner.read()
            if (b >= 0) limit--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (limit <= 0) return -1
            val want = minOf(len.toLong(), limit).toInt()
            val n = inner.read(b, off, want)
            if (n > 0) limit -= n
            return n
        }

        override fun available(): Int =
            minOf(limit, inner.available().toLong()).toInt()

        /** 把剩余字节读完，让底层流停在正确位置。 */
        fun drain() {
            if (limit <= 0) return
            val buf = ByteArray(8 * 1024)
            while (limit > 0) {
                val want = minOf(limit, buf.size.toLong()).toInt()
                val n = inner.read(buf, 0, want)
                if (n <= 0) break
                limit -= n
            }
            limit = 0
        }
    }
}
