package com.example.fcldsh

import com.DSHRunner
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * dsh 引擎包的下载与安装。
 *
 * ## 为什么引擎要单独下载
 *
 * 容器（Node + bash + 第三方依赖，约 130MB）随 APK 内置，它是固定基础；
 * 而 dsh 引擎（`@deepseek-ai/` 这棵子树）迭代快，单独下载才能在
 * 不重装 APK 的情况下升级。相关目录约定见 [DSHRunner.installDsh]。
 *
 * 注意：KDoc 里不能写「斜杠+星号」，Kotlin 的块注释支持嵌套，
 * 那样会再开一层注释，导致整个文件被判为未闭合（本项目已踩过两次）。
 *
 * ## 为什么不直接边下边装
 *
 * 网络中断会产生半个 zip，`ZipInputStream` 解到一半才发现损坏，
 * 此时容器里已经写入了一些文件。所以先落盘到 cache，校验成功再解压安装。
 * 代价是需要约 30MB 临时空间，换来失败时容器不被污染。
 *
 * ## 状态更新为什么都 post 到主线程
 *
 * [phase] / [progress] / [message] 是 Compose 的 `mutableStateOf`，
 * 在后台线程直接写会破坏快照一致性。统一切回主线程写。
 */
object DshEngineDownloader {

    enum class Phase(val label: String) {
        IDLE("就绪"),
        DOWNLOADING("下载中"),
        INSTALLING("安装中"),
        DONE("完成"),
        ERROR("失败")
    }

    var phase by mutableStateOf(Phase.IDLE)
        private set

    /** 0f..1f 为确定进度；负值表示不确定（连接中 / 解压中）。 */
    var progress by mutableStateOf(-1f)
        private set

    /** 供 UI 显示的一行说明（含已下载体积）。 */
    var message by mutableStateOf("")
        private set

    @Volatile
    private var cancelled = false

    @Volatile
    private var busy = false

    private val main = Handler(Looper.getMainLooper())

    private class Cancelled : Exception()

    fun isBusy(): Boolean = busy

    /** 取消下载。安装阶段不可中断（解压中途停下会留下半个引擎）。 */
    fun cancel() {
        if (phase == Phase.DOWNLOADING) cancelled = true
    }

    /** 把状态复位到「就绪」，便于重新开始。 */
    fun reset() {
        if (busy) return
        phase = Phase.IDLE
        progress = -1f
        message = ""
    }

    private fun post(block: () -> Unit) = main.post(block)

    /**
     * 从 URL 下载引擎包并安装。
     *
     * 失败原因会写进 [message]（HTTP 状态码、超时等），
     * 解压层面的细节在 [DSHRunner] 的日志里。
     */
    fun downloadAndInstall(context: Context, url: String) {
        if (busy) return
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            phase = Phase.ERROR
            message = "地址必须以 http:// 或 https:// 开头"
            return
        }

        busy = true
        cancelled = false
        phase = Phase.DOWNLOADING
        progress = -1f
        message = "正在连接…"
        // 前台保活：下载完成后还要解压安装，全程不能被系统冻结。
        // 服务靠轮询 isBusy() 自行退出，这里无需配对 stop。
        EngineTaskService.start(context, "正在下载 dsh 引擎包")

        Thread {
            val tmp = File(context.cacheDir, "dsh-engine-download.zip")
            var ok = false
            try {
                downloadTo(trimmed, tmp)
                if (cancelled) {
                    post { phase = Phase.IDLE; progress = -1f; message = "已取消" }
                    return@Thread
                }
                post { phase = Phase.INSTALLING; progress = -1f; message = "正在解压安装…" }
                ok = tmp.inputStream().use { stream ->
                    // 下载到本地文件了，长度可知 —— 解压阶段也能给确定百分比
                    DSHRunner.installDsh(context, stream, tmp.length()) { p ->
                        post { progress = p }
                    }
                }
                post {
                    if (ok) {
                        phase = Phase.DONE
                        progress = 1f
                        message = "dsh 引擎已安装"
                    } else {
                        phase = Phase.ERROR
                        message = "安装失败，详见运行日志"
                    }
                }
            } catch (e: Cancelled) {
                post { phase = Phase.IDLE; progress = -1f; message = "已取消" }
            } catch (e: Exception) {
                post {
                    phase = Phase.ERROR
                    progress = -1f
                    message = "下载失败: ${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                runCatching { tmp.delete() }
                busy = false
            }
        }.start()
    }

    /**
     * 从本地文件安装（用户自选的 zip）。
     *
     * 给「没有稳定下载地址」的情况留一条退路：把引擎包放进手机存储，
     * 用系统文件选择器指过来即可。
     */
    fun installFrom(context: Context, input: InputStream, label: String, totalBytes: Long = -1L) {
        if (busy) return

        busy = true
        cancelled = false
        phase = Phase.INSTALLING
        progress = -1f
        message = "正在安装 $label…"
        // 从本地安装同样可能耗时（解压几千个文件），保活一并覆盖。
        EngineTaskService.start(context, "正在安装 $label")

        Thread {
            var ok = false
            try {
                // ── 先确保内置容器就绪 ────────────────────────────────────────
                //
                // 这一步不能省。installDsh 开头就检查 runtimeInstalled()，容器没解压
                // 就直接返回 false —— 表现就是「全新安装后第一次导入整合包必定失败，
                // 必须先去下载中心点一次，让那边的流程顺手把容器准备好」。
                //
                // 用户不该为了导入一个本地 zip 先去做一件看起来无关的事，
                // 所以在安装前就地准备好容器（ensureRuntime 内部有锁，
                // 与启动线程并发进来也不会互相破坏）。
                if (!DSHRunner.runtimeInstalled(context)) {
                    post {
                        progress = -1f
                        message = "首次安装：正在解压内置容器…"
                    }
                }
                if (!DSHRunner.ensureRuntime(context)) {
                    post {
                        phase = Phase.ERROR
                        message = "内置容器解压失败，无法导入"
                    }
                    // ★ 早退必须自己清 busy ★
                    // 这个分支以前直接 return@Thread，跳过了末尾的 busy = false，
                    // 于是 busy 永久为 true：之后每一次导入都在开头的
                    // `if (busy) return` 处静默返回 —— 用户看到的现象就是
                    // 「确认名字后什么都没发生」，而且重启应用之前再也装不进去。
                    busy = false
                    return@Thread
                }
                post { message = "容器就绪，正在导入 $label…" }

                // 传 totalBytes 才能在进度窗里显示真实百分比；
                // SAF 拿不到长度时传 -1，UI 会自动退化成不确定进度条。
                ok = DSHRunner.installDsh(context, input, totalBytes) { p ->
                    post {
                        // 负数表示「不知道总量」，原样传给 UI
                        progress = p
                        message = if (p >= 0f) {
                            "正在解压 ${(p * 100).toInt()}%"
                        } else {
                            "正在解压…"
                        }
                    }
                }
            } catch (e: Exception) {
                // 失败细节已由 installDsh 写进日志
            } finally {
                runCatching { input.close() }
            }
            post {
                if (ok) {
                    phase = Phase.DONE
                    progress = 1f
                    message = "dsh 引擎已安装"
                } else {
                    phase = Phase.ERROR
                    message = "安装失败，详见运行日志"
                }
            }
            busy = false
        }.start()
    }

    /**
     * 下载到 [dest]，边下边更新进度。
     *
     * 用 `HttpURLConnection` 而不引第三方库：只有一个 GET，
     * 加上 Cookie/UA 也不需要，没必要为此多一个依赖。
     */
    private fun downloadTo(url: String, dest: File) {
        // 声明成**非空 val**。
        //
        // 原来写的是 `var conn: HttpURLConnection? = null` 再在 try 里赋值，
        // 结果后面每一处 `conn.xxx` 都报
        // 「Only safe (?.) or non-null asserted (!!) calls are allowed」——
        // Kotlin 不会为**可变的**局部变量做智能转换，哪怕它在上一行刚被赋值。
        // 提成 val 后既不用满地写 !!，finally 里也不必再判空。
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "fcl-dsh/${BuildConfig.VERSION_NAME} (Android)")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code ${conn.responseMessage.orEmpty()}".trim())
            }

            // `contentLengthLong` 是 API 24 的接口，本项目 minSdk 是 23，
            // 直接用会在旧设备上崩。改为自己解析响应头，取值等价。
            val total = conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            // 先写临时名，成功后才改名——避免半截文件被误当完整包
            val part = File(dest.parentFile, dest.name + ".part")
            conn.inputStream.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPostAt = 0L
                    while (true) {
                        if (cancelled) throw Cancelled()
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        done += read

                        val now = System.currentTimeMillis()
                        if (now - lastPostAt > 200) {
                            lastPostAt = now
                            val p = if (total > 0) (done.toFloat() / total) else -1f
                            val text = if (total > 0) {
                                "${mb(done)} / ${mb(total)} MB"
                            } else {
                                "${mb(done)} MB"
                            }
                            post { progress = p; message = text }
                        }
                    }
                }
            }
            if (!part.renameTo(dest)) {
                // renameTo 在跨文件系统时可能失败，退化为复制
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun mb(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes / 1024f / 1024f)
}
