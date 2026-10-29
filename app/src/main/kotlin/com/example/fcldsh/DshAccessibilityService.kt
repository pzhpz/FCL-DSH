package com.example.fcldsh

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务：读取屏幕内容 + 模拟点击。
 *
 * ## 能力边界（与 res/xml/dsh_accessibility_config.xml 严格对应）
 *
 * - **读屏**：`rootInActiveWindow` 拿到当前窗口的节点树，遍历成结构化快照。
 *   依赖 `canRetrieveWindowContent=true`，否则 root 恒为 null。
 * - **点击**：两条路。
 *   1. 节点级 `performAction(ACTION_CLICK)` —— 优先用，它触发控件自己的点击逻辑，
 *      比按坐标戳更可靠，也不受控件被遮挡影响。
 *   2. 坐标级 `dispatchGesture` 点按 —— 节点点不动时（自绘控件、WebView 内部元素）
 *      的兜底，依赖 `canPerformGestures=true`，且要求 **API 24+**。
 * - **截图**：`takeScreenshot()`，依赖 `canTakeScreenshot=true`，要求 **API 30+**。
 *
 *   ★ 这是本项目一开始漏掉的一条路，也是「读屏拿不到画面」的真正解法 ★
 *
 *   之前只有 MediaProjection 一条路，而它**必须由用户点系统弹窗授权、且不能持久化**
 *   —— 进程一重启就失效，agent 每次会话都可能卡在「未授权」。而
 *   `AccessibilityService.takeScreenshot()` 只要无障碍服务本身是开着的就能直接截：
 *   没有额外授权弹窗，没有一次性凭证，跨进程重启依然可用。
 *
 *   注意系统对它有**节流**：连续调用会返回 `errorCode=3`(INTERVAL_TIME_SHORT)，
 *   所以这里做了几百毫秒的帧复用 + 重试退避（见 [captureBitmap]）。
 *
 * ## 为什么状态要放 Compose 可变状态
 *
 * 权限中心要显示「服务是否已连接」。系统绑定/解绑服务的时机完全不受本应用控制
 * （用户在系统设置里开关、系统在内存紧张时重启服务），只能靠服务自己的生命周期
 * 回调把状态写出来，UI 订阅它 —— 轮询静态变量既不准也白耗电。
 *
 * ## 为什么用 companion 而不是单例对象
 *
 * 这个类的实例由**系统**创建，本应用拿不到构造权。外部只能通过
 * [instance] 取到系统给的那一个；为 null 就说明没连上。
 */
class DshAccessibilityService : AccessibilityService() {

    // ── 截图相关状态 ────────────────────────────────────────────────
    //
    // 系统对 takeScreenshot 有节流：连续调用会返回 errorCode=3
    // (INTERVAL_TIME_SHORT)。所以：
    //  · 几百毫秒内重复请求直接复用上一帧（[SHOT_REUSE_MS]）
    //  · 失败时退避重试（[SHOT_RETRY_DELAY_MS] × [MAX_SHOT_ATTEMPTS]）

    /** 截图用后台线程：takeScreenshot 是异步回调，但要等 latch，不能占主线程。 */
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    /** 最近一帧的缓存，避免节流期内重复调用被系统拒绝。 */
    @Volatile
    private var cachedShot: Bitmap? = null

    @Volatile
    private var cachedShotAt: Long = 0L

    /** 最近一次失败的错误码（0 表示没失败）。供 /status 诊断用。 */
    @Volatile
    private var lastShotError: Int = 0

    /** 上一次「自动截帧」的时间，用于按窗口变化节流刷新。 */
    @Volatile
    private var lastAutoCaptureAt: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connectedState = true
        Log.i(TAG, "无障碍服务已连接")

        // 在这里启动桥接，而不是只靠 MainActivity：
        // 系统可以只为了绑定无障碍服务而重新拉起整个进程，那时 Activity
        // 还没创建。只从 Activity 启动会漏掉这种情况，表现为「服务开着但
        // agent 连不上端口」。start() 是幂等的。
        runCatching { A11yBridgeServer.start(applicationContext) }

        // 服务可能刚开始连接，桥接文件里的内容也随之更新
        Log.i(TAG, "无障碍桥接状态 running=${A11yBridgeServer.isRunning()}")

        // 截图线程 + 能力自检。
        //
        // 自检很重要：canTakeScreenshot 是**后来才加进 XML** 的能力，
        // 而系统只在服务被重新绑定时读取服务配置。用户升级 APK 后若没有
        // 手动关/开一次无障碍服务，capabilities 里不会有那一项，
        // takeScreenshot 就会一直返回 errorCode=2(NO_ACCESSIBILITY_ACCESS)。
        // 这里把结论直接写出来，省得再靠猜。
        runCatching {
            val t = HandlerThread("a11y-shot").apply { start() }
            captureThread = t
            captureHandler = Handler(t.looper)
        }.onFailure { Log.w(TAG, "截图线程启动失败: ${it.message}") }

        val cap = canTakeScreenshotNow()
        canScreenshotState = cap
        Log.i(TAG, "截图能力 canTakeScreenshot=$cap (API ${Build.VERSION.SDK_INT})")
        if (!cap && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Log.w(
                TAG,
                "系统已支持但没有截图能力 —— 请在系统设置里关闭再开启一次" +
                    "「FCL-DSH 屏幕操作」，让服务重新读取配置"
            )
            A11yBridgeServer.onCapabilityHintGot(
                "无障碍服务缺少截图能力：请到 系统设置 → 无障碍 里" +
                    "关闭再开启一次「FCL-DSH 屏幕操作」"
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 读屏靠主动遍历 rootInActiveWindow，不需要在这里累积事件。
        //
        // 但窗口切换时顺手刷一次截图文件：agent 侧习惯用 `screencap`（已被替身接管，
        // 读的就是这个文件），如果只在 HTTP 请求时才刷新，命令读到的是旧画面。
        // 按窗口变化刷新能让它「开一次新界面就有一张新图」。
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scheduleAutoCapture()
        }
    }

    override fun onInterrupt() {
        // 系统要求实现。我们的手势都是即发即忘，没有需要中断的进行中动作。
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        connectedState = false
        Log.i(TAG, "无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        connectedState = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // 读屏
    // ------------------------------------------------------------------

    /**
     * 把当前屏幕的节点树读成结构化数据。
     *
     * @param limit 最多收集多少个节点。深层嵌套列表（聊天记录）很容易上千个，
     *   全量序列化既慢又没必要 —— 调用方要的是「屏幕上有哪些可操作元素」。
     * @return JSON 字符串，结构见 [snapshot]。
     */
    fun readScreen(limit: Int = MAX_NODES): String =
        JSONObject().apply {
            put("package", rootInActiveWindow?.packageName?.toString().orEmpty())
            put("nodes", snapshot(limit))
        }.toString()

    /**
     * 节点快照。
     *
     * 每项字段：
     * - `text`  节点文字
     * - `desc`  无障碍描述（图标按钮通常只有这个）
     * - `cls`   控件类名
     * - `id`    viewId（需 flagReportViewIds）
     * - `b`     `[left, top, right, bottom]` 屏幕坐标
     * - `click` / `edit` / `scroll` / `focus`  布尔能力位
     *
     * 只保留「有文字或描述，或者可点击/可编辑/可滚动」的节点：
     * 布局容器既没内容也不可交互，全塞进去只会把有用的信息淹掉。
     */
    fun snapshot(limit: Int = MAX_NODES): JSONArray {
        val arr = JSONArray()
        val root = rootInActiveWindow ?: return arr

        // 显式队列而不是递归：极端深的树（WebView 里几百层 div）会把栈打爆
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && arr.length() < limit && visited < limit * 4) {
            val node = queue.removeFirst()
            visited++

            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable

            // 过滤掉纯布局容器
            if (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable || scrollable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                arr.put(
                    JSONObject().apply {
                        put("text", text)
                        put("desc", desc)
                        put("cls", node.className?.toString().orEmpty())
                        put("id", node.viewIdResourceName.orEmpty())
                        put("b", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                        put("click", clickable)
                        put("edit", editable)
                        put("scroll", scrollable)
                        put("focus", node.isFocused)
                    }
                )
            }

            for (i in 0 until node.childCount) {
                // getChild 对已失效节点会返回 null，必须判空
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return arr
    }

    /** 纯文本形式：每行一个节点的文字，便于直接喂给模型或写进日志。 */
    fun readScreenText(limit: Int = MAX_NODES): String {
        val sb = StringBuilder()
        val arr = snapshot(limit)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("text")
            val desc = o.optString("desc")
            val label = if (text.isNotEmpty()) text else desc
            if (label.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(label)
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    /**
     * 按文字（或无障碍描述）找到节点并点击。
     *
     * 优先节点级点击：控件自己的点击逻辑会处理「被遮挡」「延迟生效」等问题，
     * 而按坐标戳只是往那个位置送一次触摸，控件若在滚动容器里或有动画，很容易落空。
     *
     * @param exact true 要求完全相等；false 是包含匹配（默认，更宽容）
     * @return 是否成功点到了
     */
    fun clickByText(target: String, exact: Boolean = false): Boolean {
        if (target.isBlank()) return false
        val node = findNodeByText(target, exact) ?: return false

        // 找它自己或最近的、真正可点击的祖先。
        // 常见情况：文字节点的父容器才是 clickable 的。
        var clickable: AccessibilityNodeInfo? = node
        var depth = 0
        while (clickable != null && !clickable.isClickable && depth < 6) {
            clickable = clickable.parent
            depth++
        }
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // 节点点不动 → 退回按坐标戳它的中心
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        return tap(rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    /**
     * 按坐标点一下（屏幕绝对坐标，单位 px）。
     *
     * `dispatchGesture` 是 **API 24+**，minSdk 23 上必须版本守卫，
     * 否则直接 NoSuchMethodError。低版本返回 false，调用方应改用 [clickByText]。
     */
    fun tap(x: Float, y: Float): Boolean = gesture(Path().apply { moveTo(x, y) }, 0L)

    /** 长按（800ms，接近系统默认长按阈值）。 */
    fun longPress(x: Float, y: Float, durationMs: Long = 800L): Boolean =
        gesture(Path().apply { moveTo(x, y) }, durationMs)

    private fun gesture(path: Path, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "dispatchGesture 需要 API 24+，当前 ${Build.VERSION.SDK_INT}")
            return false
        }
        return runCatching {
            // 单点手势的时长不能为 0，否则部分 ROM 判定为无效手势；给 1ms 兜底
            val duration = if (durationMs <= 0L) 1L else durationMs
            val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
            dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                null,
                null
            )
        }.getOrDefault(false)
    }

    /** 按文字找节点。找不到返回 null。 */
    fun findNodeByText(target: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES * 4) {
            val node = queue.removeFirst()
            visited++
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val hit = if (exact) {
                text == target || desc == target
            } else {
                (text.isNotEmpty() && text.contains(target)) ||
                    (desc.isNotEmpty() && desc.contains(target))
            }
            if (hit) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 文字输入（ACTION_SET_TEXT：一次写入整段，不用逐字点按）
    // ------------------------------------------------------------------

    /**
     * 节点是否可写入文字。
     *
     * 两个判据都要看：`isEditable` 是标准标志，但部分自定义输入框
     * 只把类名设成 `EditText` 而没置该标志。
     */
    private fun isEditableNode(n: AccessibilityNodeInfo): Boolean = runCatching {
        n.isEditable || n.className?.toString() == "android.widget.EditText"
    }.getOrDefault(false)

    /**
     * 找一个可写入文字的输入框。
     *
     * ## 为什么先看「输入焦点」
     *
     * 用户/AI 通常已经点过输入框（焦点在它身上），这时直接用它最准 ——
     * 页面上可能有多个 EditText（搜索框、评论框、标题栏搜索），
     * 按 `target` 猜容易写错地方。
     *
     * ## target 的匹配范围
     *
     * 依次比对 `text` / `contentDescription` / `hintText`（API 26+）。
     * 只支持这三种，不做「找旁边的 label」那种猜测 —— 猜错会把文字写进
     * 不该写的框里，比失败更糟。
     *
     * @param target 输入框的提示文字/描述片段；null 或空表示「任取一个可编辑框」
     */
    fun findEditableNode(target: String? = null): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val hint = target?.trim().orEmpty()

        // 1) 优先：当前持有输入焦点的可编辑节点
        val focused = runCatching {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }.getOrNull()
        if (focused != null && isEditableNode(focused)) {
            if (hint.isEmpty() || matchesTarget(focused, hint)) return focused
        }

        // 2) 广度优先遍历；找到第一个匹配的，同时记住第一个可编辑的作兜底
        var fallback: AccessibilityNodeInfo? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES * 4) {
            val n = queue.removeFirst()
            visited++
            if (isEditableNode(n)) {
                if (hint.isEmpty()) return n
                if (matchesTarget(n, hint)) return n
                if (fallback == null) fallback = n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        // 指定了 target 却没命中时**不回落到别的输入框** —— 宁可失败，
        // 也不要把内容写进用户没指定的地方。
        return if (hint.isEmpty()) fallback else null
    }

    /** 节点的文本/描述/提示是否包含 target（忽略大小写）。 */
    private fun matchesTarget(n: AccessibilityNodeInfo, hint: String): Boolean {
        fun hit(s: String?) = !s.isNullOrBlank() && s.contains(hint, ignoreCase = true)
        if (hit(n.text?.toString())) return true
        if (hit(n.contentDescription?.toString())) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            hit(runCatching { n.hintText?.toString() }.getOrNull())
        ) return true
        return false
    }

    /**
     * 往输入框写文字。
     *
     * ## 为什么用 `ACTION_SET_TEXT` 而不是逐字点按键
     *
     * `dispatchGesture` 逐字点击软键盘有三大问题：
     * 1. 每个字符一次手势，一句话要几十次 IPC；
     * 2. 键位随输入法布局、语言、大小写状态变化，坐标极难算；
     * 3. 中文/emoji 根本无法用按键输入。
     *
     * `ACTION_SET_TEXT` 是系统提供的「直接设置输入框内容」动作，一次调用整段写入，
     * 与输入法无关，也不会触发软键盘弹跳。参考项目的 `POST /input` 走的同一条路。
     *
     * ## 已知限制
     *
     * 部分 WebView 内的输入框会拒绝这个动作（返回 false）。那种情况下
     * 需要退回「先聚焦再逐字输入」或让用户手动输入 —— 调用方据返回值决定，
     * 这里不做静默降级（静默降级会让 agent 以为已经写进去了）。
     *
     * @param text   要写入的内容；空串表示清空
     * @param target 用来定位输入框的提示文字
     * @param append true 则在原有内容后追加，否则整体替换
     * @return 是否写入成功
     */
    fun inputText(
        text: String,
        target: String? = null,
        append: Boolean = false
    ): Boolean {
        val node = findEditableNode(target) ?: run {
            Log.i(TAG_INPUT, "input: 找不到可写入的输入框 target=$target")
            return false
        }

        val old = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
        val finalText = if (append) old + text else text

        // 先请求焦点：部分输入框未聚焦时 ACTION_SET_TEXT 会被忽略
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                finalText
            )
        }
        val ok = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)

        Log.i(
            TAG_INPUT,
            "input target=$target append=$append oldLen=${old.length} " +
                "newLen=${finalText.length} ok=$ok"
        )
        if (!ok) {
            Log.w(
                TAG_INPUT,
                "input: ACTION_SET_TEXT 被拒 —— 该输入框可能不支持直接写入" +
                    "（部分 WebView / 自定义控件），需退回聚焦后逐字输入或由用户手动输入"
            )
        }
        return ok
    }

    /** 清空输入框。 */
    fun clearInput(target: String? = null): Boolean = inputText("", target, append = false)

    /** 返回键（节点级点击够不到系统栏时有用）。 */
    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** 回到桌面。 */
    fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    // ------------------------------------------------------------------
    // 小窗（自由窗口）支持：读范围 + 拖动避让 + 最小化手势
    // ------------------------------------------------------------------
    //
    // 设计前提（重要）：**本应用不会自己进入小窗**。
    // Android 没有让应用主动把自身缩成自由窗口的公共 API
    // （WINDOWING_MODE_FREEFORM 是 @SystemApi）。所以小窗由用户手工摆好，
    // 这里只做三件在无障碍能力范围内的事：
    //   1. 读出本应用窗口当前占用的屏幕范围；
    //   2. 把窗口拖到别处（全局滑动），避开要操作的目标；
    //   3. 用固定手势把过大的窗口最小化。
    //
    // 所有日志统一以 [dsh-window] 开头，出问题时凭日志定位。

    /**
     * 读本应用自己的窗口在屏幕上的范围。
     *
     * ## 为什么按包名匹配而不是取「当前活动窗口」
     *
     * 用户把小窗摆好后，小窗通常**不是**活动窗口（焦点在目标应用上）。
     * 只有遍历 `windows` 并挑出 root 包名等于本应用的窗口，才能拿到小窗的位置。
     *
     * @return 屏幕坐标矩形；找不到返回 null。
     */
    fun ownWindowBounds(): Rect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
        val list = runCatching { windows }.getOrNull() ?: return null
        val pkg = packageName
        for (w in list) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            val rootPkg = runCatching { root.packageName?.toString() }.getOrNull()
            if (rootPkg != pkg) continue
            val rect = Rect()
            w.getBoundsInScreen(rect)
            if (rect.isEmpty) continue
            // 记一份历史位置：悬浮球状态下窗口不可见，恢复时只能靠它。
            lastKnownBounds = rect
            Log.i(TAG_WINDOW, "own window bounds=$rect type=${w.type}")
            return rect
        }
        Log.i(TAG_WINDOW, "own window not found (windows=${list.size})")
        return null
    }

    /** 屏幕尺寸（用窗口管理器，避免依赖 Display 已废弃的重载）。 */
    fun screenSize(): android.graphics.Point {
        val dm = resources.displayMetrics
        return android.graphics.Point(dm.widthPixels, dm.heightPixels)
    }

    /**
     * 通用滑动。`dispatchGesture` 需要 API 24+。
     *
     * @param durationMs 滑动时长。太短会被系统当「快速甩动」，
     *   拖动窗口时要给足（默认 600ms），否则窗口可能不动或只微移。
     */
    fun swipe(
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 600L
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG_WINDOW, "swipe 需要 API 24+，当前 ${Build.VERSION.SDK_INT}")
            return false
        }
        return runCatching {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val d = if (durationMs <= 0L) 600L else durationMs
            val stroke = GestureDescription.StrokeDescription(path, 0L, d)
            val ok = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(), null, null
            )
            Log.i(TAG_WINDOW, "swipe ($x1,$y1)->($x2,$y2) ${d}ms dispatched=$ok")
            ok
        }.getOrElse {
            Log.w(TAG_WINDOW, "swipe failed: ${it.message}")
            false
        }
    }

    /**
     * 把本应用窗口拖到指定位置。
     *
     * ## ★ 抓取点必须是「顶部中央」★
     *
     * 实测：按窗口左上角或顶部偏左起手**完全无效**（窗口不动），
     * 只有按住**顶部中央**才被系统认作拖动把手。所以：
     * ```
     * grabX = bounds.centerX()
     * grabY = bounds.top + inset
     * ```
     * `inset` 取窗口高度的 6%、上限 28px —— 落在标题栏内、又不至于压到
     * 标题栏下方的内容区。
     *
     * @param dx 水平位移（正=右）
     * @param dy 垂直位移（正=下）
     */
    fun dragOwnWindowBy(dx: Float, dy: Float): Boolean {
        val b = ownWindowBounds() ?: run {
            Log.w(TAG_WINDOW, "drag: 窗口未知，跳过")
            return false
        }
        val grabX = b.centerX().toFloat()
        val grabY = b.top + minOf(b.height() * 0.06f, 28f)
        // 位移后仍要落在屏幕内，否则窗口会被甩到边缘外（部分 ROM 会直接失败）
        val screen = screenSize()
        val endX = (grabX + dx).coerceIn(8f, screen.x - 8f)
        val endY = (grabY + dy).coerceIn(8f, screen.y - 8f)
        Log.i(TAG_WINDOW, "drag(top-center) bounds=$b grab=($grabX,$grabY) -> ($endX,$endY)")
        return swipe(grabX, grabY, endX, endY, 700L)
    }

    /**
     * 本应用窗口当前的形态。
     *
     * ## 三档（实测结论）
     *
     * | 形态 | 表现 | 对界面遮挡 |
     * |---|---|---|
     * | [WindowMode.LARGE] | 可调大的窗口，有最小尺寸下限 | 大 |
     * | [WindowMode.SMALL] | 小窗，略高于下限 | 最小 |
     * | [WindowMode.BALL] | 悬浮球：窗口不可见，退化为一个图标 | 无（但界面也看不见） |
     *
     * 继续往小调会依次经过这三档；点悬浮球图标可回到大窗。
     *
     * ## 判定依据
     *
     * 以「窗口面积 / 屏幕面积」划分。阈值是经验值：
     * 大窗通常占屏 50% 以上，小窗在 10%~50% 之间。
     * 拿不到窗口时返回 [WindowMode.UNKNOWN] ——
     * 这既可能是悬浮球（窗口已隐藏），也可能是应用被切到后台，
     * **两者无法从无障碍侧区分**，所以不猜，由调用方决定下一步。
     */
    enum class WindowMode(val label: String) {
        LARGE("large"),
        SMALL("small"),
        BALL("ball"),
        UNKNOWN("unknown")
    }

    /** 按尺寸归类窗口形态。 */
    fun currentWindowMode(): WindowMode {
        val b = ownWindowBounds()
        if (b == null) {
            Log.i(TAG_WINDOW, "mode=unknown（无窗口：悬浮球或已切后台）")
            return WindowMode.UNKNOWN
        }
        val screen = screenSize()
        val screenArea = (screen.x.toLong() * screen.y.toLong()).coerceAtLeast(1L)
        val ratio = (b.width().toLong() * b.height().toLong()).toDouble() / screenArea
        val mode = when {
            ratio >= 0.45 -> WindowMode.LARGE
            ratio >= 0.06 -> WindowMode.SMALL
            else -> WindowMode.BALL
        }
        Log.i(TAG_WINDOW, "mode=$mode ratio=$ratio bounds=$b screen=$screen")
        return mode
    }

    /**
     * 缩小一档：**按住左下角边缘（略靠外）向右上划**。
     *
     * ## ★ 起手点要在窗口边界**外侧** ★
     *
     * 实测：窗口的可拖动边框并不落在 `getBoundsInScreen` 返回的矩形内，
     * 而是贴着矩形**外侧**一点。按内侧 24px 起手会落进内容区，
     * 结果变成滚动页面而不是缩放窗口。
     *
     * 所以起手点取 `left - edgeBias`、`bottom + edgeBias`，
     * 即左下角**外**一点点（默认 6px，再钳制在屏幕内）。
     *
     * ## 为什么幅度给 0.55
     *
     * 幅度太小会被判成点击，太大容易滑出屏幕。0.55 倍窗口宽高
     * 足以跨越一档的下限阈值。
     *
     * @param scale 手势幅度占窗口尺寸的比例。
     */
    fun shrinkWindow(scale: Float = 0.55f): Boolean {
        val b = ownWindowBounds() ?: run {
            Log.w(TAG_WINDOW, "shrink: 窗口未知，无法计算起手点")
            return false
        }
        val screen = screenSize()
        val edgeBias = 6f
        // 左下角外侧一点点；钳制在屏幕内，避免被系统丢弃
        val startX = (b.left - edgeBias).coerceIn(1f, screen.x - 1f)
        val startY = (b.bottom + edgeBias).coerceIn(1f, screen.y - 1f)
        val endX = (startX + b.width() * scale).coerceIn(1f, screen.x - 1f)
        val endY = (startY - b.height() * scale).coerceIn(1f, screen.y - 1f)
        Log.i(
            TAG_WINDOW,
            "shrink bounds=$b start=($startX,$startY) end=($endX,$endY) scale=$scale"
        )
        return swipe(startX, startY, endX, endY, 500L)
    }

    /**
     * 兼容旧名：上一版把「缩小一档」叫 minimize。
     *
     * 保留是为了不破坏已经写进 AGENTS.md 的 `a11y minimize` 用法，
     * 但语义已修正为「缩一档」，并修正了起手点在窗口外侧。
     */
    fun minimizeOwnWindow(scale: Float = 0.55f): Boolean = shrinkWindow(scale)

    /**
     * 从悬浮球/小窗恢复：点一下窗口最后已知位置的中心。
     *
     * ## 这只是启发式，不保证成功
     *
     * 悬浮球是一个**系统绘制的图标**，它的位置不会出现在本应用的
     * `AccessibilityWindowInfo` 里（窗口已隐藏），所以拿不到确切坐标。
     * 只能退而用最后一次读到的窗口中心去点。
     *
     * 因此：**调用方必须复核结果**（点完再读 `currentWindowMode()`），
     * 失败就明确告诉用户「请手动点一下悬浮球」，不要重复盲点。
     *
     * @param fallback 已知的窗口位置；null 时用内部缓存的上一次位置。
     */
    fun restoreFromBall(fallback: Rect? = null): Boolean {
        val target = fallback ?: lastKnownBounds
        if (target == null) {
            Log.w(TAG_WINDOW, "restore: 没有已知窗口位置，无法点悬浮球")
            return false
        }
        val x = target.centerX().toFloat()
        val y = target.centerY().toFloat()
        Log.i(TAG_WINDOW, "restore: 点最后已知位置中心 ($x,$y) bounds=$target")
        return tap(x, y)
    }

    /**
     * 最近一次成功读到的窗口范围。
     *
     * 用途只有一个：悬浮球状态下窗口已不可见，`ownWindowBounds()` 返回 null，
     * 想「点一下恢复」就只剩这个历史位置可用。
     */
    @Volatile
    var lastKnownBounds: Rect? = null
        private set

    /**
     * 目标矩形是否与本应用窗口相交（含边缘接触）。
     *
     * 判定留 2px 容差：窗口边框通常有 1~2px 的阴影/描边，
     * 严格不相交也可能在视觉上压住目标边缘。
     */
    fun intersectsOwnWindow(target: Rect, tolerance: Int = 2): Boolean {
        val own = ownWindowBounds() ?: return false
        val shrunk = Rect(target)
        shrunk.inset(-tolerance, -tolerance)
        val hit = Rect.intersects(own, shrunk)
        Log.i(TAG_WINDOW, "intersect own=$own target=$target hit=$hit")
        return hit
    }

    // ------------------------------------------------------------------
    // 截图（AccessibilityService.takeScreenshot，API 30+）
    // ------------------------------------------------------------------

    /**
     * 系统是否给了本服务截图能力。
     *
     * `canTakeScreenshot` 是后加进 XML 的能力，而系统**只在服务被重新绑定时**
     * 读取服务配置。所以升级 APK 后如果用户没有手动关/开一次无障碍服务，
     * capabilities 里不会有那一项，`takeScreenshot()` 会一直返回
     * `errorCode=2`(NO_ACCESSIBILITY_ACCESS)。这个判断就是用来区分
     * 「系统不支持」和「用户需要重新开关一次」的。
     */
    fun canTakeScreenshotNow(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val caps = runCatching { serviceInfo?.capabilities ?: 0 }.getOrDefault(0)
        return (caps and android.accessibilityservice.AccessibilityServiceInfo
            .CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
    }

    /**
     * 抓一帧屏幕，返回软件位图；失败返回 null。
     *
     * ## 为什么要缓存几百毫秒
     *
     * 系统对 takeScreenshot 有节流：两次调用间隔太短会返回
     * `errorCode=3`(INTERVAL_TIME_SHORT)。而 agent 常常连着调
     * （先 `screencap` 再 `/screenshot`），每次都真抓会被拒。所以
     * [SHOT_REUSE_MS] 内直接返回上一帧 —— 几百毫秒内画面几乎不会变。
     *
     * ## 为什么必须 copy
     *
     * `Bitmap.wrapHardwareBuffer()` 得到的是 Hardware 位图，底层直接引用
     * `hardwareBuffer` 的内存。一旦 `close()` 那个 buffer，位图就悬空了。
     * 所以这里立刻 `copy(ARGB_8888)` 成软件位图，再释放 buffer。
     *
     * ## 为什么用 CountDownLatch
     *
     * `takeScreenshot` 是**异步回调**，但调用方（HTTP 线程 / shim 落盘）
     * 需要同步拿到结果。latch + 超时是最直接的桥接方式；超时用 2 秒，
     * 因为系统在极少数情况下会既不回调 onSuccess 也不回调 onFailure。
     */
    fun captureBitmap(allowCache: Boolean = true): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val now = android.os.SystemClock.elapsedRealtime()
        val cached = cachedShot
        if (allowCache && cached != null && !cached.isRecycled &&
            now - cachedShotAt < SHOT_REUSE_MS
        ) {
            return cached
        }

        if (!canTakeScreenshotNow()) {
            lastShotError = ERR_NO_ACCESS
            return null
        }

        return runCatching {
            val latch = CountDownLatch(1)
            var result: Bitmap? = null

            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                // 用截图线程的 executor；传 null 会走主线程，而我们要阻塞等待，
                // 在主线程上等自己必然超时。
                { r -> captureHandler?.post(r) ?: r.run() },
                object : TakeScreenshotCallback {
                    override fun onSuccess(shot: ScreenshotResult) {
                        try {
                            val hw = shot.hardwareBuffer
                            result = Bitmap.wrapHardwareBuffer(hw, shot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            // 必须关，否则图形内存很快耗尽
                            runCatching { hw.close() }
                        } catch (e: Exception) {
                            Log.w(TAG, "截图转位图失败: ${e.message}")
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        lastShotError = errorCode
                        Log.w(TAG, "takeScreenshot 失败 errorCode=$errorCode")
                        latch.countDown()
                    }
                }
            )

            // 阻塞等待回调；最多 2 秒（系统偶尔会两边都不回调）
            val done = latch.await(2, TimeUnit.SECONDS)
            if (!done) {
                Log.w(TAG, "takeScreenshot 超时未回调")
                return@runCatching null
            }
            val bmp = result
            if (bmp != null) {
                lastShotError = 0
                val old = cachedShot
                cachedShot = bmp
                cachedShotAt = android.os.SystemClock.elapsedRealtime()
                // 旧缓存没人用就回收 —— 1080×2400 一张 10MB，不回收很快 OOM
                if (old != null && old !== bmp && !old.isRecycled) {
                    runCatching { old.recycle() }
                }
            }
            bmp
        }.getOrElse {
            Log.w(TAG, "截图异常: ${it.message}")
            null
        }
    }

    /**
     * 抓一帧并落盘，供 `screencap` 替身与 `/screenshot` 读取。
     *
     * 落盘交给 [ScreenCaptureState.writeFrame]：它会把同一帧写到
     * **内部路径**（`~/screenshot.png`，给 shim 与 `cat` 用）和
     * **外部私有路径**（`/storage/emulated/0/Android/data/<pkg>/files/screenshot.png`，
     * 给读图工具用）。后者是必须的 —— 读图工具在解析
     * `/data/user/0` 那一层就 EACCES，根本进不到文件。
     *
     * @return 是否至少成功写入一个位置
     */
    fun captureToFile(): Boolean {
        val bmp = captureBitmap() ?: return false
        return ScreenCaptureState.writeFrame(applicationContext, bmp) != null
    }

    /**
     * 窗口切换时顺手刷新截图文件。
     *
     * agent 侧习惯用 `screencap`（已被替身接管，读的就是这个文件）。
     * 如果只在 HTTP 请求时才刷新，那条命令读到的是旧画面 ——
     * 按窗口变化刷新才能让它「开一次新界面就有一张新图」。
     *
     * 节流到 [AUTO_CAPTURE_MS]：切窗口很频繁，每次都真抓会撞上系统节流。
     */
    private fun scheduleAutoCapture() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAutoCaptureAt < AUTO_CAPTURE_MS) return
        lastAutoCaptureAt = now
        // 延迟一点再抓：窗口刚切换时画面常常还在动画中，抓到过渡帧没用
        captureHandler?.postDelayed({
            runCatching { captureToFile() }
        }, 250L)
    }

    /** 桥接层要求现场抓一帧时用（例如 `/screenshot` 发现文件过期）。 */
    fun captureNow(): Boolean = captureToFile()

    companion object {
        private const val TAG = "DshA11y"

        /**
         * 小窗相关日志统一用这个 tag。
         *
         * 需求要求所有窗口操作日志带 `[dsh-window]` 前缀便于现场定位。
         * 这里用 Log.i(TAG_WINDOW, ...) 输出到 logcat；同时桥接层的
         * `/minimize`、`/swipe` 返回值里也带 `log` 字段，
         * agent 拿不到 logcat 时仍能看到决策依据。
         */
        private const val TAG_WINDOW = "dsh-window"

        /**
         * 文字输入相关日志的 tag。
         *
         * 与 [TAG_WINDOW] 分开：输入失败（`ACTION_SET_TEXT` 被 WebView 拒绝）
         * 是最需要现场定位的一类问题，单独 tag 便于 `logcat -s dsh-input` 过滤。
         */
        private const val TAG_INPUT = "dsh-input"

        /** 一次快照最多收集的节点数。 */
        const val MAX_NODES = 400

        /** 截图错误码：服务没有截图权限（通常是没重新开关过无障碍服务）。 */
        const val ERR_NO_ACCESS = 2

        /** 截图错误码：调用太频繁，被系统节流。 */
        const val ERR_INTERVAL_SHORT = 3

        /** 这个时间内的重复截图请求直接复用上一帧（避开系统节流）。 */
        private const val SHOT_REUSE_MS = 400L

        /** 窗口切换后自动刷新的最小间隔。 */
        private const val AUTO_CAPTURE_MS = 800L

        /**
         * 系统绑定的那个实例；null 表示服务没连上（未开启或被系统回收）。
         *
         * `@Volatile`：系统在主线程写、调用方可能在后台线程读。
         */
        @Volatile
        var instance: DshAccessibilityService? = null
            private set

        /**
         * 连接状态的 Compose 可观察版本。
         *
         * 存 `mutableStateOf` 而不是让 UI 去读 [instance]：普通字段的变化不会触发重组，
         * 用户在系统设置里开关无障碍后回到应用，界面会停在旧状态。
         */
        private var connectedState by mutableStateOf(false)

        /** 是否已连接。读它会订阅重组。 */
        val connected: Boolean get() = connectedState

        /**
         * 无障碍截图能力是否可用（`canTakeScreenshot`）。
         *
         * 与 [connected] 分开：服务可能连着但**没有**截图能力 ——
         * 那通常是用户升级 APK 后没有重新开关过无障碍服务，
         * 系统还按旧配置给的 capabilities。UI 与 `/status` 都要能区分这两种状态，
         * 否则会出现「无障碍已开启、但截图一直失败」这种无从下手的情况。
         */
        private var canScreenshotState by mutableStateOf(false)

        /** 读它会订阅重组。 */
        val canScreenshot: Boolean get() = canScreenshotState

        /** 服务是否可用（实例还在）。 */
        fun isRunning(): Boolean = instance != null

        /**
         * 现场抓一帧并落盘，返回是否成功。
         *
         * 给 [A11yBridgeServer] 的 `/screenshot` 用：文件过期/不存在时
         * 主动截一次，而不是把「没有截图」直接甩给 agent。
         */
        fun captureNow(): Boolean {
            val svc = instance ?: return false
            val ok = svc.captureNow()
            canScreenshotState = ok || svc.canTakeScreenshotNow()
            return ok
        }

        /** 最近一次截图失败的错误码；0 表示没有失败。供诊断用。 */
        fun lastError(): Int = instance?.lastShotError ?: 0
    }
}
