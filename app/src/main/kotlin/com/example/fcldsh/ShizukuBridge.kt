package com.example.fcldsh

import android.content.Context
import android.util.Log
import com.DSHRunner
import com.ensureShizukuCli
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Shizuku 通道桥：把引擎内 AI 的命令转发到本进程的 Shizuku binder 上执行。
 *
 * ## 为什么必须有这座桥
 *
 * dsh 引擎是**独立进程**。Shizuku 授予的是**本应用进程**的 binder 能力
 * （`Shizuku.getBinder()` 在当前进程里才有效），引擎子进程根本拿不到它。
 * 所以「选了 Shizuku 模式」本身不会让 AI 多出任何能力 —— 必须有一条通路，
 * 把这个能力借给 agent。
 *
 * 通路就是：引擎内的 `shz` 命令 → 本机回环 HTTP → 这里 → `Privilege.shizukuExec()`
 * → Shizuku 服务以 adb(uid 2000) 身份执行。
 *
 * ## 为什么绑 127.0.0.1 且必须带 token
 *
 * 绑回环让局域网里的其它设备连不上。但**同一台设备上的其它应用能连 127.0.0.1**
 * —— 回环不是安全边界。而这条通道能执行任意 shell（adb 身份），
 * 所以必须鉴权：启动时随机生成 token，写进 `$HOME/shz-bridge.json`，
 * 每个请求都要带。别的应用要调，得先读到那个文件 —— 它在应用私有目录里。
 *
 * ## 端口：**独立分配，不依赖引擎**
 *
 * 早先这里是 `引擎端口 + 2`，看似能自动避开冲突，实则埋了一个必然踩到的坑：
 * 引擎端口来自 [com.DSHRunner.currentPort]，那是个**内存态字段** ——
 * 应用进程一重启就归零，哪怕 Node 进程还活着、端口仍然被占着。
 *
 * 于是出现「Shizuku 全通（服务在跑、已授权、状态 READY），但 `shz` 说桥端口 0」：
 * 桥启动时读到 `currentPort == 0`，判定「引擎没跑」直接放弃绑定。
 * 用户则被引向「去重启引擎」「去打开主界面」这类猜测，怎么试都不对。
 *
 * 现在改成**自己占一段固定端口**（与 [A11yBridgeServer] 的 18081 同一套做法）：
 *
 * - `18091` 起，被占就往后试 20 个；
 * - 与 dsh 引擎的 3080–3099、无障碍桥的 18081 都不重叠；
 * - 只要 Shizuku 可用就能绑，**与引擎在不在跑完全无关**。
 *
 * 引擎起来后 `shz` 立刻能用；引擎没起来时绑定也照样成功，只是没人调它而已。
 *
 * ## 生命周期
 *
 * 只在「运行权限 = Shizuku 且服务在线且已授权」时启动。切模式或服务掉线时
 * [stop] 会关掉端口 —— 留着一条没人管的高权限端口是纯风险。
 */
object ShizukuBridge {

    private const val TAG = "DSH-shz"

    /** 起始端口。避开 dsh 的 3080–3099 与无障碍桥的 18081。 */
    private const val BASE_PORT = 18091

    /** 起始端口被占时往后试多少个。 */
    private const val PORT_TRIES = 20

    /**
     * 状态文件名，落在引擎的 HOME 下（`$HOME/shz-status.json`）。
     *
     * ★ 这个文件**总是**存在 ★，即使通道不可用也写。它是 `shz` 命令唯一的
     * 信息来源：命令读它拿到端口/token（可用时），或拿到「为什么不可用」
     * （不可用时）。早先只有通道成功才写发现文件，结果不可用时
     * `shz` 既不存在、也没有任何线索可查。
     */
    private const val STATUS_NAME = "shz-status.json"

    /** 请求体上限。命令是一行 shell，64 KB 已经离谱地宽裕。 */
    private const val MAX_BODY = 64 * 1024

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var token: String = ""

    @Volatile
    private var appContext: Context? = null

    /** 实际监听端口；0 表示未启动。 */
    @Volatile
    var port: Int = 0
        private set

    fun isRunning(): Boolean = running

    /** 容器根（`filesDir/usr`）。所有 `shz` 的落盘都在它下面。 */
    private fun prefixOf(ctx: Context): String =
        File(ctx.filesDir, "usr").absolutePath

    /**
     * 绑一个空闲的回环端口，返回 socket；全被占返回 null。
     *
     * 从 [BASE_PORT] 起逐个试，绑上就用，绑不上试下一个。
     *
     * ⚠️ 刻意**不设** `reuseAddress`：SO_REUSEADDR 会让绑定「成功」到处于
     * TIME_WAIT 的端口上，而这里的目标恰恰是**换一个真正空闲的端口** ——
     * 设了它反而可能拿到一个别人刚释放、仍有余包的端口。
     * 绑定失败就换下一个，语义更直接。
     */
    private fun bindAnyPort(): ServerSocket? {
        for (i in 0 until PORT_TRIES) {
            val p = BASE_PORT + i
            val ss = runCatching {
                ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", p)) }
            }.getOrNull() ?: continue
            Log.i(TAG, "bound 127.0.0.1:$p")
            return ss
        }
        Log.w(TAG, "no free port in $BASE_PORT..${BASE_PORT + PORT_TRIES - 1}")
        return null
    }

    // ==================== 顺序无关：绑定器上线即建通道 ====================

    @Volatile
    private var stateWatcher: Privilege.StateListener? = null

    /**
     * 订阅 Shizuku 状态变化，让通道**不必等引擎重启**。
     *
     * ## 修的是什么（实测踩到的顺序陷阱）
     *
     * 原先 `shz` 只在 [com.DSHRunner.startNode] 里生成一次。于是存在一个
     * 极易踩到、而且完全看不出原因的顺序依赖：
     *
     * ```
     * 先重启引擎（Shizuku 还没启动）   → 连接失败 → 不生成 shz
     * 后启动 Shizuku 服务             → 通道没人管 → 仍然没有 shz
     * ```
     *
     * 用户按这个顺序操作时，`$PREFIX/bin/shz` 永远不存在，`shz id` 报 127，
     * 而设置里明明选了 Shizuku 模式、授权也勾了 —— 从外部看就是「怎么弄都不行」。
     *
     * 现在改成**事件驱动**：绑定器一上线 / 一授权，立刻建桥并把 `shz` 落盘，
     * **不需要引擎在跑**（桥自己占固定端口段，与引擎无关）。
     *
     * 反向同理：服务掉线或授权被撤时立刻收掉端口，
     * 但不删 `shz` —— 命令会打印诊断说明为什么不可用。
     *
     * 幂等：重复调用只注册一次监听。
     */
    @Synchronized
    fun watchState(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx

        if (stateWatcher != null) return
        val l = Privilege.StateListener { onPrivilegeChanged(ctx) }
        stateWatcher = l
        Privilege.addStateListener(l)

        // ★ 立刻做一次对齐（后台线程）★
        //
        // 不能只在引擎启动时才装 shz —— 否则用户还没启动引擎时
        // `command -v shz` 仍然什么都没有，又回到「无从自查」的老问题。
        //
        // 通道可用时这里就会把端口绑起来，所以「应用一打开、Shizuku 就绪」
        // 的顺序下，`shz id` 立刻能用，不用等引擎。
        Thread {
            runCatching { start(ctx, prefixOf(ctx)) }
                .onFailure { Log.w(TAG, "initial sync failed: ${it.message}") }
        }.apply { isDaemon = true; name = "dsh-shz-init"; start() }
    }

    /**
     * Shizuku 状态变了 → 对齐通道。
     *
     * 放在后台线程：里面可能要做 binder 查询、起服务端 socket、写文件，
     * 而回调本身是从 Shizuku 的 binder 线程上来的，不该在那里做这些。
     */
    private fun onPrivilegeChanged(ctx: Context) {
        Thread {
            runCatching { reconcile(ctx) }
                .onFailure { Log.w(TAG, "reconcile failed: ${it.message}") }
        }.apply { isDaemon = true; name = "dsh-shz-watch"; start() }
    }

    private fun reconcile(ctx: Context) {
        // 模式不是 Shizuku：通道本就不该存在。
        if (Privilege.getMode(ctx) != PrivMode.SHIZUKU) {
            if (isRunning()) {
                stop()
                DSHRunner.onLog("ℹ️ 已切出 Shizuku 模式，通道已关闭")
            }
            return
        }

        // ★ 短暂重试 ★
        //
        // 回调可能在「权限刚授予 / binder 刚连上」的那一瞬间到达，此时
        // `checkSelfPermission()` 偶尔还答不上来。不重试就会错过这一次，
        // 而 sticky 监听不会再补发 —— 表现仍是「授权了但没生效」。
        var usable = false
        for (attempt in 0 until 6) {
            usable = Privilege.shizukuUsable()
            if (usable) break
            // 服务真的不在线时重试没有意义（binder 不会因为我们等而出现）。
            if (!Privilege.shizukuRunning()) break
            Thread.sleep(300L)
        }

        if (!usable) {
            // 服务掉线 / 授权被撤：关掉端口，但**保留** `shz` 命令 ——
            // 它会打印诊断说明为什么不可用。删掉命令只会让人无从自查。
            val wasRunning = isRunning()
            stop()
            if (wasRunning) {
                DSHRunner.onLog("ℹ️ Shizuku 通道已关闭（服务掉线或授权被撤）")
            }
            return
        }

        if (isRunning()) return

        // 不再检查引擎端口：桥自己占 18091 起的独立端口段。
        // 早先这里有「引擎没在跑就 defer」的判断，正是「Shizuku 全通但端口 0」
        // 的成因 —— 应用进程重启后 currentPort 归零，哪怕引擎还活着。
        start(ctx, prefixOf(ctx))
        if (isRunning()) {
            // 通道刚起来，agent 的说明文档要跟着更新 —— 否则它仍以为
            // 「Shizuku 未就绪」，不会去用这条新通道。
            runCatching { AgentGuide.refresh(ctx) }
        }
    }

    /**
     * 对齐通道与当前状态。
     *
     * ## 无论可用与否，这三件事**都做**
     *
     * 1. 安装 `shz` 命令 —— 不可用时它打印诊断，而不是「命令不存在」
     * 2. 写 `$HOME/shz-status.json` —— 记录模式/服务/授权/原因/该怎么做
     * 3. 可用时额外绑回环端口
     *
     * ## 为什么不再「失败就不装」
     *
     * 早先的实现是「连不上 Shizuku 就不生成 `shz`」。结果用户只能看到
     * `command -v shz` 无输出、`shz id` 报 127，**看不到任何原因**，
     * 于是反复猜「是不是没重启引擎」「是不是模式没生效」，而真实原因
     * （Shizuku 服务没在跑）被完全遮住。
     *
     * ## 为什么不再需要 enginePort 参数
     *
     * 桥现在自己占 18091 起的端口段（见 [bindAnyPort]），与引擎端口无关。
     * 原先绑在「引擎端口 + 2」上，导致引擎没在跑（或应用进程重启后
     * `currentPort` 归零）时桥永远绑不上 —— 表现为「Shizuku 全通、但端口 0」。
     *
     * 幂等：已绑定时只刷新状态文件。
     */
    @Synchronized
    fun start(context: Context, prefix: String) {
        val ctx = context.applicationContext
        appContext = ctx

        val mode = Privilege.getMode(ctx)
        val state = Privilege.shizukuState(ctx)
        val ready = mode == PrivMode.SHIZUKU && state == Privilege.ShizukuState.READY

        // ★ 命令永远装 ★（返回值只用于日志，不该因为它为 false 就跳过状态写入）
        val cliOk = runCatching { ensureShizukuCli(prefix) }.getOrDefault(false)

        if (!ready) {
            closeServer()
            writeStatus(ctx, mode, state)
            val why = reasonFor(mode, state)
            if (mode == PrivMode.SHIZUKU) {
                // 只有用户确实选了 Shizuku 模式时才值得提示 —— 普通模式下
                // 通道本来就不该存在，报一句「未启动」纯属噪音。
                DSHRunner.onLog("ℹ️ Shizuku 通道未启动：$why")
                DSHRunner.onLog("ℹ️ 运行 `shz status` 可随时查看原因")
            }
            if (!cliOk) DSHRunner.onLog("⚠️ 安装 shz 命令失败")
            return
        }

        // 已经绑好：只刷新状态（端口/token 没变，无需重绑）。
        if (server != null) {
            writeStatus(ctx, mode, state)
            return
        }

        val ss = bindAnyPort()
        if (ss == null) {
            DSHRunner.onLog(
                "⚠️ Shizuku 通道启动失败：$BASE_PORT..${BASE_PORT + PORT_TRIES - 1} 全部被占用"
            )
            closeServer()
            writeStatus(ctx, mode, state)
            return
        }

        // 端口由 socket 自己报告，不要用循环变量 —— 两者理论上一致，
        // 但以实际绑上的为准，免得状态文件里写的是"想绑的"而不是"绑上的"。
        val p = ss.localPort
        server = ss
        port = p
        running = true
        token = randomToken()

        Thread({ acceptLoop(ss) }, "shz-bridge").apply {
            isDaemon = true
            start()
        }

        writeStatus(ctx, mode, state)
        DSHRunner.onLog("✅ Shizuku 通道已启动（127.0.0.1:$p，agent 用 `shz <命令>`）")
        Log.i(TAG, "bridge listening on 127.0.0.1:$p")
    }

    /**
     * 关闭回环桥（**不删 `shz` 命令**）。
     *
     * ## 为什么不删命令
     *
     * 早先这里会 `delete()` 掉 `$PREFIX/bin/shz`，认为「留一条必然失败的入口
     * 比没有更糟」。实际效果相反：用户 `command -v shz` 什么都没有，
     * 连「为什么没有」都无从查起。
     *
     * 现在命令常驻，不可用时它自己打印诊断（模式 / 服务 / 授权 / 原因 / 处理）。
     * 删掉的只有**端口**与 **token** —— 那才是真正该收回的能力。
     *
     * 状态文件也会同步更新，所以 `shz status` 立刻能反映「通道已关闭」。
     */
    @Synchronized
    fun stop() {
        closeServer()
        appContext?.let { ctx ->
            runCatching {
                writeStatus(ctx, Privilege.getMode(ctx), Privilege.shizukuState(ctx))
            }
        }
    }

    /** 只关 socket，不动文件。可重复调用。 */
    private fun closeServer() {
        val wasRunning = server != null
        running = false
        port = 0
        token = ""
        runCatching { server?.close() }
        server = null
        if (wasRunning) Log.i(TAG, "bridge socket closed")
    }

    // ==================== HTTP ====================

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val client = runCatching { ss.accept() }.getOrNull() ?: continue
            Thread({ handle(client) }, "shz-req").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 20_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), "ISO-8859-1"))

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respond(client, 400, "bad request line")
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]

            // 请求头必须读完，否则部分客户端会等着我们把连接关掉。
            val headers = HashMap<String, String>()
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim().lowercase()
                    val v = line.substring(idx + 1).trim()
                    headers[k] = v
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                }
            }

            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))

            // 鉴权：header 优先，其次 query（方便手敲排查）
            val given = headers["x-shz-token"] ?: query["token"].orEmpty()
            if (token.isNotEmpty() && given != token) {
                respond(client, 401, "invalid token")
                return
            }

            when (path) {
                "/", "/status" -> {
                    val ctx = appContext
                    respond(
                        client, 200,
                        buildStatusJson(ctx)
                    )
                }

                "/exec" -> {
                    if (method != "POST") {
                        respond(client, 405, "use POST")
                        return
                    }
                    if (contentLength < 0 || contentLength > MAX_BODY) {
                        respond(client, 413, "body too large")
                        return
                    }
                    val buf = CharArray(contentLength)
                    var off = 0
                    while (off < contentLength) {
                        val n = reader.read(buf, off, contentLength - off)
                        if (n <= 0) break
                        off += n
                    }
                    val cmd = String(buf, 0, off).trim()
                    if (cmd.isEmpty()) {
                        respond(client, 400, "empty command")
                        return
                    }
                    val ctx = appContext
                    if (ctx == null) {
                        respond(client, 500, "no context")
                        return
                    }
                    val out = Privilege.runPrivileged(ctx, cmd)
                    if (out == null) {
                        respond(
                            client, 503,
                            "shizuku/su 都不可用（Shizuku 服务掉线或未授权？）"
                        )
                    } else {
                        respond(client, 200, out)
                    }
                }

                else -> respond(client, 404, "unknown path: $path")
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle failed: ${e.message}")
            runCatching { respond(client, 500, e.message ?: "error") }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun respond(client: Socket, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ')
            append(
                when (code) {
                    200 -> "OK"
                    400 -> "Bad Request"
                    401 -> "Unauthorized"
                    404 -> "Not Found"
                    405 -> "Method Not Allowed"
                    413 -> "Payload Too Large"
                    503 -> "Service Unavailable"
                    else -> "Internal Server Error"
                }
            ).append("\r\n")
            append("Content-Type: text/plain; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out: OutputStream = client.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=').let {
                runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }
            val v = if (pair.contains('=')) pair.substringAfter('=') else ""
            out[k] = runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
        }
        return out
    }

    // ==================== 状态文件 ====================

    /**
     * `$HOME/shz-status.json`。
     *
     * 用引擎的 HOME（`files/home`）而不是应用私有根：agent 的 `$HOME` 就是那里，
     * `shz` 命令读它拿到端口/token（可用时）或「为什么不可用」（不可用时）。
     *
     * 把诊断信息放进这个文件、而不是只打日志，是因为**日志不在 agent 手上** ——
     * 它只能读文件、跑命令。一个能被 `cat` 到的状态文件，才是它能自查的东西。
     */
    private fun statusFile(context: Context): File =
        File(com.appHomeDir(context), STATUS_NAME)

    /**
     * 写状态文件。**任何情况下都写**，包括通道不可用、甚至模式不是 Shizuku。
     *
     * 早先只有建桥成功才写发现文件，于是不可用时 `shz` 既不存在、也没有任何
     * 线索可查。现在反过来：状态文件是「诊断的单一真源」，`shz` 只负责读它。
     */
    private fun writeStatus(
        context: Context,
        mode: PrivMode,
        state: Privilege.ShizukuState
    ) {
        val f = statusFile(context)
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(
                buildString {
                    append("{\"mode\":\"").append(mode.id).append('"')
                    append(",\"sdk\":").append(android.os.Build.VERSION.SDK_INT)
                    append(",\"supported\":").append(Privilege.shizukuSupported())
                    append(",\"installed\":").append(Privilege.shizukuInstalled(context))
                    append(",\"running\":").append(Privilege.shizukuRunning())
                    append(",\"granted\":").append(Privilege.shizukuGranted())
                    append(",\"state\":\"").append(state.name).append('"')
                    // 端口为 0 = 通道未启动，`shz` 据此拒绝执行并打印诊断。
                    append(",\"port\":").append(port)
                    append(",\"token\":\"").append(token).append('"')
                    append(",\"reason\":\"").append(jsonEscape(reasonFor(mode, state))).append('"')
                    append(",\"hint\":\"").append(jsonEscape(hintFor(mode, state))).append('"')
                    append("}\n")
                }
            )
        }.onFailure { Log.w(TAG, "writeStatus failed: ${it.message}") }
    }

    /** 「为什么不可用」——一句话，直接写进状态文件与日志。 */
    private fun reasonFor(mode: PrivMode, state: Privilege.ShizukuState): String =
        if (mode != PrivMode.SHIZUKU) {
            "当前运行模式是「${mode.label}」，不是 Shizuku 模式"
        } else when (state) {
            Privilege.ShizukuState.UNSUPPORTED -> "系统版本低于 Android 7，Shizuku 库不支持"
            Privilege.ShizukuState.NOT_INSTALLED -> "本机没有安装 Shizuku"
            Privilege.ShizukuState.NOT_RUNNING ->
                "Shizuku 服务没有在运行，或 binder 未送达本应用"
            Privilege.ShizukuState.NOT_GRANTED -> "Shizuku 服务在运行，但本应用未获授权"
            Privilege.ShizukuState.READY -> "通道可用"
        }

    /** 「该怎么做」——直接可执行的一句话，写给用户也写给 agent。 */
    private fun hintFor(mode: PrivMode, state: Privilege.ShizukuState): String =
        if (mode != PrivMode.SHIZUKU) {
            "到 设置 → 权限设置 → 运行模式，选「Shizuku 模式」并完成授权"
        } else when (state) {
            Privilege.ShizukuState.UNSUPPORTED ->
                "本机系统版本过低，只能使用普通模式或 Root 模式"
            Privilege.ShizukuState.NOT_INSTALLED ->
                "安装 Shizuku 应用，并用「无线调试」配对或 root 启动它的服务"
            Privilege.ShizukuState.NOT_RUNNING ->
                "① 打开 Shizuku 应用，用「无线调试」配对或 root 启动服务，" +
                    "主页显示「运行中」；② 若服务已在运行而这里仍不变，" +
                    "把 FCL-DSH 从最近任务里划掉重开 —— binder 只在 Shizuku " +
                    "服务启动或应用进程启动时投递一次"
            Privilege.ShizukuState.NOT_GRANTED ->
                "设置 → 权限设置 → 运行模式 → 点「申请」；" +
                    "或在 Shizuku 的「应用管理」里打开 FCL-DSH 的开关"
            Privilege.ShizukuState.READY -> "无需处理"
        }

    /** JSON 字符串转义（状态文件是给机器读的，不能因为引号就坏掉）。 */
    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .replace("\r", " ")

    private fun buildStatusJson(ctx: Context?): String {
        val mode = ctx?.let { Privilege.getMode(it) } ?: PrivMode.NORMAL
        return buildString {
            append("{\"apiVersion\":1,\"running\":").append(running)
            append(",\"port\":").append(port)
            append(",\"mode\":\"").append(mode.id).append('"')
            append(",\"shizukuSupported\":").append(Privilege.shizukuSupported())
            append(",\"shizukuRunning\":").append(Privilege.shizukuRunning())
            append(",\"shizukuGranted\":").append(Privilege.shizukuGranted())
            // ⚠️ 这里必须用只读缓存，不能调 usableSuPrefix() ——
            // 那个函数会真的起 su 子进程（首次还会弹 Magisk 授权框），
            // 而本函数在每次 `/status` 请求里都会跑。
            append(",\"hasRoot\":").append(Privilege.hasKnownRoot())
            append(",\"endpoints\":[\"GET /status\",\"POST /exec\"]}")
        }
    }

    private fun randomToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
