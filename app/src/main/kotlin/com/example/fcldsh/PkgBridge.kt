package com.example.fcldsh

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.UUID

/**
 * `pkg` 命令的**绕行实现** —— 不装 dpkg / apt，直接用应用内部的 [TermuxPkg]。
 *
 * ## 为什么必须绕行
 *
 * 容器里的 `bin/pkg` 是 Termux 的残留脚本：它第 11 行
 * `source /data/data/com.termux/files/usr/bin/termux-setup-package-manager`，
 * 而那个路径在本机**根本不存在** —— 所以那个脚本是坏的。`apt` / `dpkg` 也全无，
 * 连 dpkg 数据库都没有。
 *
 * 而**装真正的 dpkg/apt 也救不了**，三条独立的死路：
 *
 * | 阻塞 | 后果 |
 * |---|---|
 * | dpkg 要 fork+exec 维护者脚本（postinst）与 dpkg-deb | 这些 exec 全被 W^X 拦，包永远停在「已解包未配置」 |
 * | Termux 的 dpkg/apt 把前缀编译死成 `/data/data/com.termux/files/usr` | 我们的前缀更长，连原地覆写都做不到 |
 * | 想重编它们得先有 clang + 头文件（约 1.5~2 GB） | 当前容器没有，且代价远大于收益 |
 *
 * 所以这里走另一条路：**应用内部早就有 [TermuxPkg]**（自己解 ar/tar/xz、
 * 剥掉 Termux 前缀、改写文本里的路径、记录安装清单）。把它经**本地 HTTP**
 * 暴露出来，再配一个 node 小 CLI 包成 `pkg` 命令，覆盖
 * `install` / `remove` / `search` / `list` / `status`。
 *
 * ## 为什么是「函数 + HTTP」而不是写个脚本
 *
 * - **函数**：`bin/pkg` 脚本在私有目录，`execve` 会被拦，用起来得写
 *   `sh $PREFIX/bin/pkg ...`。而 shell 函数由 sh 自己解释，裸敲 `pkg` 就行。
 *   函数体由 [SandboxExec] 生成到 rc 里。
 * - **HTTP**：真正干活的是 Kotlin（xz 解码只有它有）。CLI 是 node 写的，
 *   而 node 在本环境可用（经 linker64），所以不需要 curl。
 *
 * ## 安全
 *
 * - 只绑 `127.0.0.1`，外部网络访问不到；
 * - 每个请求都要 `X-Pkg-Token` 头，token 随机、只写在 `~/.pkg-bridge.json`；
 * - token 每次应用启动重新生成 —— **重启应用后旧终端里的 pkg 会鉴权失败**，
 *   重开终端即可。
 */
internal object PkgBridge {

    /** 端口起点。与 a11y（18081）、shizuku（18091）错开。 */
    private const val PORT_START = 18095

    private const val PORT_TRIES = 12

    /** 状态文件（写在 `$HOME` 下，与 `a11y-bridge.json` 同一约定）。 */
    private const val STATE_NAME = ".pkg-bridge.json"

    /**
     * 版本标记：CLI 内容变了才重写，避免每次启动碰磁盘。
     *
     * ★ 改 [CLI_JS] 时必须**同时**改这里的 rev 号 ★
     * 判据是「已落盘的 CLI 文本里包含这个串」。改内容不改标记的话，
     * 老设备上永远不会更新 CLI —— 而 CLI 在 `$PREFIX/lib` 里，
     * 用户不会想到去手动删它。
     */
    private const val CLI_MARKER = "fcl-pkg cli rev:2"

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var token = ""

    @Volatile
    private var container: File? = null

    @Volatile
    var port = 0
        private set

    // ────────────────────────── 生命周期 ──────────────────────────

    /**
     * 启动服务并写好 CLI。**幂等**，可反复调用。
     *
     * 在 [com.DSHRunner.startNode] 里调（与 a11y / shz 的 shim 同一处）。
     * 失败只记日志、绝不阻断引擎启动 —— pkg 是附加能力，不是启动前提。
     */
    fun ensure(context: Context, prefix: File, onLog: (String) -> Unit) {
        container = prefix
        runCatching { writeCli(prefix) }
            .onFailure { onLog("⚠️ 写入 pkg CLI 失败: ${it.message ?: it.javaClass.simpleName}") }

        if (server != null) {
            // 已在跑：只刷新状态文件（HOME 可能换了实例）
            runCatching { writeState(context) }
            return
        }
        runCatching { start(context, onLog) }
            .onFailure { onLog("⚠️ pkg 服务启动失败: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun start(context: Context, onLog: (String) -> Unit) {
        val addr = InetAddress.getByName("127.0.0.1")
        var bound: ServerSocket? = null
        var chosen = 0
        for (p in PORT_START until PORT_START + PORT_TRIES) {
            val s = runCatching { ServerSocket(p, 16, addr) }.getOrNull() ?: continue
            bound = s
            chosen = p
            break
        }
        val srv = bound
        if (srv == null) {
            onLog("⚠️ pkg 服务：$PORT_START~${PORT_START + PORT_TRIES - 1} 全被占用，pkg 不可用")
            return
        }
        port = chosen
        token = UUID.randomUUID().toString().replace("-", "")
        server = srv
        writeState(context)

        Thread {
            while (!srv.isClosed) {
                val sock = runCatching { srv.accept() }.getOrNull() ?: break
                Thread { runCatching { handle(context, sock) } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()

        onLog("📦 pkg 命令已就绪（端口 $port，终端里直接敲 pkg）")
    }

    // ────────────────────────── HTTP ──────────────────────────

    private fun handle(context: Context, sock: Socket) {
        sock.use { s ->
            s.soTimeout = 20_000
            val inp = s.inputStream.bufferedReader()

            val requestLine = inp.readLine() ?: return
            var authorized = false
            // 只关心 token 头；请求体不用（参数全部走 query string）。
            while (true) {
                val line = inp.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i <= 0) continue
                val name = line.substring(0, i).trim()
                if (name.equals("X-Pkg-Token", ignoreCase = true) &&
                    line.substring(i + 1).trim() == token
                ) {
                    authorized = true
                }
            }

            val out = s.outputStream
            if (!authorized) {
                out.write(
                    (
                        "HTTP/1.1 401 Unauthorized\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\n" +
                            "Connection: close\r\n\r\n" +
                            "pkg: 鉴权失败。重启应用后 token 会变，请重开一次终端。\n"
                        ).toByteArray()
                )
                out.flush()
                return
            }

            val target = requestLine.split(' ').getOrNull(1) ?: "/"
            val path = target.substringBefore('?')
            val q = query(target)

            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/plain; charset=utf-8\r\n" +
                        "Connection: close\r\n" +
                        "Cache-Control: no-store\r\n\r\n"
                    ).toByteArray()
            )
            out.flush()

            fun say(text: String = "") {
                runCatching {
                    out.write((text + "\n").toByteArray())
                    out.flush()
                }
            }

            val prefix = container
            // ★ 退出码要显式回传 ★
            //
            // HTTP 200 只代表「请求被处理了」，不代表装成功。CLI 靠响应末尾的
            // `#exit N` 标记取真正的退出码 —— 这样 `pkg install x && echo ok`
            // 才有意义。不回传的话 CLI 只能一律当成成功。
            var exitCode = 0

            if (prefix == null) {
                say("pkg: 容器路径未初始化（请先启动一次应用）")
                exitCode = 1
            } else {
            when (path) {
                "/status" -> {
                    say("pkg 服务正常")
                    say("  端口: $port")
                    say("  容器: " + prefix.absolutePath)
                    say("  架构: " + TermuxPkg.debArch())
                    say("  已装: " + TermuxPkg.installed(prefix).size + " 个包")
                    say("")
                    say("用法: pkg install | remove | search | list | status")
                }

                "/list" -> {
                    val ins = TermuxPkg.installed(prefix)
                    if (ins.isEmpty()) {
                        say("还没有装任何包。用 `pkg search <关键词>` 找找。")
                    } else {
                        say("已装 " + ins.size + " 个包：")
                        ins.forEach {
                            say("  " + it.name + "  " + it.version + "  (" + it.files.size + " 个文件)")
                        }
                    }
                }

                "/search" -> {
                    val kw = q["q"]?.firstOrNull().orEmpty().trim()
                    if (kw.isEmpty()) {
                        say("用法: pkg search <关键词>")
                        exitCode = 1
                    } else {
                        say("⏳ 读取仓库索引（首次需要下载几 MB）…")
                        val index = runCatching { TermuxPkg.loadIndex() }.getOrNull()
                        if (index == null) {
                            say("❌ 索引读取失败。检查网络后重试。")
                            exitCode = 1
                        } else {
                            val hits = TermuxPkg.search(index, kw, 30)
                            if (hits.isEmpty()) {
                                say("没有匹配「$kw」的包（索引共 " + index.size + " 个）")
                            } else {
                                say("匹配 " + hits.size + " 个：")
                                hits.forEach { p ->
                                    say(
                                        "  " + p.name + "  " + p.version + "  " +
                                            sizeText(p.size) + "  " + p.description
                                    )
                                }
                                say("")
                                say("安装: pkg install <包名>")
                            }
                        }
                    }
                }

                "/install" -> {
                    val names = q["name"].orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                    if (names.isEmpty()) {
                        say("用法: pkg install <包名...>")
                        exitCode = 1
                    } else if (TermuxPkg.isBusy()) {
                        say("pkg: 已有安装任务在进行，请等它结束")
                        exitCode = 1
                    } else {
                        say("📦 目标：" + names.joinToString(" "))
                        say("⏳ 读取仓库索引并解析依赖（首次要下几 MB）…")
                        val n = TermuxPkg.install(context, prefix, names) { line -> say(line) }
                        say("")
                        if (n > 0) {
                            say("✅ 完成：成功装好 $n 个包")
                            say("ℹ️ 新装的命令已可用；若当前终端还看不到，重开一次终端。")
                        } else {
                            say("❌ 没有装成功任何包，原因见上面的日志")
                            exitCode = 1
                        }
                    }
                }

                "/remove" -> {
                    val name = q["name"]?.firstOrNull().orEmpty().trim()
                    if (name.isEmpty()) {
                        say("用法: pkg remove <包名>")
                        exitCode = 1
                    } else {
                        val n = TermuxPkg.uninstall(prefix, name) { line -> say(line) }
                        if (n < 0) {
                            say("❌ 没有装过「$name」")
                            exitCode = 1
                        } else {
                            say("✅ 已卸载 $name")
                        }
                    }
                }

                else -> {
                    say("pkg: 未知请求 $path")
                    exitCode = 1
                }
            }
            }

            // 结束标记：CLI 靠它取退出码，也靠它确认响应确实传完了
            runCatching { say("#exit $exitCode") }
        }
    }

    /** 解析 query string，支持同名参数重复（`?name=a&name=b`）。 */
    private fun query(target: String): Map<String, List<String>> {
        val idx = target.indexOf('?')
        if (idx < 0) return emptyMap()
        val out = HashMap<String, MutableList<String>>(4)
        for (pair in target.substring(idx + 1).split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawK = if (eq < 0) pair else pair.substring(0, eq)
            val rawV = if (eq < 0) "" else pair.substring(eq + 1)
            val k = runCatching { URLDecoder.decode(rawK, "UTF-8") }.getOrDefault(rawK)
            val v = runCatching { URLDecoder.decode(rawV, "UTF-8") }.getOrDefault(rawV)
            out.getOrPut(k) { ArrayList(2) }.add(v)
        }
        return out
    }

    private fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format(Locale.ROOT, "%d KB", bytes / 1024)
        else -> "$bytes B"
    }

    // ────────────────────────── 落盘 ──────────────────────────

    /** 写 `$HOME/.pkg-bridge.json`，CLI 靠它找到端口与 token。 */
    private fun writeState(context: Context) {
        if (port <= 0 || token.isEmpty()) return
        val home = runCatching { com.appHomeDir(context) }.getOrNull() ?: return
        runCatching {
            val o = JSONObject()
            o.put("port", port)
            o.put("token", token)
            val f = File(home, STATE_NAME)
            f.writeText(o.toString())
            // 只有本应用 UID 可读：别的应用不该能驱动包安装
            f.setReadable(true, true)
        }
    }

    private fun writeCli(prefix: File) {
        val lib = File(prefix, "lib")
        if (!lib.isDirectory && !lib.mkdirs()) {
            throw IllegalStateException("无法创建 ${lib.absolutePath}")
        }
        val f = File(lib, "fcl-pkg.js")
        if (f.isFile && runCatching { f.readText().contains(CLI_MARKER) }.getOrDefault(false)) {
            return
        }
        f.writeText(CLI_JS)
        f.setReadable(true, false)
    }

    /**
     * `pkg` 的 CLI（node 脚本）。
     *
     * 刻意**不用模板字符串、不出现美元符号** —— 这段文本嵌在 Kotlin 的原始字符串里，
     * 只要有 `$` 就会被当插值，编译期就炸。用字符串拼接同样清晰。
     */
    private val CLI_JS = """
// fcl-pkg cli rev:2
// FCL-DSH 的 pkg 命令：不依赖 dpkg/apt，直接调本应用内部的 TermuxPkg。
// 通过 127.0.0.1 上的本地服务工作，凭据在 ~/.pkg-bridge.json。
var fs = require('fs')
var path = require('path')
var http = require('http')

// ★ 一律用 exitCode，绝不调 process.exit() ★
//
// 本 CLI 的 stdout 是**管道**（应用的终端读它），而 Node 对管道的写入是
// 异步的。`process.exit()` 会立刻终止进程，把还在缓冲区的输出丢掉 ——
// 表现就是「命令跑了、但只看到半截输出」，长输出（装包日志）尤其明显。
// 设 exitCode 之后让事件循环自然走空，Node 会 flush 完再退出。
function fail(m) {
  console.error('pkg: ' + m)
  process.exitCode = 1
}

var home = process.env.HOME || '.'
var stateFile = path.join(home, '.pkg-bridge.json')
var st = null

// fail() 只设 exitCode、不终止进程（见上面的说明），所以这里必须用 if/else 串起来
// —— 否则「失败之后继续往下跑」会拿空的 st 去取 st.port，直接崩成一段堆栈。
if (!fs.existsSync(stateFile)) {
  fail('包管理服务未启动。请先打开 FCL-DSH 应用，再重开终端重试。')
} else {
  try {
    st = JSON.parse(fs.readFileSync(stateFile, 'utf8'))
  } catch (e) {
    fail('状态文件损坏：' + e.message)
    st = null
  }
  if (st && (!st.port || !st.token)) {
    fail('状态文件缺少 port/token。请重开一次终端。')
    st = null
  }
}

var argv = process.argv.slice(2)
var cmd = argv[0] || 'help'
var rest = argv.slice(1)

function usage() {
  console.log('pkg —— 容器包管理器（不依赖 dpkg/apt）')
  console.log('')
  console.log('  pkg install <包名...>   安装（连依赖一起）')
  console.log('  pkg remove  <包名>      卸载')
  console.log('  pkg search  <关键词>    搜索仓库')
  console.log('  pkg list                已安装')
  console.log('  pkg status              服务状态')
  console.log('')
  console.log('例：pkg install ffmpeg')
}

// 流式读响应：逐行转发到 stdout，并拦下服务端在末尾发的 `#exit N` 标记
// —— 那才是真正的退出码（HTTP 200 只代表「连接成功」，不代表装成功了）。
//
// 用**行缓冲**而不是整段缓冲：装一个大包要跑几分钟，整段缓冲意味着几分钟里
// 屏幕上一个字都没有，用户会以为卡死。
function call(qs) {
  return new Promise(function (resolve, reject) {
    var req = http.request({
      host: '127.0.0.1',
      port: st.port,
      path: qs,
      method: 'GET',
      headers: { 'X-Pkg-Token': st.token }
    }, function (res) {
      var buf = ''
      var code = res.statusCode === 200 ? 0 : 1
      function handleLine(line) {
        if (line.indexOf('#exit ') === 0) {
          var n = parseInt(line.slice(6), 10)
          if (!isNaN(n)) code = n
        } else {
          process.stdout.write(line + '\n')
        }
      }
      res.setEncoding('utf8')
      res.on('data', function (c) {
        buf += c
        var i
        while ((i = buf.indexOf('\n')) >= 0) {
          handleLine(buf.slice(0, i))
          buf = buf.slice(i + 1)
        }
      })
      res.on('end', function () {
        if (buf.length > 0) handleLine(buf)
        resolve(code)
      })
    })
    req.on('error', function (e) { reject(e) })
    // 0 = 不设超时：装包可能几分钟，不能中途断开
    req.setTimeout(0)
    req.end()
  })
}

function enc(s) { return encodeURIComponent(s) }

// 只在拿到有效凭据时才真的发请求。st 为 null 说明上面已经 fail 过，
// exitCode 已经设好，这里什么都不做即可让 Node 正常退出。
if (st) {
  var run = null
  if (cmd === 'install' || cmd === 'add' || cmd === 'i') {
    if (rest.length === 0) {
      fail('请给出包名，例如 pkg install ffmpeg')
    } else {
      run = call('/install?name=' + rest.map(enc).join('&name='))
    }
  } else if (cmd === 'remove' || cmd === 'uninstall' || cmd === 'rm') {
    if (rest.length === 0) {
      fail('请给出包名，例如 pkg remove ffmpeg')
    } else {
      run = call('/remove?name=' + enc(rest[0]))
    }
  } else if (cmd === 'search' || cmd === 'find') {
    if (rest.length === 0) {
      fail('请给出关键词，例如 pkg search ffmpeg')
    } else {
      run = call('/search?q=' + enc(rest.join(' ')))
    }
  } else if (cmd === 'list' || cmd === 'ls') {
    run = call('/list')
  } else if (cmd === 'status') {
    run = call('/status')
  } else if (cmd === 'help' || cmd === '--help' || cmd === '-h') {
    usage()
  } else {
    fail('未知子命令：' + cmd)
    usage()
  }

  if (run) {
    run.then(function (code) {
      // 用 exitCode 而不是 process.exit()：后者会丢掉仍在缓冲区的输出
      process.exitCode = code
    }).catch(function (e) {
      fail('连不上本地包管理服务（' + (e.message || e) + '）。请确认 FCL-DSH 还在运行。')
    })
  }
}
""".trimIndent()
}
