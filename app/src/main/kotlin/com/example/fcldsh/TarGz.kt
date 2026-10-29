package com.example.fcldsh

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 极简 tar.gz 解压器。
 *
 * ## 为什么需要它
 *
 * 容器里没有可用的 npm（`bin/npm` 只是指向 `lib/node_modules/npm` 的入口，
 * 而该目录在构建期被裁掉了）。要补回来就得从官方 registry 下载 npm 本体，
 * 而 npm 发布的是 **.tgz**（gzip + tar），`java.util.zip.ZipInputStream`
 * 无法解析，所以自己实现一个只够用的 tar 读取器。
 *
 * ## 支持范围
 *
 * 只处理 npm tarball 实际会用到的形态：
 * - 普通文件（typeflag `0` 或 `\0`）
 * - 目录（`5`）
 * - GNU 长文件名（`L`）
 * - POSIX pax 扩展头（`x`，解析其中的 `path=`）
 *
 * 符号链接直接跳过 —— npm 包里没有必须保留的相对链接，
 * 而 Android 上创建符号链接需要额外权限。
 *
 * 有意不做的事：不还原权限位（调用方自己 `setExecutable`）、
 * 不校验校验和（数据来自 HTTPS 的官方 registry）。
 */
object TarGz {

    private const val BLOCK = 512

    /**
     * 解压 tar.gz 到 [destDir]。
     *
     * @param stripComponents 剥掉的路径层数。npm tarball 的条目形如
     *   `package/bin/npm-cli.js`，传 1 就能把 `package/` 去掉。
     * @return 成功写出的文件数。
     */
    fun extract(gz: InputStream, destDir: File, stripComponents: Int = 0): Int {
        destDir.mkdirs()
        var files = 0
        var pendingLongName: String? = null
        var pendingPaxPath: String? = null

        GZIPInputStream(gz, 64 * 1024).use { input ->
            val header = ByteArray(BLOCK)
            while (true) {
                if (!readFully(input, header)) break
                // 全零块是结束标记
                if (header.all { it.toInt() == 0 }) break

                val nameField = readString(header, 0, 100)
                val prefixField = readString(header, 345, 155)
                val size = readOctal(header, 124, 12)
                val type = header[156].toInt().toChar()

                var name = if (prefixField.isNotEmpty()) "$prefixField/$nameField" else nameField

                when (type) {
                    // GNU 长文件名：下一块是名字本身
                    'L' -> {
                        pendingLongName = readString(input, size.toInt())
                        skipPadding(input, size)
                        continue
                    }
                    // pax 扩展头：形如 "<len> path=xxx\n"
                    'x' -> {
                        val text = readString(input, size.toInt())
                        pendingPaxPath = parsePaxPath(text)
                        skipPadding(input, size)
                        continue
                    }
                    // 元数据类条目（全局 pax / 字符设备等）一律跳过
                    'g', 'c', 'b', 'D', 'M', 'N', 'S', 'V' -> {
                        skipPadding(input, size)
                        continue
                    }
                }

                pendingPaxPath?.let { name = it; pendingPaxPath = null }
                pendingLongName?.let { name = it; pendingLongName = null }

                // 去掉指定的层级
                val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
                if (parts.size <= stripComponents) {
                    skipPadding(input, size)
                    continue
                }
                val rel = parts.drop(stripComponents).joinToString("/")
                val out = resolveInside(destDir, rel)

                if (type == '5' || name.endsWith("/")) {
                    out?.mkdirs()
                    skipPadding(input, size)
                    continue
                }

                if (out == null) {
                    // 越界路径（含 ..）直接丢弃，但仍要把数据读掉保持流对齐
                    skipBytes(input, size)
                    skipPadding(input, size)
                    continue
                }

                out.parentFile?.mkdirs()
                FileOutputStream(out).use { fos ->
                    copyExactly(input, fos, size)
                }
                files++
                skipPadding(input, size)
            }
        }
        return files
    }

    /** 从 pax 记录里取 `path=` 的值；没有则返回 null。 */
    private fun parsePaxPath(text: String): String? {
        text.lineSequence().forEach { line ->
            val idx = line.indexOf(' ')
            if (idx <= 0) return@forEach
            val kv = line.substring(idx + 1)
            if (kv.startsWith("path=")) {
                return kv.substring(5)
            }
        }
        return null
    }

    private fun resolveInside(root: File, rel: String): File? {
        if (rel.isBlank() || rel.contains("..")) return null
        val f = File(root, rel)
        val rootPath = root.canonicalPath + File.separator
        return if (f.canonicalPath.startsWith(rootPath)) f else null
    }

    /** 读满 [buf]，返回是否读到完整长度。 */
    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) return false
            off += n
        }
        return true
    }

    private fun readString(input: InputStream, len: Int): String {
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n <= 0) break
            off += n
        }
        return String(buf, 0, off, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun readString(block: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val limit = minOf(offset + len, block.size)
        while (end < limit && block[end].toInt() != 0) end++
        return String(block, offset, end - offset, Charsets.UTF_8).trim()
    }

    /** tar 的数字字段是八进制 ASCII，可能带空格或 NUL 结尾。 */
    private fun readOctal(block: ByteArray, offset: Int, len: Int): Long {
        val s = readString(block, offset, len).trim()
        if (s.isEmpty()) return 0
        return runCatching { java.lang.Long.parseLong(s, 8) }.getOrDefault(0)
    }

    private fun copyExactly(input: InputStream, out: FileOutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val want = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun skipBytes(input: InputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(8 * 1024)
        while (remaining > 0) {
            val want = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            remaining -= n
        }
    }

    /** tar 每块 512 字节，内容不足要补齐。 */
    private fun skipPadding(input: InputStream, size: Long) {
        val pad = ((BLOCK - (size % BLOCK)) % BLOCK).toInt()
        if (pad > 0) skipBytes(input, pad.toLong())
    }
}
