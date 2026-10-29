package com.example.fcldsh

import android.content.Context
import android.graphics.Rect
import android.util.Log
import com.DSHRunner
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * 无障碍能力桥接：在 **127.0.0.1** 上开一个极小的 HTTP 服务，
 * 让 dsh 的 agent 能通过 `curl` 调用读屏与点击。
 *
 * ## ★ 为什么必须是 HTTP，而不是 WebView 的 JS 注入 ★
 *
 * 之前用 `addJavascriptInterface` 注入的 `window.fclA11y` 只存在于
 * **WebView 的客户端 JS** 里。而 dsh 的 agent 跑在**服务端 Node 进程**中 ——
 * 它根本够不到浏览器窗口对象，所以那条路对 agent 完全不可见：
 *
 * ```
 * 浏览器侧 JS  ──可以调──> window.fclA11y        （有用，但只对页面脚本有效）
 * Node 侧 agent ──够不到──> window.fclA11y        （这才是 agent 需要的那条）
 * ```
 *
 * agent 手上真正有的能力是**bash**（spawn 补丁已跑通）。而它这个 Node 进程
 * 是本应用的子进程、同一个 UID（u0_a600），所以它能连本机回环端口。
 * 于是「开一个本地 HTTP 端口 + 用 curl 调」就成了唯一不需要 Shizuku、
 * 也不需要改动 dsh 自身插件体系的通路。
 *
 * ## 为什么绑 127.0.0.1 而不是 0.0.0.0，且必须带 token
 *
 * 绑回环：局域网内的其他设备就连不上来了。
 *
 * 但**同一台设备上的其他应用也能连 127.0.0.1** —— 回环不是安全边界。
 * 读屏能读到密码框与别的应用界面、点击能代替用户操作，所以必须鉴权：
 * 启动时生成一个随机 token，写进发现文件，每个请求都要带。
 * 别的应用要调，得先读到那个文件 —— 而它在应用私有目录里，读不到。
 *
 * ## 发现文件：`~/a11y-bridge.json`
 *
 * dsh 的进程 HOME 是 `files/home`（见 DSHRunner.startNode 里的 workDir）。
 * 所以把端口与 token 写到 `files/home/a11y-bridge.json`，agent 直接
 * `cat ~/a11y-bridge.json` 就能拿到全部连接信息，不需要猜端口。
 *
 * 文件内容形如：
 * ```json
 * {"port":18081,"token":"…","url":"http://127.0.0.1:18081","apiVersion":1}
 * ```
 *
 * ## 线程模型
 *
 * 所有无障碍调用都由本服务的后台线程发起。`AccessibilityService` 的这些方法
 * 背后是到系统服务的 IPC，可以从任意线程调用；每一处都包了 runCatching ——
 * 让 HTTP 处理线程抛异常会把连接晾在那里不响应，调用方只能等超时。
 */
object A11yBridgeServer {

    private const val TAG = "A11yBridge"

    /** 起始端口。刻意避开 dsh 用的 3080–3099，避免互相抢。 */
    private const val BASE_PORT = 18081

    /** 端口被占时往后试多少个。 */
    private const val PORT_TRIES = 20

    /** 本桥接的 API 版本，便于调用方判断能力。 */
    const val API_VERSION = 1

    /** 发现文件名，落在 dsh 的 HOME 下。 */
    private const val DISCOVERY_NAME = "a11y-bridge.json"

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    private var token: String = ""

    /**
     * 应用上下文，供路由计算截图文件路径。
     *
     * 路由函数拿不到 Context，但 `/screenshot` 需要 `files/home` 的绝对路径。
     * 在 [start] 里存一份 applicationContext（不会泄漏 Activity）。
     */
    @Volatile
    private var appContext: android.content.Context? = null

    /** 实际监听端口；0 表示未启动。 */
    @Volatile
    var port: Int = 0
        private set

    fun isRunning(): Boolean = running

    /**
     * 当前 token，供 [AgentGuide] 生成给 agent 看的指令文档。
     *
     * 桥接未启动时返回空串。调用方不要把它写进任何可被第三方应用读到的位置
     * —— 它等同于「读屏 + 代替用户点击」的钥匙。
     */
    fun currentToken(): String = token

    /**
     * 最近一次「能力缺失」的原因，供 `/status` 返回给 agent。
     *
     * 典型场景：无障碍服务是开着的，但系统还按**旧配置**给的能力
     * （用户升级 APK 后没重新开关过服务），于是没有 `canTakeScreenshot`。
     * 这时候 agent 看到的不该只是「失败」，而是一句能转告用户的话。
     *
     * 用 Compose 可变状态：设置页也可能要显示这条提示。
     */
    @Volatile
    private var capabilityHint: String = ""

    /** 由 [DshAccessibilityService.onServiceConnected] 在检测到能力缺失时调用。 */
    fun onCapabilityHintGot(hint: String) {
        capabilityHint = hint
        DSHRunner.onLog("⚠️ $hint")
    }

    /** 供 `/status` 读取；没有提示时返回空串。 */
    fun capabilityHint(): String = capabilityHint

    private fun homeDir(context: Context) = com.appHomeDir(context)

    private fun discoveryFile(context: Context) = File(homeDir(context), DISCOVERY_NAME)

    /** 生成 32 字节随机 token（64 位十六进制）。 */
    private fun newToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(64)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /**
     * 启动桥接。可重复调用（幂等），不会重复开端口。
     *
     * 触发点有两处：[MainActivity.onCreate] 与
     * [DshAccessibilityService.onServiceConnected] ——
     * 后者是必要的：系统可以只为了绑定无障碍服务而重新拉起进程，
     * 那时 Activity 还没创建，只从 Activity 启动就会漏掉。
     */
    fun start(context: Context) {
        if (running) return
        synchronized(this) {
            if (running) return

            val app = context.applicationContext
            // 路由函数拿不到 Context，但 /screenshot 需要 files/home 的绝对路径。
            // 存 applicationContext 不会吊住 Activity。
            appContext = app
            if (token.isEmpty()) token = newToken()

            val socket = bindLoopback()
            if (socket == null) {
                DSHRunner.onLog("❌ 无障碍桥接：$BASE_PORT..${BASE_PORT + PORT_TRIES} 全部被占用")
                return
            }

            server = socket
            port = socket.localPort
            running = true

            writeDiscovery(app)

            Thread({ acceptLoop(socket) }, "a11y-bridge").apply {
                isDaemon = true
                start()
            }

            DSHRunner.onLog("🌉 无障碍桥接已启动 http://127.0.0.1:$port")
            DSHRunner.onLog("   凭据文件: ~/$DISCOVERY_NAME（cat ~/$DISCOVERY_NAME 可读取）")
            Log.i(TAG, "listening on 127.0.0.1:$port")
        }
    }

    /** 停止桥接并撤掉发现文件（避免留下指向已死端口的凭据）。 */
    fun stop(context: Context) {
        synchronized(this) {
            running = false
            runCatching { server?.close() }
            server = null
            port = 0
            runCatching { discoveryFile(context.applicationContext).delete() }
        }
    }

    /** 只在回环地址上尝试绑定，端口从 [BASE_PORT] 往后找。 */
    private fun bindLoopback(): ServerSocket? {
        val addr = InetAddress.getByName("127.0.0.1")
        for (p in BASE_PORT until BASE_PORT + PORT_TRIES) {
            val s = runCatching {
                ServerSocket().apply {
                    // reuseAddress 让进程重启后立刻能重新绑上，不必等 TIME_WAIT
                    reuseAddress = true
                    bind(InetSocketAddress(addr, p), 8)
                }
            }.getOrNull()
            if (s != null) return s
        }
        return null
    }

    /**
     * 写发现文件。
     *
     * 同时写一份 `a11y-bridge.md`，内容是给人（和 agent）直接照着用的
     * curl 命令示例 —— agent 读 JSON 还要自己拼 URL，读 md 可以直接抄。
     */
    private fun writeDiscovery(context: Context) {
        val url = "http://127.0.0.1:$port"
        runCatching {
            homeDir(context).mkdirs()
            discoveryFile(context).writeText(
                JSONObject().apply {
                    put("port", port)
                    put("token", token)
                    put("url", url)
                    put("apiVersion", API_VERSION)
                }.toString(2)
            )
        }.onFailure {
            DSHRunner.onLog("⚠️ 写无障碍凭据文件失败: ${it.message}")
        }

        runCatching {
            File(homeDir(context), "a11y-bridge.md").writeText(
                """
                |# 无障碍桥接（读屏 + 点击）
                |
                |本应用的无障碍服务已把「读屏」与「模拟点击」暴露在本机回环端口上。
                |agent 直接用 bash 调即可，不需要 Shizuku。
                |
                |## 连接信息
                |
                |端口：$port
                |token：$token
                |地址：$url
                |
                |后续命令都用这个前缀（token 必须以请求头传，否则 401）：
                |
                |```bash
                |A11Y_URL=$url
                |A11Y_TOKEN=$token
                |a11y() { curl -sS -H "X-A11y-Token: ${'$'}A11Y_TOKEN" "${'$'}A11Y_URL${'$'}1"; }
                |```
                |
                |## 先看能不能用
                |
                |```bash
                |a11y /status
                |```
                |返回 `{"ok":true,"service":"connected",...}` 才说明无障碍已开启。
                |若 `service` 是 `disconnected`，需要用户到系统设置 → 无障碍里
                |开启「FCL-DSH 屏幕操作」。
                |
                |## 读屏
                |
                |```bash
                |a11y '/screen?limit=200'        # 结构化 JSON：文字、控件类、bounds、可点击
                |a11y '/screen/text?limit=200'   # 纯文本，每行一条可见文字
                |```
                |
                |节点里 `b` 是 `[left,top,right,bottom]` 屏幕像素坐标，可直接喂给 `/tap`。
                |
                |## 点击
                |
                |```bash
                |a11y '/tap?x=540&y=1200'
                |a11y '/tapText?text=发送'          # 按文字找工作并点击（推荐）
                |a11y '/tapText?text=发送&exact=true'
                |a11y '/longPress?x=540&y=1200&ms=800'
                |a11y /back
                |a11y /home
                |```
                |
                |`/tapText` 会优先用控件自身的点击动作（比按坐标戳可靠），
                |点不动时才退回按坐标点它的中心。
                |
                |## 已知限制
                |
                |- 只能读**无障碍树**：WebView、Flutter 自绘界面里部分内容可能读不全。
                |- **不能截图**。`screencap` 需要 shell/root 权限，无障碍做不到；
                |  截图要用 MediaProjection（需要用户每次授权），本桥接未实现。
                |- 坐标是屏幕**物理像素**，横竖屏切换后要重新 `/screen` 取一次。
                |- 点击是异步的，调用返回 true 只代表手势已下发，不代表界面已响应；
                |  操作后建议 sleep 300–500ms 再 `/screen` 复核。
                """.trimMargin()
            )
        }

        // 把「本机有手机控制能力」写进 agent 每次启动都会读的 AGENTS.md。
        //
        // 只写 a11y-bridge.md 是不够的：agent 不知道要去读它，上一轮就是
        // 靠人工粘说明才跑通的。AGENTS.md 是各级 agent 工具链的通用约定名，
        // 写在那里它一开口就知道有这个桥、怎么拿凭据、哪些事做不到。
        runCatching { AgentGuide.refresh(context) }
            .onFailure { Log.w(TAG, "刷新 agent 说明失败: ${it.message}") }
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = runCatching { socket.accept() }.getOrNull() ?: continue
            // 每个连接一个短线程：请求量极小（一次点击/一次读屏），
            // 不值得为此引入线程池。
            Thread({ handle(client) }, "a11y-req").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), "ISO-8859-1"))

            val requestLine = reader.readLine() ?: return
            // "GET /path?query HTTP/1.1"
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respond(client.getOutputStream(), 400, jsonError("bad request line"))
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]

            // 只接受 GET / POST。读操作走 GET，动作走 POST 是为了让
            // agent 一眼看出哪些调用有副作用；但两者都允许 —— 手敲 curl
            // 时经常顺手用 GET，为此报错只会添麻烦。
            if (method != "GET" && method != "POST") {
                respond(client.getOutputStream(), 405, jsonError("method not allowed: $method"))
                return
            }

            // 读完请求头。必须读完，否则部分客户端会因为没消费完请求而卡住。
            val headers = HashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }

            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))

            // 鉴权：header 优先，其次 query（方便手敲 curl 调试）
            val given = headers["x-a11y-token"] ?: query["token"].orEmpty()
            if (token.isNotEmpty() && given != token) {
                respond(client.getOutputStream(), 401, jsonError("invalid token"))
                return
            }

            val body = route(path, query)
            respond(client.getOutputStream(), 200, body)
        } catch (e: Exception) {
            Log.w(TAG, "handle failed: ${e.message}")
            runCatching {
                respond(client.getOutputStream(), 500, jsonError(e.message ?: "error"))
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun route(path: String, query: Map<String, String>): String = when (path) {
        "/", "/help" -> jsonOk(
            JSONObject().apply {
                put("apiVersion", API_VERSION)
                put(
                    "endpoints", JSONArray(
                        listOf(
                            "GET /status",
                            "GET /screenshot",
                            "GET /screen?limit=200",
                            "GET /screen/text?limit=200",
                            "POST /tap?x=&y=",
                            "POST /longPress?x=&y=&ms=",
                            "POST /tapText?text=&exact=",
                            "POST /input?text=&target=&append=",
                            "POST /back",
                            "POST /home",
                            "GET /windows",
                            "POST /swipe?x1=&y1=&x2=&y2=&ms=",
                            "POST /drag?dx=&dy=",
                            "POST /window/step?scale=",
                            "POST /window/restore",
                            "POST /minimize?scale=",
                            "GET /check?x=&y="
                        )
                    )
                )
                put(
                    "note",
                    "读屏需无障碍服务已开启；截图需用户在应用内授权投屏（/status 的 " +
                        "canScreenshot）。不要用 screencap/screenrecord，它们在本沙箱里必然失败。"
                )
                put(
                    "window",
                    "小窗由用户手工设置（Android 没有让应用主动缩成自由窗口的公共 API）。" +
                        "/windows 读小窗范围与形态（large/small/ball），/check 判断目标是否被挡，" +
                        "/drag 拖顶部中央挪开，/window/step 缩小一档（大窗→小窗→悬浮球），" +
                        "/window/restore 尝试点最后位置恢复。"
                )
            }
        )

        "/status" -> statusJson()

        // 截图：返回已落盘的 PNG 绝对路径。
        //
        // 为什么不直接返回图片字节（base64 内联）：1080×2400 的 PNG 常有几百 KB，
        // base64 后更大，塞进 JSON 既撑爆命令行输出也不方便 agent 处理。
        // 给路径更实用 —— agent 用它手上的读图能力打开即可。
        "/screenshot" -> screenshotJson()

        "/screen" -> {
            val svc = DshAccessibilityService.instance
            if (svc == null) jsonUnavailable() else jsonOk(
                JSONObject().apply {
                    put("nodes", svc.snapshot(limit(query)))
                }
            )
        }

        "/screen/text" -> {
            val svc = DshAccessibilityService.instance
            if (svc == null) jsonUnavailable() else jsonOk(
                JSONObject().apply {
                    put("text", svc.readScreenText(limit(query)))
                }
            )
        }

        "/tap" -> {
            val svc = DshAccessibilityService.instance
            val x = query["x"]?.toFloatOrNull()
            val y = query["y"]?.toFloatOrNull()
            when {
                svc == null -> jsonUnavailable()
                x == null || y == null -> jsonError("需要 x 与 y 参数（屏幕像素）")
                else -> jsonAction("tap", svc.tap(x, y))
            }
        }

        "/longPress" -> {
            val svc = DshAccessibilityService.instance
            val x = query["x"]?.toFloatOrNull()
            val y = query["y"]?.toFloatOrNull()
            val ms = query["ms"]?.toLongOrNull() ?: 800L
            when {
                svc == null -> jsonUnavailable()
                x == null || y == null -> jsonError("需要 x 与 y 参数（屏幕像素）")
                else -> jsonAction("longPress", svc.longPress(x, y, ms))
            }
        }

        "/tapText" -> {
            val svc = DshAccessibilityService.instance
            val text = query["text"]
            val exact = query["exact"]?.toBooleanStrictOrNull() ?: false
            when {
                svc == null -> jsonUnavailable()
                text.isNullOrBlank() -> jsonError("需要 text 参数")
                else -> jsonAction("tapText", svc.clickByText(text, exact))
            }
        }

        // 文字输入：一次写入整段，不用逐字点按软键盘。
        //
        // 参考项目的做法一致（AgentBridge 的 `POST /input`，body
        // {"text":"...","target":"<输入框提示>"}）：用 target 定位输入框，
        // 再走系统的 ACTION_SET_TEXT。比逐字点按键可靠得多 ——
        // 键位随输入法/语言/大小写变化，中文与 emoji 根本无法用按键输入。
        "/input" -> {
            val svc = DshAccessibilityService.instance
            val text = query["text"]
            val target = query["target"]
            val append = query["append"]?.toBooleanStrictOrNull() ?: false
            when {
                svc == null -> jsonUnavailable()
                // 注意：text 为空串是**合法**的，表示清空输入框，
                // 所以判 null 而不是判 isBlank。
                text == null -> jsonError("需要 text 参数（空串表示清空）")
                else -> {
                    val ok = svc.inputText(text, target, append)
                    JSONObject().apply {
                        put("ok", ok)
                        put("action", "input")
                        put("length", text.length)
                        put("append", append)
                        if (!ok) {
                            put(
                                "reason",
                                if (target.isNullOrBlank()) {
                                    "找不到可写入的输入框（页面上没有可编辑控件，或无障碍服务未连）"
                                } else {
                                    "找不到提示文字含「$target」的输入框，或该输入框" +
                                        "不支持 ACTION_SET_TEXT（部分 WebView 会拒绝）"
                                }
                            )
                            put(
                                "hint",
                                "先用 a11y tree 确认输入框存在及其坐标；" +
                                    "若该输入框拒绝直接写入，只能退回逐字输入或请用户手动输入。"
                            )
                        }
                    }.toString()
                }
            }
        }

        "/back" -> {
            val svc = DshAccessibilityService.instance
            if (svc == null) jsonUnavailable() else jsonAction("back", svc.back())
        }

        "/home" -> {
            val svc = DshAccessibilityService.instance
            if (svc == null) jsonUnavailable() else jsonAction("home", svc.home())
        }

        // ── 小窗（自由窗口）协助 ────────────────────────────────────────
        //
        // 本应用**不会**自己进入小窗：Android 没有让应用主动缩成自由窗口的
        // 公共 API（WINDOWING_MODE_FREEFORM 是 @SystemApi）。小窗由用户手工摆好，
        // 这几个端点只做无障碍能力范围内的事：读范围、判断遮挡、挪开、最小化。

        "/windows" -> {
            val svc = DshAccessibilityService.instance
            if (svc == null) {
                jsonUnavailable()
            } else {
                val b = svc.ownWindowBounds()
                val screen = svc.screenSize()
                val mode = svc.currentWindowMode()
                JSONObject().apply {
                    put("ok", true)
                    put(
                        "screen",
                        JSONObject().put("w", screen.x).put("h", screen.y)
                    )
                    // 三档形态：large / small / ball / unknown
                    put("mode", mode.label)
                    if (b == null) {
                        // 读不到窗口不等于出错：可能是悬浮球（窗口已隐藏），
                        // 也可能是应用被切到后台 —— 无障碍侧无法区分，不猜。
                        put("window", JSONObject.NULL)
                        put(
                            "hint",
                            when (mode) {
                                DshAccessibilityService.WindowMode.BALL ->
                                    "窗口不可见，可能是悬浮球形态。可用 /window/restore " +
                                        "尝试点最后位置恢复；失败就请用户手动点一下悬浮球。"

                                else ->
                                    "本应用当前没有可见窗口。若需要边看对话边操作，" +
                                        "请让用户手动把本应用设为小窗（最近任务里的" +
                                        "「小窗」或「在自由窗口中打开」）。"
                            }
                        )
                    } else {
                        put("window", rectJson(b))
                    }
                }.toString()
            }
        }

        // 缩小一档：大窗 → 小窗 → 悬浮球。
        //
        // 手势是「按住左下角**外侧**边缘向右上划」。上一版按内侧 24px 起手
        // 会落进内容区，结果成了滚动页面 —— 这是实测确认的。
        "/window/step" -> {
            val svc = DshAccessibilityService.instance
            val scale = query["scale"]?.toFloatOrNull() ?: 0.55f
            if (svc == null) {
                jsonUnavailable()
            } else {
                val before = svc.ownWindowBounds()
                val modeBefore = svc.currentWindowMode()
                val ok = svc.shrinkWindow(scale)
                JSONObject().apply {
                    put("ok", ok)
                    put("action", "shrink")
                    put("dispatched", ok)
                    put("modeBefore", modeBefore.label)
                    if (before != null) put("before", rectJson(before))
                    put(
                        "next",
                        "手势已下发。请 sleep 0.6 后调 /windows 读 mode，" +
                            "确认是否进入下一档（large → small → ball）"
                    )
                    if (!ok) put("reason", "窗口未知，无法计算起手点")
                }.toString()
            }
        }

        // 从悬浮球/小窗恢复一档：点最后已知位置的中心。
        //
        // 只是启发式：悬浮球是系统绘制的图标，不出现在本应用的
        // AccessibilityWindowInfo 里，拿不到确切坐标，只能点最后位置。
        "/window/restore" -> {
            val svc = DshAccessibilityService.instance
            if (svc == null) {
                jsonUnavailable()
            } else {
                val known = svc.lastKnownBounds
                if (known == null) {
                    JSONObject().apply {
                        put("ok", false)
                        put("action", "restore")
                        put(
                            "reason",
                            "没有已知的窗口位置（本次运行还没读到过窗口）。" +
                                "请用户手动点一下悬浮球或从最近任务恢复。"
                        )
                    }.toString()
                } else {
                    val ok = svc.restoreFromBall(known)
                    JSONObject().apply {
                        put("ok", ok)
                        put("action", "restore")
                        put("dispatched", ok)
                        put("tapped", rectJson(known))
                        put(
                            "next",
                            "已点最后已知位置中心。请 sleep 0.6 后调 /windows 复核 mode；" +
                                "若仍是 ball，请用户手动点悬浮球，不要重复盲点。"
                        )
                    }.toString()
                }
            }
        }

        "/swipe" -> {
            val svc = DshAccessibilityService.instance
            val x1 = query["x1"]?.toFloatOrNull()
            val y1 = query["y1"]?.toFloatOrNull()
            val x2 = query["x2"]?.toFloatOrNull()
            val y2 = query["y2"]?.toFloatOrNull()
            val ms = query["ms"]?.toLongOrNull() ?: 600L
            when {
                svc == null -> jsonUnavailable()
                x1 == null || y1 == null || x2 == null || y2 == null ->
                    jsonError("需要 x1,y1,x2,y2（屏幕像素）")

                else -> jsonAction("swipe", svc.swipe(x1, y1, x2, y2, ms))
            }
        }

        "/drag" -> {
            val svc = DshAccessibilityService.instance
            val dx = query["dx"]?.toFloatOrNull()
            val dy = query["dy"]?.toFloatOrNull()
            when {
                svc == null -> jsonUnavailable()
                dx == null || dy == null -> jsonError("需要 dx 与 dy（相对位移，像素）")

                else -> JSONObject().apply {
                    val before = svc.ownWindowBounds()
                    val ok = svc.dragOwnWindowBy(dx, dy)
                    // 拖动是异步手势，这里只能确认「已下发」。真正的位移量
                    // 由调用方稍后再查 /windows 确认 —— 不做虚假的「已移动」承诺。
                    put("ok", ok)
                    put("action", "drag")
                    put("dispatched", ok)
                    if (before != null) put("before", rectJson(before))
                    put("dx", dx.toDouble())
                    put("dy", dy.toDouble())
                    put(
                        "next",
                        "手势已下发，请 sleep 0.7 后再调 /windows 确认实际位置"
                    )
                    if (!ok) put("reason", "手势未能下发（窗口未知或缺少手势权限）")
                }.toString()
            }
        }

        "/minimize" -> {
            val svc = DshAccessibilityService.instance
            val scale = query["scale"]?.toFloatOrNull() ?: 0.55f
            when {
                svc == null -> jsonUnavailable()
                else -> JSONObject().apply {
                    val before = svc.ownWindowBounds()
                    val ok = svc.minimizeOwnWindow(scale)
                    put("ok", ok)
                    put("action", "minimize")
                    put("dispatched", ok)
                    if (before != null) put("before", rectJson(before))
                    put("scale", scale.toDouble())
                    put(
                        "next",
                        "已按下左边缘向右上划（最小化手势）。请 sleep 0.6 后调 /windows 确认"
                    )
                    if (!ok) put("reason", "窗口未知，无法计算起手点")
                }.toString()
            }
        }

        "/check" -> {
            val svc = DshAccessibilityService.instance
            val x = query["x"]?.toFloatOrNull()
            val y = query["y"]?.toFloatOrNull()
            val l = query["l"]?.toFloatOrNull()
            val t = query["t"]?.toFloatOrNull()
            val r = query["r"]?.toFloatOrNull()
            val bo = query["b"]?.toFloatOrNull()
            when {
                svc == null -> jsonUnavailable()
                // 支持两种用法：单点 (x,y)，或矩形 (l,t,r,b)
                x == null && (l == null || t == null || r == null || bo == null) ->
                    jsonError("需要 (x,y) 或 (l,t,r,b)")

                else -> JSONObject().apply {
                    // 用无参构造 + set() 而不是 Rect(l,t,r,b)：
                    // 本工程的诊断器把四参构造解析成了别的 Rect 重载
                    // （报 "Too many arguments for 'Rect' (expected 2)"，
                    //  那是 androidx.compose.ui.geometry.Rect(offset,size)）。
                    // set() 的签名唯一，不会歧义。
                    val target = rectOf(
                        if (l != null) l.toInt() else x!!.toInt(),
                        if (t != null) t.toInt() else y!!.toInt(),
                        if (r != null) r.toInt() else x!!.toInt() + 1,
                        if (bo != null) bo.toInt() else y!!.toInt() + 1
                    )
                    val own = svc.ownWindowBounds()
                    val hit = svc.intersectsOwnWindow(target)
                    put("ok", true)
                    put("target", rectJson(target))
                    put("window", if (own == null) JSONObject.NULL else rectJson(own))
                    put("blocked", hit)
                    if (hit) {
                        put(
                            "hint",
                            "目标被本应用小窗遮住。先 /drag 把小窗挪到另一侧，" +
                                "或 /minimize 最小化，再 /check 复核后操作"
                        )
                    }
                }.toString()
            }
        }

        else -> jsonError("unknown endpoint: $path")
    }

    /** 把矩形转成 `{l,t,r,b,w,h}`，便于 agent 直接取用。 */
    private fun rectJson(r: Rect): JSONObject = JSONObject().apply {
        put("l", r.left)
        put("t", r.top)
        put("r", r.right)
        put("b", r.bottom)
        put("w", r.width())
        put("h", r.height())
    }

    /**
     * 构造屏幕矩形。
     *
     * 用无参构造 + `set()` 而不是四参构造 `Rect(l, t, r, b)`：
     * 本工程的诊断器在**没有显式 import** 时会把 `Rect` 解析成
     * `androidx.compose.ui.geometry.Rect(offset, size)`，报
     * "Too many arguments for 'Rect' (expected 2)" / "Unresolved reference: set"。
     *
     * 现在文件顶部已 `import android.graphics.Rect`，两种写法都能过；
     * 这里仍保留 `set()` 形式，因为它对参数个数不可能有歧义。
     */
    private fun rectOf(l: Int, t: Int, r: Int, b: Int): Rect =
        Rect().apply { set(l, t, r, b) }

    private fun limit(query: Map<String, String>): Int {
        val n = query["limit"]?.toIntOrNull() ?: 400
        return when {
            n <= 0 -> 400
            n > DshAccessibilityService.MAX_NODES -> DshAccessibilityService.MAX_NODES
            else -> n
        }
    }

    private fun statusJson(): String {
        val svc = DshAccessibilityService.instance
        // 两条截图路径，优先级不同：
        //  1) 无障碍截图（takeScreenshot）—— 只要服务开着就能用，无需额外授权；
        //  2) MediaProjection —— 需用户点系统弹窗、且进程重启即失效。
        // 前者是主路径，后者只在 API 30 以下或无障碍截图不可用时兜底。
        val a11yShot = svc != null && svc.canTakeScreenshotNow()
        val projection = ScreenCaptureState.active
        val canShot = a11yShot || projection
        // 先把 context 取出来再进 apply。
        //
        // 之前写成 `put(..., appContext ?: return@apply)`：只要 appContext 为空，
        // 就会从 apply 中途返回，后面那些字段（截图提示、无障碍原因）全被跳过，
        // 调用方拿到一个信息残缺的 JSON。而且这种「表达式里 return」极难看出问题。
        val ctx = appContext
        return JSONObject().apply {
            // ok 表示「至少有一项能力可用」。只开了截图没开无障碍时，
            // /screenshot 依然能工作，所以不能把 ok 绑死在无障碍上。
            put("ok", svc != null || projection)
            put("apiVersion", API_VERSION)
            put("service", if (svc != null) "connected" else "disconnected")
            put(
                "canGesture",
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
            )
            put("canScreenshot", canShot)
            // 明确告诉调用方走的是哪条路 —— 排查时这是第一个要看的信息
            put(
                "screenshotMode",
                when {
                    a11yShot -> "accessibility"
                    projection -> "projection"
                    else -> "none"
                }
            )
            if (ctx != null) {
                // 报可读路径（外部私有目录）：内部路径含 /data/user/0，
                // 读图工具在解析那一层就会 EACCES，进不到文件。
                put(
                    "screenshotPath",
                    ScreenCaptureState.readableScreenshotFile(ctx).absolutePath
                )
            }
            if (!canShot) {
                val hint = capabilityHint()
                put(
                    "screenshotHint",
                    hint.ifBlank {
                        if (svc == null) {
                            "无障碍服务未开启：请到 系统设置 → 无障碍 启用「FCL-DSH 屏幕操作」"
                        } else {
                            "无障碍服务缺少截图能力：请到 系统设置 → 无障碍 里" +
                                "关闭再开启一次「FCL-DSH 屏幕操作」"
                        }
                    }
                )
            }
            if (svc == null) {
                put(
                    "reason",
                    "无障碍服务未开启：请到 系统设置 → 无障碍 启用「FCL-DSH 屏幕操作」"
                )
            }
        }.toString()
    }

    /**
     * `/screenshot`：返回已落盘 PNG 的绝对路径。
     *
     * ## 为什么等一会儿
     *
     * VirtualDisplay 只在**画面变化**时推帧。刚授权完的第一帧可能还没到位，
     * 此时文件不存在。等最多 1 秒再返回，能让 agent 少一次「拿不到 → 重试」
     * 的往返。已经有帧时（绝大多数情况）立即返回，不做任何等待。
     *
     * ## 为什么给路径而不是内联图片
     *
     * 1080×2400 的 PNG 常有几百 KB，base64 内联进 JSON 会撑爆命令行输出，
     * agent 也不好处理。给绝对路径，它用自己的读图能力打开即可。
     */
    private fun screenshotJson(): String {
        val ctx = appContext ?: return jsonError("桥接尚未初始化")

        val svc = DshAccessibilityService.instance
        val a11yShot = svc != null && svc.canTakeScreenshotNow()

        // ── 首选：无障碍截图 ────────────────────────────────────────────
        //
        // 这条路只要无障碍服务开着就能用：没有授权弹窗、没有一次性凭证、
        // 进程重启后依然有效。文件过期或不存在时**现场截一帧**，
        // 而不是把「没有截图」直接甩给 agent 让它干等。
        if (a11yShot) {
            // 判活用**可读路径**（外部私有目录）。captureToFile 会同时写内部与外部
            // 两份，所以观察哪一份都能反映帧的新鲜度；但返回给 agent 的必须是
            // 外部那份 —— 内部路径含 /data/user/0，读图工具打不开。
            val file = ScreenCaptureState.readableScreenshotFile(ctx)
            val age = ScreenCaptureState.frameAgeMs()
            // 文件缺失或超过 1.5 秒没更新就现场抓一帧
            if (!file.isFile || age < 0 || age > 1500L) {
                DshAccessibilityService.captureNow()
            }
            if (file.isFile && file.length() > 0L) {
                return JSONObject().apply {
                    put("ok", true)
                    put("path", file.absolutePath)
                    put("bytes", file.length())
                    put("width", ScreenCaptureState.frameWidth)
                    put("height", ScreenCaptureState.frameHeight)
                    put("ageMs", ScreenCaptureState.frameAgeMs())
                    put("format", "png")
                    put("mode", "accessibility")
                }.toString()
            }
            // 现场截也失败了，如实报错误码，便于定位是节流还是没权限
            val err = DshAccessibilityService.lastError()
            return JSONObject().apply {
                put("ok", false)
                put("reason", "无障碍截图失败")
                put("errorCode", err)
                put(
                    "fix",
                    when (err) {
                        DshAccessibilityService.ERR_NO_ACCESS ->
                            "无障碍服务缺少截图能力：请让用户到 系统设置 → 无障碍 里" +
                                "关闭再开启一次「FCL-DSH 屏幕操作」"

                        DshAccessibilityService.ERR_INTERVAL_SHORT ->
                            "调用太频繁，等 500ms 再试"

                        else -> "请稍后重试；若持续失败，请让用户重新开关一次无障碍服务"
                    }
                )
            }.toString()
        }

        // ── 兜底：MediaProjection ───────────────────────────────────────
        if (!ScreenCaptureState.active) {
            return JSONObject().apply {
                put("ok", false)
                put("reason", "没有可用的截图通道")
                put(
                    "fix",
                    if (svc == null) {
                        "请让用户在 系统设置 → 无障碍 启用「FCL-DSH 屏幕操作」" +
                            "（开启后即可截图，无需其他授权）"
                    } else {
                        "无障碍服务缺少截图能力：请在系统设置里关闭再开启一次" +
                            "「FCL-DSH 屏幕操作」；或在 设置 → 权限设置 → 开启「屏幕截图」"
                    }
                )
                put("note", "不要改用 screencap / screenrecord 的原生实现，它们在本沙箱里必然失败")
            }.toString()
        }

        // MediaProjection 兜底路径：同样只返回**可读**的那一份。
        val file = ScreenCaptureState.readableScreenshotFile(ctx)
        // 还没有帧时稍等（首帧或屏幕长时间静止后的首帧）
        var waited = 0
        while (!file.isFile && waited < 1000) {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                break
            }
            waited += 50
        }

        if (!file.isFile || file.length() == 0L) {
            return JSONObject().apply {
                put("ok", false)
                put("reason", "尚未抓到画面")
                put("fix", "请让用户点亮屏幕并切换一次界面，然后重试")
            }.toString()
        }

        return JSONObject().apply {
            put("ok", true)
            put("path", file.absolutePath)
            put("bytes", file.length())
            put("width", ScreenCaptureState.frameWidth)
            put("height", ScreenCaptureState.frameHeight)
            // ageMs 让 agent 判断这一帧是不是刚操作完的旧画面
            put("ageMs", ScreenCaptureState.frameAgeMs())
            put("format", "png")
        }.toString()
    }

    private fun jsonOk(payload: JSONObject): String =
        JSONObject().apply {
            put("ok", true)
            // 把 payload 的字段平铺进来，调用方少一层嵌套
            val keys = payload.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                put(k, payload.get(k))
            }
        }.toString()

    private fun jsonAction(action: String, ok: Boolean): String =
        JSONObject().apply {
            put("ok", ok)
            put("action", action)
            if (!ok) put("reason", "操作未能下发（节点不存在或无手势权限）")
        }.toString()

    private fun jsonUnavailable(): String =
        JSONObject().apply {
            put("ok", false)
            put("reason", "无障碍服务未开启")
        }.toString()

    private fun jsonError(msg: String): String =
        JSONObject().apply {
            put("ok", false)
            put("error", msg)
        }.toString()

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    /** 解析 `a=1&b=2`，值做 URL 解码。 */
    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            val decoded = runCatching {
                URLDecoder.decode(v, "UTF-8")
            }.getOrDefault(v)
            if (k.isNotEmpty()) map[URLDecoder.decode(k, "UTF-8")] = decoded
        }
        return map
    }
}
