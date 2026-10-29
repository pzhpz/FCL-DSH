package com.example.fcldsh

import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * 把无障碍能力（读屏 + 点击）暴露给网页侧的 AI。
 *
 * ## 结构
 *
 * dsh 的界面是网页，AI 要操作屏幕只能经由两条路之一：
 * 1. 自己写一个 dsh 插件调用本桥接（需要 dsh 插件 API）；
 * 2. 页面里的脚本直接调这些方法。
 *
 * 这里提供第 2 条路的底层能力，并在 [SHIM_JS] 里注入一层更好用的
 * `window.fclA11y` 包装，供页面侧（或未来接进来的工具）调用。
 *
 * ## 为什么每个方法都返回 String / Boolean
 *
 * `@JavascriptInterface` 只支持基本类型与 String。复杂结构一律序列化成
 * JSON 字符串返回，由 JS 侧 `JSON.parse`，避免在这里发明一套编解码。
 *
 * ## 线程模型
 *
 * 这些方法由 WebView 的 JavaBridge 线程调用，**不是主线程**。
 * AccessibilityService 的接口（rootInActiveWindow / dispatchGesture）都是
 * 到系统服务的 IPC，可以跨线程调用，所以不需要切线程。
 * 但任何一处都可能抛（节点失效、服务被系统解绑），全部包了 runCatching ——
 * 让桥接方法抛异常会直接把调用方的 JS 变成 rejected promise，更难排查。
 *
 * ## 安全边界
 *
 * `addJavascriptInterface` 会把对象注入**每一个 frame**，包括第三方 iframe。
 * 读屏能读到密码框、其他应用的界面，点击能代替用户操作，
 * 所以这里加了两道闸：
 * 1. [allowed]：只允许 dsh 本地页面（127.0.0.1 / localhost）调用；
 * 2. 无障碍服务未连接时一律返回不可用，而不是静默返回空数据。
 */
class DshBridge(
    /** 当前页面是否可信（由宿主按 URL 判定）。 */
    private val allowed: () -> Boolean
) {

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    /** 桥接是否可用（页面可信 + 无障碍服务已连接）。 */
    @JavascriptInterface
    fun isAvailable(): Boolean = allowed() && DshAccessibilityService.isRunning()

    /**
     * 返回能力详情（JSON），便于页面侧决定要不要显示入口。
     *
     * ```json
     * {"available":true,"service":"connected","canGesture":true,"apiVersion":1,
     *  "reason":""}
     * ```
     */
    @JavascriptInterface
    fun status(): String = runCatching {
        val ok = allowed()
        val running = DshAccessibilityService.isRunning()
        JSONObject().apply {
            put("available", ok && running)
            put("service", if (running) "connected" else "disconnected")
            put("canGesture", android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
            put("apiVersion", API_VERSION)
            put(
                "reason",
                when {
                    !ok -> "页面来源不可信，桥接已禁用"
                    !running -> "无障碍服务未开启，请到系统设置中启用"
                    else -> ""
                }
            )
        }.toString()
    }.getOrElse { "{\"available\":false,\"reason\":\"status failed\"}" }

    /** 给调用方（含 AI）看的能力说明，纯文本。 */
    @JavascriptInterface
    fun help(): String = """
        fclA11y — 通过系统无障碍服务读取屏幕并模拟操作。

        status()                     返回 JSON：是否可用及原因
        readScreen(limit)            返回 JSON 数组，每项 {text,desc,cls,id,b,click,edit,scroll,focus}
        readScreenText(limit)        返回纯文本，每行一个可见文字，适合直接阅读
        tapText(text, exact)         按文字/描述点击，返回是否点到
        tapAt(x, y)                  按屏幕坐标点按（px）
        longPressAt(x, y, ms)        长按
        back()                       模拟返回键
        home()                       回到桌面

        limit 默认 400，最大 ${DshAccessibilityService.MAX_NODES}。
        坐标是屏幕绝对像素，原点在左上角。
    """.trimIndent()

    // ------------------------------------------------------------------
    // 读屏
    // ------------------------------------------------------------------

    /** 结构化快照（JSON 数组字符串）。不可用时返回空数组。 */
    @JavascriptInterface
    fun readScreen(limit: Int): String {
        if (!isAvailable()) return "[]"
        val svc = DshAccessibilityService.instance ?: return "[]"
        return runCatching {
            svc.snapshot(clampLimit(limit)).toString()
        }.getOrElse {
            Log.w(TAG, "readScreen failed: ${it.message}")
            "[]"
        }
    }

    /** 纯文本快照。不可用时返回空串。 */
    @JavascriptInterface
    fun readScreenText(limit: Int): String {
        if (!isAvailable()) return ""
        val svc = DshAccessibilityService.instance ?: return ""
        return runCatching {
            svc.readScreenText(clampLimit(limit))
        }.getOrElse {
            Log.w(TAG, "readScreenText failed: ${it.message}")
            ""
        }
    }

    // ------------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------------

    /** 按文字点击。exact=true 要求完全相等。 */
    @JavascriptInterface
    fun tapText(text: String?, exact: Boolean): Boolean {
        if (!isAvailable() || text.isNullOrBlank()) return false
        val svc = DshAccessibilityService.instance ?: return false
        return runCatching { svc.clickByText(text, exact) }.getOrDefault(false)
    }

    /** 按屏幕坐标点按。 */
    @JavascriptInterface
    fun tapAt(x: Float, y: Float): Boolean {
        if (!isAvailable()) return false
        val svc = DshAccessibilityService.instance ?: return false
        return runCatching { svc.tap(x, y) }.getOrDefault(false)
    }

    /** 长按。durationMs 缺省 800ms。 */
    @JavascriptInterface
    fun longPressAt(x: Float, y: Float, durationMs: Int): Boolean {
        if (!isAvailable()) return false
        val svc = DshAccessibilityService.instance ?: return false
        val d = if (durationMs <= 0) 800L else durationMs.toLong()
        return runCatching { svc.longPress(x, y, d) }.getOrDefault(false)
    }

    /** 模拟返回键。 */
    @JavascriptInterface
    fun back(): Boolean {
        if (!isAvailable()) return false
        val svc = DshAccessibilityService.instance ?: return false
        return runCatching { svc.back() }.getOrDefault(false)
    }

    /** 回到桌面。 */
    @JavascriptInterface
    fun home(): Boolean {
        if (!isAvailable()) return false
        val svc = DshAccessibilityService.instance ?: return false
        return runCatching { svc.home() }.getOrDefault(false)
    }

    /** 把外部传入的 limit 收进合理区间，避免页面传 1e9 把遍历撑爆。 */
    private fun clampLimit(limit: Int): Int = when {
        limit <= 0 -> DEFAULT_LIMIT
        limit > DshAccessibilityService.MAX_NODES -> DshAccessibilityService.MAX_NODES
        else -> limit
    }

    companion object {
        private const val TAG = "DshBridge"

        /** 注入到 JS 的对象名。 */
        const val JS_NAME = "fclNative"

        /** 桥接 API 版本，页面侧可据此判断能力。 */
        const val API_VERSION = 1

        private const val DEFAULT_LIMIT = 400

        /**
         * 注入到页面的包装层。
         *
         * 暴露成 `window.fclA11y`，方法与原生一一对应但做了三件额外的事：
         * 1. 数字参数补默认值（JS 里经常漏传，原生会因为类型不匹配直接 false）；
         * 2. `readScreen` 自动 `JSON.parse`，返回真正的数组而不是字符串；
         * 3. 就绪时派发 `fcl-a11y-ready` 事件，让页面不用轮询。
         *
         * 任何一步失败都吞掉并降级 —— 注入脚本本身绝不能抛，
         * 那会在 onPageFinished 里留下一个未捕获错误。
         */
        val SHIM_JS: String = """
            (function(){
              try {
                if (window.fclA11y) return;
                var n = window.$JS_NAME;
                if (!n) return;
                function num(v, d){ v = Number(v); return isFinite(v) ? v : d; }
                function parse(s, d){ try { return JSON.parse(s); } catch(e){ return d; } }
                window.fclA11y = {
                  apiVersion: $API_VERSION,
                  available: function(){ try { return n.isAvailable(); } catch(e){ return false; } },
                  status: function(){ return parse(n.status(), {available:false}); },
                  help: function(){ return n.help(); },
                  readScreen: function(limit){ return parse(n.readScreen(num(limit, $DEFAULT_LIMIT)), []); },
                  readScreenText: function(limit){ return n.readScreenText(num(limit, $DEFAULT_LIMIT)) || ''; },
                  tapText: function(t, exact){ return n.tapText(String(t == null ? '' : t), !!exact); },
                  tapAt: function(x, y){ return n.tapAt(num(x, 0), num(y, 0)); },
                  longPressAt: function(x, y, ms){ return n.longPressAt(num(x, 0), num(y, 0), num(ms, 800)); },
                  back: function(){ return n.back(); },
                  home: function(){ return n.home(); }
                };
                try {
                  document.dispatchEvent(new CustomEvent('fcl-a11y-ready', {
                    detail: { apiVersion: $API_VERSION }
                  }));
                } catch(e){}
              } catch(e) {
                console.warn('fclA11y shim failed: ' + e);
              }
            })();
        """.trimIndent()
    }
}
