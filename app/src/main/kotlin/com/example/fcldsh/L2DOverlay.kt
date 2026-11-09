package com.example.fcldsh

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 主页上的 Live2D 面板（[l2d-static] 那个静态前端的宿主）。
 *
 * ## 当前是「先确认页面起没起」的调试版
 *
 * 需求最终要**透明**，但上一版做成透明之后什么都看不到，分不清是
 * 「页面没加载」「assets 没进 APK」还是「加载了但模型没渲染」。
 * 所以 [L2D_DEBUG_OPAQUE] 现在打开：
 * - 面板铺一块**不透明深青底** → 看到矩形就说明 View 确实上屏了；
 * - 左上角用**真实 TextView** 显示加载状态 / JS 体检结果 → 不用接 logcat
 *   就能知道卡在哪一步。
 *
 * 确认没问题后把 [L2D_DEBUG_OPAQUE] 与 [L2D_DEBUG_STATUS] 各置 `false`
 * 就是透明版，两个常量各管一件事。
 *
 * ## 为什么状态文字是 TextView 而不是 Compose 的 Text
 *
 * `AndroidView` 装的是**真正的子 View**，绘制顺序在 Compose 画布之上。
 * 同一个 Box 里后写的 `Text` 会被 WebView 整个盖住 —— 拿来诊断等于没做。
 * 所以状态文字也必须是真 View，放进同一个 FrameLayout 里压在 WebView 上。
 *
 * ## 为什么入口页用 `loadDataWithBaseURL` 而不是 `loadUrl("https://…")`
 *
 * 最初是给一个假源 `https://l2d.local/` 然后靠 [WebViewClient.shouldInterceptRequest]
 * 连**主文档**一起拦下来。但部分 WebView 版本**不为顶层导航调用
 * shouldInterceptRequest**（只有子资源才走），那样主文档就会真的去网上找
 * `l2d.local` → DNS 失败 → 一行错误页或纯白，正好对上「看不到东西」。
 *
 * 现在改成：入口页由 Kotlin 从 assets 读成字符串，用
 * `loadDataWithBaseURL("https://l2d.local/", …)` 注入 —— 文档本身不经过网络，
 * 而 `<script type="module">`、`fetch("models/…")` 这些**子资源**仍按 baseUrl
 * 解析成 `https://l2d.local/…`，再全部由 [WebViewClient.shouldInterceptRequest]
 * 从 assets 里读出来返回。
 *
 * 用 https 作为 baseUrl 还有个必要原因：ES 模块要求 origin 不是 `null`，
 * `file://` 或 `data:` 都会被 WebView 按跨域拒绝，整页空白。
 */
private const val L2D_HOST = "l2d.local"

/** 入口页用的假源；模块脚本的 origin 由它决定，必须是 http(s)。 */
private const val L2D_BASE_URL = "https://$L2D_HOST/"

/** `assets/` 下这个前端的目录名（`app/src/main/assets/l2d`）。 */
private const val L2D_ASSET_ROOT = "l2d"

/** 入口页。 */
private const val L2D_ENTRY = "index.html"

private const val L2D_TAG = "L2D_Panel"

/**
 * 调试开关一：`true` = 不透明深底（能看见面板在哪、便于排查）；
 * **`false` = 透明（当前状态，需求要求的最终效果）**。
 *
 * 页面本身永远是 `background: transparent`，这里只影响面板自己的底色。
 * 以后面板又「看不见」时，先把它改回 `true` 就能确定到底是没上屏还是没渲染。
 */
private const val L2D_DEBUG_OPAQUE = false

/**
 * 调试开关二：`true` = 在面板左上角显示加载状态/JS 体检结果。
 *
 * **现在是 `false`**：状态框是一块半透明黑底，压在透明面板上会显眼地挡住模型，
 * 成品不能留。要排查时改回 `true` 即可，日志（tag `L2D_Panel`）始终都在打。
 */
private const val L2D_DEBUG_STATUS = false

/** 调试底色：深青，和启动器的浅蓝渐变明显不同，一眼能认出面板范围。 */
private const val L2D_DEBUG_BG = 0xFF16323C.toInt()

/**
 * 扩展名 → MIME。
 *
 * 只列这个前端真正会请求的类型。**`.js` 必须是 `text/javascript`**：
 * 模块脚本的 MIME 不对会被 WebView 直接拒绝加载（表现为整页空白）。
 * `.moc3` 没有公认 MIME，给 `application/octet-stream` 即可。
 */
private val L2D_MIME: Map<String, String> = mapOf(
    "html" to "text/html",
    "js" to "text/javascript",
    "mjs" to "text/javascript",
    "css" to "text/css",
    "json" to "application/json",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "webp" to "image/webp",
    "gif" to "image/gif",
    "svg" to "image/svg+xml",
    "wasm" to "application/wasm",
    "moc3" to "application/octet-stream",
    "bin" to "application/octet-stream"
)

/**
 * WebView + 状态文字的容器。
 *
 * 跨重组复用：每次切面板都新建 WebView 要重下几 MB 贴图与 moc3，
 * 会明显卡顿并累积 WebView 实例。所以实例挂在 remember 上，只切可见性。
 */
private class L2DWebViewHolder(context: Context) {

    private val appContext = context.applicationContext

    /** 已经发起过加载。`onSizeChanged` 会因键盘/旋转反复触发，重复加载会闪。 */
    private var loaded = false

    /** 被拦截并成功返回的子资源数（诊断用：0 = 拦截没生效）。 */
    private val served = AtomicInteger(0)

    /** 诊断文字。真 View，压在 WebView 之上。 */
    private val statusView: TextView = TextView(appContext).apply {
        setTextColor(Color.WHITE)
        // 12sp：手机上 10–11sp 得太小，模型贴图一多就压不住背景，看不清
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setBackgroundColor(0xE6000000.toInt())
        setPadding(20, 16, 20, 16)
        // 诊断文字不能吃掉触摸，否则挡住模型的手势区
        isClickable = false
        isFocusable = false
        text = "面板已创建，等待布局…\n（看到这段字就说明 View 上屏了）"
    }

    /** WebView 创建失败的原因；null = 正常。用于把失败原因显示在面板上。 */
    private var webViewError: String? = null

    /**
     * ★ 用 try/catch 包住 WebView 的创建。
     *
     * 设备上的 **Android System WebView 组件缺失或损坏**时，`new WebView()`
     * 会抛 `MissingWebViewPackageException`（或 `InflateException` 一类）。
     * 放任它冒出去会让**整个启动器崩掉** —— 用户看到的是「应用闪退」，
     * 而不是「面板没显示」，排查方向会被带歪。
     *
     * 这里降级成一块只有文字的调试面板，把原因直接写在屏幕上。
     */
    private val webView: WebView? = try {
        object : WebView(appContext) {

            /**
             * 等**真的有尺寸**再加载。
             *
             * 前端启动第一件事就是 `canvas.clientWidth/Height` 算画布像素尺寸，
             * 0×0 时加载会得到 0 尺寸 canvas，模型画不出来。
             */
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (!loaded) setStatus("尺寸 ${w}x$h")
                if (!loaded && w > 0 && h > 0) {
                    loaded = true
                    loadEntry()
                }
            }
        }.apply {
            setBackgroundColor(if (L2D_DEBUG_OPAQUE) L2D_DEBUG_BG else Color.TRANSPARENT)

            // ★ 不要 setLayerType(LAYER_TYPE_HARDWARE)。
            // WebView 内部已由 Chromium 自己合成，再套一层硬件层会把整块 View
            // 渲染进离屏纹理，**透明背景会变成黑的**。默认层类型才是对的。
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            webViewClient = AssetClient(
                context = appContext,
                onStatus = ::setStatus,
                onServed = { served.incrementAndGet() },
                servedCount = { served.get() },
                onFallback = ::loadEntryViaDataUrl
            )
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    val line = "${msg.message()} @${msg.lineNumber()}"
                    Log.d(L2D_TAG, line)
                    // 控制台错误往往就是白屏真因（模块被拒、WebGL 不可用、
                    // 模型解析失败），直接显示到面板上，省得抓 logcat。
                    if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        setStatus("console.error: $line")
                    }
                    return true
                }
            }

            @SuppressLint("SetJavaScriptEnabled")
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // 只从 assets 里喂内容，本地文件/内容提供器一律不开
                allowFileAccess = false
                allowContentAccess = false
                // 前端自己处理触屏手势，禁掉 WebView 的缩放与双击
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            }
            // 加载由 onSizeChanged 触发。
        }
    } catch (t: Throwable) {
        // 设备缺少/损坏 Android System WebView 时的降级路径。
        // 这里不能再抛，否则整个启动器崩掉，用户只会看到「闪退」。
        webViewError = "${t.javaClass.simpleName}: ${t.message}"
        Log.e(L2D_TAG, "WebView 创建失败", t)
        null
    }

    /** 是否已经退到 `loadDataWithBaseURL` 兜底路上（只退一次，避免来回循环）。 */
    private var usedDataFallback = false

    /**
     * 第一条路：让 WebView 正常导航到假源。
     *
     * 这条路的好处是文档 URL / origin 完全正常，模块脚本与相对路径都按
     * `https://l2d.local/` 解析，`shouldInterceptRequest` 连主文档一起接管。
     *
     * 但**部分 WebView 版本不为顶层导航调用 `shouldInterceptRequest`**，
     * 那样主文档就会真去解析 `l2d.local` → DNS 失败 → 白屏或错误页。
     * 所以一旦发现主文档没被接管（[onFallback] 触发），就退到
     * [loadEntryViaDataUrl]。
     */
    private fun loadEntry() {
        Log.i(L2D_TAG, "① loadUrl $L2D_BASE_URL$L2D_ENTRY")
        setStatus("① 正在加载\n$L2D_BASE_URL$L2D_ENTRY\n${assetSummary()}")
        webView?.loadUrl("$L2D_BASE_URL$L2D_ENTRY")
    }

    /**
     * 运行时数一遍 `assets/l2d/` 里到底有什么。
     *
     * ## 为什么要在设备上数
     *
     * 「assets 有没有被打进 APK」是这一整块功能的前置条件，而它在构建之外
     * 无法确认：这个工程的构建任务里**看不到 `mergeAssets` 步骤**，
     * `build/intermediates/` 下也没有 `assets/` 目录。与其猜，不如让应用
     * 自己数一遍，把结果显示在面板上 —— 设备上是什么样，答案就是什么样。
     *
     * `AssetManager.list()` 对**目录**返回子项，对**文件**返回空数组，
     * 所以「空数组 = 叶子文件」可以用来递归计数。
     */
    private fun assetSummary(): String {
        fun list(path: String): Array<String>? = try {
            appContext.assets.list(path)
        } catch (_: IOException) {
            null
        }

        val top = list(L2D_ASSET_ROOT)
            ?: return "assets/$L2D_ASSET_ROOT 读不到"
        if (top.isEmpty()) return "assets/$L2D_ASSET_ROOT 是空的（未进 APK）"

        var files = 0
        fun walk(path: String) {
            val entries = list(path) ?: return
            for (e in entries) {
                val child = "$path/$e"
                val sub = list(child)
                if (sub.isNullOrEmpty()) files++ else walk(child)
            }
        }
        walk(L2D_ASSET_ROOT)

        val hasEntry = try {
            appContext.assets.open("$L2D_ASSET_ROOT/$L2D_ENTRY").use { true }
        } catch (_: IOException) {
            false
        }

        val summary = "assets/l2d: 顶层 ${top.size} 项 / 共 $files 文件\n" +
            "index.html ${if (hasEntry) "有" else "缺"}\n" +
            "顶层: ${top.joinToString(",")}"
        Log.i(L2D_TAG, summary.replace('\n', ' '))
        return summary
    }

    /**
     * 第二条路：由 Kotlin 读出 `index.html`，用 `loadDataWithBaseURL` 注入。
     *
     * 主文档完全不经过网络、也不依赖「顶栏拦截是否被调用」，只剩子资源还走
     * 拦截。baseUrl 仍给 `https://l2d.local/`：ES 模块要求 origin 不是 `null`，
     * `file://` / `data:` 都会被 WebView 按跨域拒绝（表现就是整页空白）。
     */
    private fun loadEntryViaDataUrl() {
        if (usedDataFallback) return
        usedDataFallback = true

        val html = try {
            appContext.assets.open("$L2D_ASSET_ROOT/$L2D_ENTRY")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (_: IOException) {
            val msg = "404 缺文件\nassets/$L2D_ASSET_ROOT/$L2D_ENTRY\n（assets 可能没打进 APK）"
            Log.w(L2D_TAG, msg.replace('\n', ' '))
            setStatus(msg)
            return
        }

        Log.i(L2D_TAG, "② 兜底 loadDataWithBaseURL, ${html.length} 字符, base=$L2D_BASE_URL")
        setStatus("② 主文档拦截失败，改用注入\n${html.length} 字符")
        // webView 可能为 null（设备缺 WebView 组件时降级），这里必须用安全调用
        webView?.loadDataWithBaseURL(L2D_BASE_URL, html, "text/html", "utf-8", null)
    }

    /** 交给 AndroidView 的根容器：WebView 在下，状态文字在上。 */
    val root: FrameLayout = FrameLayout(appContext).apply {
        if (L2D_DEBUG_OPAQUE) setBackgroundColor(L2D_DEBUG_BG)

        // webView 为 null = 设备缺/坏 Android System WebView。
        // 此时不添加任何 WebView，只靠下面的状态文字说明原因，
        // 避免把异常抛到主界面把启动器搞崩。
        webView?.let { addView(it, MATCH_PARENT, MATCH_PARENT) }

        if (L2D_DEBUG_STATUS) addView(statusView, WRAP_CONTENT, WRAP_CONTENT)

        // 有降级原因就直接写在最上层，覆盖掉「等待布局…」那句，
        // 否则用户看到的只是一个永远不动的占位文字。
        webViewError?.let { err ->
            statusView.text = "WebView 不可用\n$err\n\n设备需安装/修复 Android System WebView"
        }
    }

    private fun setStatus(text: String) {
        if (!L2D_DEBUG_STATUS) return
        statusView.text = text
        Log.i(L2D_TAG, "status: ${text.replace('\n', ' ')}")
    }

    /** 从 assets 里取文件，命中 [L2D_HOST] 的子资源请求全部就地返回。 */
    private class AssetClient(
        private val context: Context,
        private val onStatus: (String) -> Unit,
        /** 每成功返回一个子资源调用一次（计数用）。 */
        private val onServed: () -> Unit,
        /**
         * 当前已返回的子资源总数。
         *
         * 诊断意义很大：`onPageFinished` 时如果是 **0**，说明
         * `shouldInterceptRequest` 一次都没被调用 —— 也就是模块脚本与模型
         * 的请求全部落空，页面必然一片空白。这比看「页面已加载」有用得多。
         */
        private val servedCount: () -> Int,
        /**
         * 主文档疑似没被接管时调用，让上层退到 `loadDataWithBaseURL` 兜底。
         *
         * 两个触发点：主文档报错、或整页加载完成却**一个子资源都没返回**。
         */
        private val onFallback: () -> Unit
    ) : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url ?: return null
            if (!L2D_HOST.equals(url.host, ignoreCase = true)) return null

            val path = url.path.orEmpty().trimStart('/').ifEmpty { L2D_ENTRY }
            if (path.contains("..")) return notFound("bad path")

            val assetPath = "$L2D_ASSET_ROOT/$path"
            val stream = try {
                context.assets.open(assetPath)
            } catch (_: IOException) {
                // ★ 这条最关键：assets 没打进 APK 时，这里就是全部真相。
                Log.w(L2D_TAG, "缺文件 assets/$assetPath")
                onStatus("404 缺文件\nassets/$assetPath")
                return notFound(assetPath)
            }

            val ext = path.substringAfterLast('.', "").lowercase()
            val mime = L2D_MIME[ext] ?: "application/octet-stream"
            // 文本类必须带 charset，否则 WebView 可能按 ISO-8859-1 解，中文乱码
            val encoding = when {
                mime.startsWith("text/") || mime == "application/json" -> "utf-8"
                else -> null
            }

            // 计数：只要这里有打印，就说明拦截链路通了（诊断用）
            onServed()
            Log.d(L2D_TAG, "asset 200 $path ($mime)")

            // ⚠️ 必须用**显式** java.util.HashMap<String, String>，不能用 mapOf()/emptyMap()：
            // 平台类型会撞车，编译器给出的是自相矛盾的
            // 「Map<String, String> but Map<String, String> was expected」。
            val headers = java.util.HashMap<String, String>()
            headers["Access-Control-Allow-Origin"] = "*"
            headers["Cache-Control"] = "no-cache"

            return WebResourceResponse(mime, encoding, stream).apply {
                setStatusCodeAndReasonPhrase(200, "OK")
                responseHeaders = headers
            }
        }

        /** 主文档加载完成 → 跑一段 JS 体检，把结论写回面板。 */
        override fun onPageFinished(view: WebView?, url: String?) {
            val n = servedCount()
            Log.i(L2D_TAG, "onPageFinished $url, 已返回子资源 $n 个")

            // ★ 0 个资源 = shouldInterceptRequest 一次都没接管到。
            // 这说明主文档很可能是真去网上找 l2d.local 了（DNS 失败）或
            // 拦截对顶层导航无效 —— 直接换注入路线，别在这儿做无用的体检。
            if (n == 0) {
                Log.w(L2D_TAG, "拦截未生效（0 个资源），退到注入路线")
                onFallback()
                return
            }

            onStatus("页面已加载（资源 $n）\n检查渲染…")

            // 体检三件事：
            // 1. canvas 有没有真实像素尺寸（0 = CSS 没生效 / 没布局）
            // 2. WebGL 能不能拿到上下文（拿不到 = 模型库根本没法画）
            // 3. #hint 是否显示错误文字（模型加载失败的提示）
            val probe = """
                (function(){
                  var out = {};
                  var c = document.getElementById('stage');
                  out.canvas = c ? (c.width + 'x' + c.height) : 'no-canvas';
                  out.css = c ? (c.clientWidth + 'x' + c.clientHeight) : 'no-canvas';
                  try {
                    var t = document.createElement('canvas');
                    var gl = t.getContext('webgl') || t.getContext('experimental-webgl');
                    out.webgl = gl ? 'yes' : 'NO';
                  } catch (e) { out.webgl = 'throw:' + e; }
                  var h = document.getElementById('hint');
                  out.hint = (h && h.style.display !== 'none') ? h.textContent : '';
                  out.dpr = window.devicePixelRatio;
                  return JSON.stringify(out);
                })()
            """.trimIndent()

            view?.evaluateJavascript(probe) { result ->
                Log.i(L2D_TAG, "probe=$result")
                onStatus("已加载\n$result")
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: android.webkit.WebResourceError?
        ) {
            val text = "加载失败\n${request?.url}\n${error?.errorCode} ${error?.description}"
            Log.w(L2D_TAG, text.replace('\n', ' '))
            // 只关心主文档：子资源失败不该盖掉更有用的信息
            if (request?.isForMainFrame == true) {
                onStatus(text)
                // 主文档拿不到 → 几乎肯定是顶层导航没被拦截（DNS 解析 l2d.local
                // 失败）。换注入路线再试一次。
                onFallback()
            }
        }

        /** 页面只应停留在自己的假源里，外链一律拦掉（这是启动器主界面）。 */
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean = !L2D_HOST.equals(request?.url?.host, ignoreCase = true)

        private fun notFound(what: String): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream("404 $what".toByteArray())
        ).apply {
            setStatusCodeAndReasonPhrase(404, "Not Found")
            responseHeaders = java.util.HashMap<String, String>()
        }
    }
}

/**
 * 面板本体。
 *
 * @param visible 侧栏面板展开时置 false：侧栏是 Compose 画的，而这里装的是
 *   **真正的子 View**，绘制顺序在 Compose 之上 —— 不藏起来会把面板整个盖住。
 *   `GONE` 而不是 `alpha = 0`：前者让 WebView 停止渲染与动画，省电。
 */
@Composable
fun L2DPanel(
    modifier: Modifier = Modifier,
    visible: Boolean = true
) {
    val context = LocalContext.current
    val holder = remember(context) { L2DWebViewHolder(context) }

    DisposableEffect(holder) {
        onDispose {
            (holder.root.parent as? android.view.ViewGroup)?.removeView(holder.root)
            runCatching { holder.root.removeAllViews() }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            // ★ 复用同一个容器时它可能还挂在**上一个** ViewGroup 上
            // （组合重建、插槽位置变化）。直接返回带父容器的 View 会抛
            // 「specified child already has a parent」并崩掉主界面。
            (holder.root.parent as? android.view.ViewGroup)?.removeView(holder.root)
            holder.root
        },
        update = { it.visibility = if (visible) View.VISIBLE else View.GONE }
    )
}
