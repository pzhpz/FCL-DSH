package com.example.fcldsh

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.*
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.abs

/**
 * 网页运行窗口（仿 FCL 的独立全屏窗口）。
 *
 * ## 为什么单独开一个 Activity
 *
 * FCL 的交互是：启动器里点「启动」→ 弹出独立的全屏游戏窗口，启动器退到后台；
 * 退出窗口才回到启动器。dsh 也一样 —— 网页界面需要整块屏幕，
 * 和左侧导航栏挤在一起会两边都局促（dsh 自己还有左侧会话列表）。
 *
 * 分成两个 Activity 后：
 * - 本窗口：纯 WebView，全屏沉浸式，无侧栏干扰；
 * - [MainActivity]：启动器，负责实例/日志/设置，不加载网页。
 *
 * ## 全屏沉浸
 *
 * 隐藏状态栏与导航栏，并允许内容延伸到刘海区。用户从屏幕边缘划入时
 * 系统栏会临时浮现（`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`），
 * 松手后自动隐藏 —— 这是游戏/全屏应用的标准行为。
 */
class DshWebActivity : ComponentActivity() {

    companion object {
        const val TAG = "DSH_WebView"
        const val EXTRA_URL = "extra_url"

        /**
         * 进程级标志：只在进程内第一次创建 WebView 时清缓存/Cookie。
         * 放在 companion 而不是实例字段，避免窗口重建时把登录态清掉。
         */
        @Volatile
        var cookiesCleared = false

        /**
         * 单个挑选文件的大小上限（64 MB）。
         *
         * 复制进 cache 会实打实占存储，而超大文件上传本身也容易超时。
         * 拒绝时给出明确日志，而不是让页面一直转圈。
         */
        private const val MAX_PICK_BYTES = 64L * 1024 * 1024

        /**
         * 关闭键静置时的透明度。
         *
         * 0.35 是「看得见入口、但又几乎不干扰阅读」的折中：
         * 再低会让人找不到怎么退出，再高就回到原来遮挡观感的问题。
         * 按下时立刻变到 1.0，明确反馈「抓到了」。
         */
        private const val EXIT_IDLE_ALPHA = 0.35f
    }

    private lateinit var webView: WebView

    /**
     * 本次窗口的缩放百分比（50..200）。
     *
     * 在 [onCreate] 里从偏好读一次并缓存：`applyViewport()` 会在**每次**
     * onPageFinished 时被调用，每次都去读 SharedPreferences 既浪费也容易
     * 在一次会话中途读到不一致的值。
     */
    private var zoomPercent = 100

    /**
     * 应用「设置 → dsh 窗口」里的方向与缩放偏好。
     *
     * ## 为什么用 setRequestedOrientation 而不是改 Manifest
     *
     * Manifest 里锁的是 `fullSensor`（窗口内允许四向旋转）。用户的偏好是
     * **运行时**选择，只能靠这个 API 覆盖。Activity 的 `configChanges`
     * 已包含 `orientation|screenSize`，所以切换方向**不会重建 Activity** ——
     * 页面、WebView、登录态都保得住。
     *
     * ## 为什么「强制」用 SENSOR_ 系列而不是 LANDSCAPE/PORTRAIT
     *
     * `SENSOR_LANDSCAPE` 允许在左右两个横向之间翻转（手机横拿左右手都顺手），
     * 同时始终锁定在横向轴上；`SENSOR_PORTRAIT` 同理。直接用
     * `LANDSCAPE`/`PORTRAIT` 会把方向钉死成一个，翻过来就变成倒置显示。
     */
    private fun applyWindowSettings() {
        val mode = LauncherPrefs.getDshScreenMode(this)
        zoomPercent = LauncherPrefs.getDshZoom(this)

        val orientation = when (mode) {
            DshScreenMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            DshScreenMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            DshScreenMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        // 少数 ROM 会拒绝某些组合（如分屏下强制横屏），失败只记日志，
        // 不能让「设置没生效」演变成「窗口打不开」。
        runCatching { requestedOrientation = orientation }.onFailure {
            AppState.launcher.log("WEB 应用屏幕方向失败: ${it.message}")
        }
        Log.d(TAG, "window settings mode=${mode.name} zoom=$zoomPercent%")
    }

    /**
     * 网页 `<input type="file">` 的选择结果回调。
     *
     * WebView 收到文件选择请求时**不会自己弹选择器**，必须由宿主实现
     * `onShowFileChooser` 并在选完后通过 `onReceiveValue()` 回传；
     * 不回传的话页面会一直等待，表现为「点了上传没反应」。
     */
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    /**
     * 拍照上传时我们预先创建的 FileProvider URI。
     *
     * `ACTION_IMAGE_CAPTURE` 不通过返回值给图，而是把图写进
     * `EXTRA_OUTPUT` 指定的 URI 里。所以这条路径下「结果 URI」是
     * 我们自己生成的，不来自 `result.data`，需要单独记住。
     */
    private var pendingCaptureUri: Uri? = null

    /**
     * 当前页面是否来自 dsh 本地服务，用于给 JS 桥接放行。
     *
     * 无障碍桥接能读屏、能代替用户点击，绝不能对任意页面开放：
     * `addJavascriptInterface` 注入的对象在**所有 frame** 里都可见，
     * 页面若加载了第三方 iframe 或发生 XSS，就等于把读屏能力交出去了。
     */
    @Volatile
    private var trustedOrigin = false

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = pendingFileCallback
            val capture = pendingCaptureUri
            pendingFileCallback = null
            pendingCaptureUri = null
            if (cb == null) return@registerForActivityResult

            // 拍照路径：只要拍成功，图就已经在 capture URI 指向的文件里了
            if (capture != null) {
                val ok = result.resultCode == RESULT_OK &&
                    runCatching { contentResolver.openInputStream(capture) != null }
                        .getOrDefault(false)
                cb.onReceiveValue(if (ok) arrayOf(capture) else null)
                return@registerForActivityResult
            }

            if (result.resultCode != RESULT_OK) {
                // 用户取消：必须回传 null 释放回调，否则该 input 之后再也点不动
                cb.onReceiveValue(null)
                return@registerForActivityResult
            }
            val data = result.data
            val picked: Array<Uri> = when {
                data?.clipData != null -> {
                    val cd = data.clipData!!
                    Array(cd.itemCount) { cd.getItemAt(it).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> emptyArray()
            }
            if (picked.isEmpty()) {
                cb.onReceiveValue(null)
                return@registerForActivityResult
            }
            // ★ 关键：复制进本应用 cache，换成自己的 FileProvider URI ★
            //
            // 直接把系统选择器返回的 content:// 交给 WebView 是「上传图片失败」
            // 的主要原因：那个 URI 属于相册/文档提供者，读权限只授予了本应用的
            // 主进程，而 WebView 的渲染进程是独立沙箱进程，读不到 →
            // 页面拿到的 File 是空的，上传卡住或传上去 0 字节。
            cb.onReceiveValue(materializeForWebView(picked))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 允许内容延伸到刘海/挖孔区域，否则横屏时左侧会空一条
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // 必须先于 setupWebView()：它要读 zoomPercent 去设 setInitialScale。
        // 方向设置也在里面一并生效（configChanges 已声明，不会重建 Activity）。
        applyWindowSettings()

        val root = FrameLayout(this)
        webView = WebView(this)
        setupWebView()
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // 关闭键：可拖动、静置半透明，避开 dsh 页面右上角的控件。
        // 用 TOP|START + 左右边距自由定位 —— END 对齐下改 leftMargin 不会移动，
        // 那样就没法拖了。
        val exitBtn = makeExitButton(root)
        root.addView(
            exitBtn,
            FrameLayout.LayoutParams(
                (40 * resources.displayMetrics.density).toInt(),
                (40 * resources.displayMetrics.density).toInt()
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val m = (10 * resources.displayMetrics.density).toInt()
                setMargins(m, m, m, m)
            }
        )
        // root 此刻还没测量，拿不到可用宽高 —— 等布局完成再按保存的比例落位
        root.post { placeExitButton(root, exitBtn) }
        // 转屏/分屏会改变可用尺寸：只在尺寸真的变了时重排，
        // 否则每次 WebView 内部布局都会把用户刚拖好的位置重置一遍。
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            val sizeChanged = (r - l) != (orr - ol) || (b - t) != (ob - ot)
            if (sizeChanged) placeExitButton(root, exitBtn)
        }
        setContentView(root)

        // 清掉上次运行遗留的挑选临时文件（选图时复制的副本会一直占 cache）
        cleanPickerCache()

        AppState.attachWebActivity(this)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            AppState.launcher.log("❌ 网页窗口缺少 URL")
            finish()
            return
        }
        loadWhenSized(url)
    }

    override fun onDestroy() {
        AppState.detachWebActivity(this)
        // 真正销毁 WebView，避免多次开关窗口后累积多个带硬件层的实例，
        // 合成表面耗尽会导致后续窗口整片白屏。
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
        super.onDestroy()
    }

    /**
     * 网页能后退就后退，退无可退才关窗口回到启动器。
     *
     * ⚠️ 这里**故意**继续用 `onBackPressed()` 而不是
     * `OnBackPressedDispatcher`：本 Activity 被系统以
     * `singleInstance` 方式拉起，返回键语义要与 WebView 历史严格绑定，
     * 而 Compose 侧的 `BackHandler` 只对 Compose 内容生效，
     * 覆盖不到 WebView 自己的历史栈。等确认迁移不影响手势返回再换。
     *
     * 两个 suppression 缺一不可：
     * - `DEPRECATION`：`super.onBackPressed()` 本身已过时；
     * - `OVERRIDE_DEPRECATION`：重写一个已过时的成员，编译器单独提示
     *   「重写者自身没有标注 @Deprecated」。
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 系统栏被意外的焦点变化/弹窗唤出后，重新收起
        if (hasFocus) enterImmersive()
    }

    /**
     * 隐藏状态栏与导航栏，边缘划入时临时浮现。
     *
     * `setDecorFitsSystemWindows` 在 API 35 起被标记过时（官方推荐
     * `enableEdgeToEdge()`），但本项目 minSdk 23、targetSdk 34，
     * 且这里配合 `insetsController.hide(systemBars())` 使用，
     * 行为与预期一致 —— 换新 API 属于另一件事，不在修告警的范围里。
     */
    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 放在 run{} 里、让注解直接贴住调用表达式 —— 这是本工程里
            // 唯一实测能压住 Java 过时 API 告警的写法（见 EngineTaskService
            // 里 WIFI_MODE_FULL_HIGH_PERF 那处）。写在函数上或语句上方都无效。
            run { @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(false) }
            window.insetsController?.let { c ->
                c.hide(android.view.WindowInsets.Type.systemBars())
                c.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    /**
     * 网页窗口里那个悬浮「✕」关闭键。
     *
     * ## 为什么要做成可拖动 + 静置半透明
     *
     * 它原先钉死在右上角（40dp 实心圆，不透明）。而 dsh 页面自己的顶栏、
     * 章节折叠按钮、设置入口也都在右上角 —— 直接压住对方的控件，点不到。
     * dsh 的 UI 还会随版本变，写死一个角落迟早挡到别的东西。
     *
     * 所以这一版：
     *  · 静置时半透明（[EXIT_IDLE_ALPHA]），不抢视觉；
     *  · 按住立刻变实，明确「抓到了」；
     *  · 可拖到任意位置，避开挡住的控件；
     *  · **单击仍然是关闭** —— 拖动与单击靠移动距离区分，
     *    手指抖几像素不会被误判成拖动。
     *
     * 位置按比例存进偏好（见 [LauncherPrefs.getWebExitButtonPos]），
     * 下次打开窗口仍在用户放的地方。
     */
    private fun makeExitButton(root: FrameLayout): View {
        val d = resources.displayMetrics.density
        val btn = TextView(this)
        btn.text = "✕"
        btn.textSize = 16f
        btn.gravity = Gravity.CENTER
        btn.setTextColor(0xFFE6E1E5.toInt())
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(0x991C1B1F.toInt())
        btn.background = bg
        btn.elevation = 4 * d
        btn.isClickable = true
        btn.alpha = EXIT_IDLE_ALPHA
        btn.setOnClickListener { finish() }

        // 触摸抖动阈值。用系统值而不是写死像素：各设备密度不同，
        // 写死会让某些机型稍微一碰就被判成拖动、点不动关闭。
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startLeft = 0
        var startTop = 0
        var dragging = false

        btn.setOnTouchListener { v, ev ->
            val lp = v.layoutParams as? FrameLayout.LayoutParams
                ?: return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startLeft = lp.leftMargin
                    startTop = lp.topMargin
                    dragging = false
                    v.animate().alpha(1f).setDuration(80).start()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                    }
                    if (dragging) {
                        // 夹在容器内，避免拖出屏幕后找不回来
                        val maxLeft = (root.width - v.width).coerceAtLeast(0)
                        val maxTop = (root.height - v.height).coerceAtLeast(0)
                        lp.leftMargin = (startLeft + dx).toInt().coerceIn(0, maxLeft)
                        lp.topMargin = (startTop + dy).toInt().coerceIn(0, maxTop)
                        v.layoutParams = lp
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().alpha(EXIT_IDLE_ALPHA).setDuration(160).start()
                    if (dragging) {
                        saveExitButtonPos(root, v)
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        // 没拖动 → 当作单击。OnTouchListener 返回 true 会吃掉
                        // 默认的点击派发，所以这里必须自己调 performClick()。
                        v.performClick()
                    }
                    true
                }

                else -> false
            }
        }
        return btn
    }

    /**
     * 按保存的比例把关闭键放回上次的位置；没有记录时落在右上角。
     *
     * 必须在 root 完成布局后调用 —— 之前拿不到宽高，
     * 算出来的边距只能是 0，按钮会跑进左上角。
     */
    private fun placeExitButton(root: FrameLayout, btn: View) {
        val lp = btn.layoutParams as? FrameLayout.LayoutParams ?: return
        val (fx, fy) = LauncherPrefs.getWebExitButtonPos(this)
        val maxLeft = (root.width - btn.width).coerceAtLeast(0)
        val maxTop = (root.height - btn.height).coerceAtLeast(0)
        lp.leftMargin = (fx * maxLeft).toInt()
        lp.topMargin = (fy * maxTop).toInt()
        btn.layoutParams = lp
    }

    /** 把当前位置归一化成 0..1 存起来，供下次打开时还原。 */
    private fun saveExitButtonPos(root: FrameLayout, btn: View) {
        val lp = btn.layoutParams as? FrameLayout.LayoutParams ?: return
        // 用 1 兜底避免除零：可用空间为 0 时保存 0 没有意义，保留原值更安全
        val maxLeft = (root.width - btn.width).coerceAtLeast(1)
        val maxTop = (root.height - btn.height).coerceAtLeast(1)
        LauncherPrefs.setWebExitButtonPos(
            this,
            lp.leftMargin.toFloat() / maxLeft,
            lp.topMargin.toFloat() / maxTop
        )
    }

    // ------------------------------------------------------------------
    // WebView 配置
    // ------------------------------------------------------------------

    /**
     * WebView 全局配置。
     *
     * `databaseEnabled` 自 API 33 起过时（WebSQL 数据库已移除，设它无效），
     * 但 API 23–32 上它仍是唯一的开关，去掉会改变老设备上页面的存储能力。
     * 保留调用，仅在这一处压制告警 —— 不要提升到文件级，否则会把
     * 真正需要处理的过时 API 一起遮掉。
     */
    @Suppress("DEPRECATION")
    private fun setupWebView() {
        val wv = webView
        WebView.setWebContentsDebuggingEnabled(true)

        // 不透明底色：WebView 表面透明时会把父容器透出来，表现为白屏。
        // 注意 WebView 没有 backgroundColor 属性（无对应 getter），必须调 setter。
        wv.setBackgroundColor(android.graphics.Color.WHITE)

        // ── 无障碍能力桥接 ───────────────────────────────────────────────
        //
        // 注入 window.fclNative（原生方法）+ window.fclA11y（包装层，见 DshBridge.SHIM_JS），
        // 让 dsh 页面侧的脚本能读屏、点按。
        //
        // 授权闸门交给 DshBridge 内部：只有 trustedOrigin 为 true
        // （即当前主文档来自 127.0.0.1 / localhost）且无障碍服务已连接时才生效。
        // 不能用「注入就代表授权」的思路 —— addJavascriptInterface 的对象
        // 在所有 frame 里都可见，包括页面里嵌的第三方 iframe。
        wv.addJavascriptInterface(
            DshBridge { trustedOrigin },
            DshBridge.JS_NAME
        )

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                val line =
                    "[${cm.messageLevel()}] ${cm.message()} @ ${cm.sourceId()}:${cm.lineNumber()}"
                Log.d(TAG, line)
                // 只把 WARN/ERROR 送进 App 内日志，避免普通 console.log 把 Node 输出淹掉
                val level = cm.messageLevel()
                if (level == ConsoleMessage.MessageLevel.ERROR ||
                    level == ConsoleMessage.MessageLevel.WARNING
                ) {
                    AppState.launcher.log("WEB $line")
                }
                return true
            }

            /** 网页 `<input type="file">` → 系统文件选择器。 */
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?
            ): Boolean {
                filePathCallback ?: return false
                // 上一个请求未结束（连点 / 页面切换）→ 先回传空值释放，否则后续全部失效
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback

                val multiple =
                    params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                val intent = when {
                    // 页面要求「拍照」且只收图片（input 带 capture 属性）→ 直接起相机。
                    // 相机不可用时回退相册，不让用户卡在「点了没反应」。
                    params?.isCaptureEnabled == true && wantsOnlyImages(params) ->
                        buildCaptureIntent() ?: buildPhotoPickerIntent(params)

                    wantsOnlyImages(params) -> buildPhotoPickerIntent(params)

                    else -> runCatching { params?.createIntent() }.getOrNull()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "*/*"
                            // 多选必须显式声明，否则选择器只允许选一个，
                            // 页面按多选处理时会拿到「少了几张」的结果
                            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
                        }
                }
                return runCatching {
                    fileChooserLauncher.launch(intent)
                    true
                }.getOrElse {
                    AppState.launcher.log("WEB file chooser 启动失败: ${it.message}")
                    pendingFileCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }

            /** JS alert()：用原生对话框接管，保证深色页面里文字可见。 */
            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                result ?: return false
                alert("提示", message ?: "") { result.confirm() }
                return true
            }

            /** JS confirm()：确定 → confirm，取消 → cancel。 */
            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                result ?: return false
                android.app.AlertDialog.Builder(this@DshWebActivity)
                    .setTitle("确认")
                    .setMessage(message ?: "")
                    .setCancelable(false)
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .create()
                    .show()
                return true
            }

            /** JS prompt()：回传输入框内容，取消则 cancel。 */
            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                result ?: return false
                // 显式变量：EditText(...).apply{...} 在本项目分析器里推断不出接收者
                val input = EditText(this@DshWebActivity)
                input.setText(defaultValue ?: "")
                input.setSelection(input.text?.length ?: 0)
                android.app.AlertDialog.Builder(this@DshWebActivity)
                    .setTitle("输入")
                    .setMessage(message ?: "")
                    .setView(input)
                    .setCancelable(false)
                    .setPositiveButton("确定") { _, _ ->
                        result.confirm(input.text?.toString() ?: "")
                    }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .create()
                    .show()
                return true
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val u = request?.url.toString()
                // http/https 自己加载，其他协议一律拦截
                return !(u.startsWith("http://") || u.startsWith("https://"))
            }

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "onPageStarted: $url")
                // 每次导航都重算一次可信来源：离开本地页面后桥接必须立刻失效，
                // 否则页面跳到外部站点，那个站点的脚本仍能调用读屏与点击。
                trustedOrigin = isTrustedHost(url)
                // 错误钩子：前端 boot 抛错不会进任何原生回调，只能靠 JS 侧捕获
                view?.evaluateJavascript(
                    "window.addEventListener('error',function(e){" +
                        "console.error('WINERR: '+(e.message||'')+' @ '+(e.filename||'')+':'+(e.lineno||0));});" +
                        "window.addEventListener('unhandledrejection',function(e){" +
                        "console.error('REJECT: '+((e.reason&&e.reason.message)||e.reason));});",
                    null
                )
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "onPageFinished: $url")
                // 主动改写 viewport meta，并丢弃旧栅格重绘一次（见 applyViewport）
                view?.let { v ->
                    applyViewport(v)
                    // 注入无障碍桥接的 JS 包装层（window.fclA11y）。
                    //
                    // 放在 onPageFinished 而不是 onPageStarted：此时 document
                    // 已可用，包装层里的 CustomEvent 才能派发到页面脚本。
                    // 来源不可信时也注入，但每个方法内部会因 trustedOrigin
                    // 为 false 而返回「不可用」—— 注入本身不泄漏任何数据。
                    v.evaluateJavascript(DshBridge.SHIM_JS, null)
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // ⚠️ 必须写成 `request != null &&`，不能写成
                // `request?.isForMainFrame == true`：后者是对 Boolean? 做相等比较，
                // 编译器**不会**据此把 request 智能转换成非空，
                // 于是下面用 `request.url` 会直接报「nullable receiver」。
                if (request != null && request.isForMainFrame) {
                    val msg = "主文档加载失败: ${request.url} -> " +
                        "${error?.errorCode} ${error?.description}"
                    Log.e(TAG, msg)
                    AppState.launcher.log("WEB $msg")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                // 同上：显式判空才能拿到智能转换
                if (request != null && request.isForMainFrame) {
                    val msg = "主文档 HTTP ${errorResponse?.statusCode}: ${request.url}"
                    Log.e(TAG, msg)
                    AppState.launcher.log("WEB $msg")
                }
            }

            // 渲染进程崩溃时 WebView 就是一片白，且不会有任何 JS 报错。
            // 返回 true 表示已自行处理，避免整个应用进程被系统连带杀掉。
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                // didCrash() 是 API 26+，minSdk 23 上必须先判版本
                val crashed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    detail?.didCrash()
                } else {
                    null
                }
                AppState.launcher.log("WEB 渲染进程退出: didCrash=$crashed")
                Log.e(TAG, "renderer gone: didCrash=$crashed")
                finish()
                return true
            }
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // ── 视口（白屏的真正根因所在）────────────────────────────────
            // dsh 前端 index.html 里有 <meta name="viewport">，但 WebView 默认
            // useWideViewPort=false 会忽略它，改用 980px 布局视口再整体缩放；
            // 此时 html 的 height:100% 百分比链解析不到初始包含块高度，
            // 高度塌成 0 —— 就是实测到的 html.offsetHeight===0 而 scrollHeight===639。
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        // 用户设置的窗口缩放（设置 → dsh 窗口）。
        //
        // ⚠️ `setInitialScale` 是 **WebView** 的方法，不是 WebSettings 的 ——
        // 写进上面的 `wv.settings.apply { }` 里会报
        // `Unresolved reference: setInitialScale`。所以放在块后面调用。
        //
        // 它给的是「第一帧就按目标比例画」的初值；页面加载完成后
        // [applyViewport] 还会把**同一个比例**写进 viewport meta。
        // 两处取值一致，所以不会出现叠加缩放（0.5 × 0.5 = 0.25）那种事故。
        //
        // 注意 textZoom 保持 100：它是**只放大文字**的独立通道，
        // 与整体布局缩放叠加会让排版彻底走形。
        runCatching { wv.setInitialScale(zoomPercent) }
            .onFailure { Log.w(TAG, "setInitialScale($zoomPercent) 失败: ${it.message}") }

        // document 失焦时 navigator.clipboard.writeText 会被拒绝
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.requestFocus()

        // 脏数据清理：只在进程内第一次创建 WebView 时做一次，保住登录态
        if (!cookiesCleared) {
            cookiesCleared = true
            wv.clearCache(true)
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
                removeAllCookies(null)
                flush()
            }
            Log.d(TAG, "已清理缓存与 Cookie（一次性）")
        } else {
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        }

        setupDownloadListener(wv)
    }

    /**
     * 等 WebView 真正拿到尺寸后再加载。
     *
     * 如果在 View 尺寸还是 0 的时候 loadUrl，页面会以 0 高度视口完成首次布局，
     * dsh 里依赖 height:100% 的根容器解析成 0，之后即使 View 被测量成
     * 1080x1917，页面布局也不会自动恢复 —— 屏幕只剩 body 的白底。
     */
    private fun loadWhenSized(url: String, attempt: Int = 0) {
        if (webView.width > 0 && webView.height > 0) {
            Log.d(TAG, "loadUrl after size ${webView.width}x${webView.height}")
            webView.loadUrl(url)
            enterImmersive()
            return
        }
        if (attempt >= 50) {
            // 兜底：约 3 秒仍未拿到尺寸就照常加载，避免永远卡住
            Log.w(TAG, "loadUrl without size ${webView.width}x${webView.height}")
            webView.loadUrl(url)
            enterImmersive()
            return
        }
        webView.postDelayed({ loadWhenSized(url, attempt + 1) }, 60L)
    }

    /**
     * 改写 viewport meta 后强制重绘一次。
     *
     * viewport meta 由渲染进程异步应用，首帧往往已按旧缩放画完；
     * WebView 会把部分合成层留在旧尺寸的栅格里，表现为错位、截断、显示不全。
     * 切到软件层会强制丢弃全部缓存栅格并整页重绘，再切回默认恢复硬件合成。
     */
    private fun applyViewport(view: WebView) {
        // 缩放比例由「设置 → dsh 窗口」决定，默认 100（即 initial-scale=1）。
        //
        // 为什么把同一个值同时写进 initial/minimum/maximum：
        //  · 只写 initial 的话，用户双指一捏就能把页面缩到别的比例，
        //    下次进来又跳回设定值，观感是「设置没记住」；
        //  · 三个一起写等于锁定，行为可预期（页面本身已 user-scalable=no，
        //    这里只是把内部一致的假设写明确）。
        val scale = zoomPercent / 100.0
        // 用 Locale.ROOT 格式化：某些地区的小数点是逗号，拼进 CSS 会变成非法值
        val scaleText = String.format(java.util.Locale.ROOT, "%.2f", scale)

        val js = "(function(){" +
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
            "(document.head||document.documentElement).appendChild(m);}" +
            "m.setAttribute('content','width=device-width, initial-scale=$scaleText, " +
            "minimum-scale=$scaleText, maximum-scale=$scaleText, user-scalable=no');" +
            "})()"
        view.evaluateJavascript(js, null)

        view.postDelayed({
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            view.post {
                view.setLayerType(View.LAYER_TYPE_NONE, null)
                Log.d(TAG, "reraster after viewport apply")
            }
        }, 180L)
    }

    // ------------------------------------------------------------------
    // 文件选择辅助
    // ------------------------------------------------------------------

    /**
     * 页面是否**只**接受图片（`<input type="file" accept="image/…">`）。
     * 只有纯图片场景才走系统照片选择器，混合类型仍走通用文件选择器，
     * 否则用户会选不到 PDF / 文本等页面允许的文件。
     */
    // 注意：FileChooserParams 是 WebChromeClient 的嵌套类。
    // 在 webChromeClient 的匿名对象内部可以省略外层名，
    // 但在这些辅助方法里必须写全限定名，否则报 Unresolved reference。
    private fun wantsOnlyImages(params: WebChromeClient.FileChooserParams?): Boolean {
        val accepts = params?.acceptTypes ?: return false
        val nonEmpty = accepts.filter { it.isNotBlank() }
        if (nonEmpty.isEmpty()) return false
        return nonEmpty.all { it.trim().startsWith("image/", ignoreCase = true) }
    }

    /**
     * 系统照片选择器：API 33+ 用 ACTION_PICK_IMAGES（缩略图网格、免存储权限），
     * 低版本回退到 ACTION_GET_CONTENT + 图片类型过滤。
     */
    private fun buildPhotoPickerIntent(
        params: WebChromeClient.FileChooserParams?
    ): Intent {
        val allowMultiple =
            params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).apply {
                type = "image/*"
                if (allowMultiple) {
                    putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
                }
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
            }
        }
    }

    // ------------------------------------------------------------------
    // 挑选结果的落盘（修「上传图片失败」）
    // ------------------------------------------------------------------

    /** 挑选结果的临时目录，必须与 res/xml/file_paths.xml 的 cache-path 一致。 */
    private fun pickerCacheDir(): File = File(cacheDir, "picker").apply { mkdirs() }

    /** FileProvider authority，与 AndroidManifest 里的 `${applicationId}.fileprovider` 对应。 */
    private fun filesAuthority(): String = "$packageName.fileprovider"

    /**
     * 清掉上一次运行遗留的临时文件。
     *
     * 每次选图都会复制一份进 cache，不清的话会一直涨。
     * 在 onCreate 里做一次即可 —— 挑选过程中不能清（会把正在用的文件删掉）。
     */
    private fun cleanPickerCache() {
        runCatching {
            pickerCacheDir().listFiles()?.forEach { it.delete() }
        }
    }

    private fun isTrustedHost(url: String?): Boolean {
        val host = runCatching { Uri.parse(url).host }.getOrNull() ?: return false
        return host == "127.0.0.1" ||
            host == "::1" ||
            host.equals("localhost", ignoreCase = true)
    }

    /**
     * 把页面选中的 URI 复制进本应用 cache，返回自己的 FileProvider URI。
     *
     * ## 这是「无法上传图片」的正面修复
     *
     * 选择器（相册 / 文档提供者）返回的 `content://` 属于**别的应用**，
     * 读权限只授予了本应用主进程。WebView 的渲染进程是独立沙箱进程，
     * 拿不到这份授权 —— 页面 `File` 读到 0 字节或直接抛 SecurityException，
     * 表现就是「选完图上传卡住 / 传上去是空文件」。
     *
     * 复制成本地文件后，URI 指向本应用自己的 FileProvider（同 UID），
     * 渲染进程必然可读，从根上绕开跨进程授权问题。
     *
     * 复制失败时**退回原 URI**而不是丢弃：宁可让页面拿到一个可能读不了
     * 的 URI（旧行为），也不要让这次选择彻底无效。
     */
    private fun materializeForWebView(src: Array<Uri>): Array<Uri> {
        val out = ArrayList<Uri>(src.size)
        for (u in src) {
            // 已经是自己 FileProvider 的（拍照路径）不用再复制
            if (u.authority == filesAuthority()) {
                out.add(u)
                continue
            }
            val local = runCatching { copyToPickerCache(u) }.getOrNull()
            out.add(local ?: u)
        }
        return out.toTypedArray()
    }

    /** 复制单个 URI 到 cache，返回 FileProvider URI；失败返回 null。 */
    private fun copyToPickerCache(src: Uri): Uri? {
        val mime = runCatching { contentResolver.getType(src) }.getOrNull()
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime ?: "")
        // 用 Kotlin 的 toString(16) 而不是 Integer.toHexString：
        // 后者在本工程的编译环境里曾被解析到 HexFormat 重载，
        // 报「Type mismatch: inferred type is Int but HexFormat was expected」。
        // 文件名只需唯一，十六进制表示法本身无所谓。
        val name = "pick-" + System.currentTimeMillis() + "-" +
            src.hashCode().toString(16) +
            if (ext.isNullOrEmpty()) "" else ".$ext"
        val dst = File(pickerCacheDir(), name)

        val ok = runCatching {
            val input = contentResolver.openInputStream(src)
                ?: throw java.io.IOException("无法打开所选文件")
            input.use { ins ->
                dst.outputStream().use { outs ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        total += n
                        // 上限保护：异常大的文件既会撑爆 cache，也会让上传一直卡着
                        if (total > MAX_PICK_BYTES) {
                            throw java.io.IOException(
                                "文件超过 ${MAX_PICK_BYTES / 1024 / 1024} MB"
                            )
                        }
                        outs.write(buf, 0, n)
                    }
                }
            }
        }
        if (ok.isFailure) {
            runCatching { dst.delete() }
            AppState.launcher.log("WEB 复制所选文件失败: ${ok.exceptionOrNull()?.message}")
            return null
        }
        return runCatching { FileProvider.getUriForFile(this, filesAuthority(), dst) }
            .getOrElse {
                AppState.launcher.log("WEB FileProvider 取址失败: ${it.message}")
                null
            }
    }

    /**
     * 拍照上传用的 intent。
     *
     * `ACTION_IMAGE_CAPTURE` 不通过返回值给图，而是写进 `EXTRA_OUTPUT`
     * 指定的 URI，所以必须事先建好文件并把 URI 记进 [pendingCaptureUri]，
     * 结果回调里才知道该回传哪个 URI。
     *
     * 没有相机应用时返回 null，调用方回退到相册选择器 ——
     * 直接崩掉或什么都不发生都会让用户以为「上传坏了」。
     */
    private fun buildCaptureIntent(): Intent? = runCatching {
        val dst = File(pickerCacheDir(), "capture-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, filesAuthority(), dst)
        pendingCaptureUri = uri
        Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }.getOrNull()

    /** 单按钮提示框。 */
    private fun alert(title: String, message: String, onConfirm: () -> Unit) {
        runOnUiThread {
            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("确定") { _, _ -> onConfirm() }
                .create()
                .show()
        }
    }

    /** 短提示。 */
    private fun toast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    /**
     * 下载支持。
     *
     * WebView 收到下载请求时，**若宿主没设 DownloadListener，请求会被
     * 直接静默丢弃** —— 表现为「点了下载没反应」。
     *
     * http(s) 直链交给系统 DownloadManager（不需要存储权限），
     * 并且必须带上 WebView 的 Cookie，否则服务端可能返回登录页 HTML。
     * blob: 链接数据在浏览器内存里，DownloadManager 处理不了，只提示。
     */
    private fun setupDownloadListener(wv: WebView) {
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.startsWith("blob:")) {
                AppState.launcher.log("❌ blob: 下载暂不支持")
                toast("blob 下载暂不支持")
                return@setDownloadListener
            }
            val ok = runCatching {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    addRequestHeader("User-Agent", userAgent)
                    CookieManager.getInstance().getCookie(url)?.let {
                        addRequestHeader("Cookie", it)
                    }
                    setNotificationVisibility(
                        android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    setTitle(fileName)
                }
                val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
                dm.enqueue(req)
                true
            }.getOrElse {
                AppState.launcher.log("❌ 下载失败: ${it.message}")
                false
            }
            if (ok) {
                AppState.launcher.log("⬇ 已加入下载队列")
                toast("已开始下载")
            } else {
                toast("下载失败")
            }
        }
    }
}
