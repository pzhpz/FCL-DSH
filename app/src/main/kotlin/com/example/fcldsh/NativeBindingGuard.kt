package com.example.fcldsh

import android.content.Context
import com.DSHRunner
import org.json.JSONObject
import java.io.File

/**
 * 启动前的原生绑定预检。
 *
 * ## 它为什么存在（以及它**不**做什么）
 *
 * dsh 0.2.2-alpha.2 启动即崩：
 *
 * ```
 * dsh: host preparation failed:
 *   No usable native binding found for node-addon-require-builtin-android-arm64 (auto)
 * ```
 *
 * 曾经的思路是「缺什么就补装什么」，但**这条路根本不存在**。实测证据：
 *
 * | 事实 | 来源 |
 * |---|---|
 * | `node-addon-require-builtin@0.1.9` 的 optionalDependencies 只有 darwin / linux / win32 | npm registry |
 * | `node-addon-require-builtin-android-arm64` 返回 404 | npm registry |
 *
 * 也就是说 **上游从来没有为 Android 发布过这个原生绑定**。
 * 不是「没装上」，是「不存在」—— 任何下载/补装逻辑都不可能成功。
 *
 * 所以这里不再尝试修补，只做一件事：**在 dsh 抛出一百行堆栈之前，
 * 用一屏话说清真正的原因**，并给出可执行的出路。
 *
 * ## ★ 「是不是 alpha 版本的问题」—— 是，但原因不是「alpha 才引入它」★
 *
 * 查过 registry，`dsh-app-boot` 的 **两个** 版本都依赖这个包：
 *
 * | dsh-app-boot 版本 | dependencies 里的 node-addon-require-builtin |
 * |---|---|
 * | `0.2.1-rc.4`（能跑） | `^0.1.9` |
 * | `0.2.2-alpha.2`（起不来） | `^0.1.9` |
 *
 * 区别在**加载时机**：0.2.2-alpha.2 在 boot 阶段就无条件要求这个绑定
 * （崩在 `dsh-app-boot/lib/index.js` 的 boot 里），而 rc.4 没有走到那条
 * 强制路径上，所以缺绑定也能启动。
 *
 * 因此结论要说得准确：**是版本行为差异，不是「alpha 才带上这个依赖」**。
 * 前面某一版注释曾暗示是 0.2.2 新增了这个依赖，那是错的，已更正。
 *
 * ## 判定分档
 *
 * · 父包 `node-addon-require-builtin` 不在 → 这个版本不用它，通过；
 * · 已有可用的 android 绑定（目录或 build 产物）→ 通过；
 * · 父包在、但 `optionalDependencies` 里**压根没有** `-android-<arch>` 条目
 *   → 上游未发布，本机无解；
 * · 父包在、且声明了 android 条目却不在磁盘上 → 理论可补装，
 *   但那同样是上游未发布（条目名不存在于 registry），实际仍无解。
 *
 * ## 为什么不每次都说完整版
 *
 * 用户连点几次「启动」就会得到几份一模一样的十行说明，把日志刷满。
 * 同一「版本@arch」组合只详细解释一次，之后只给一行 —— 结论没变，
 * 重复只会掩盖其它有用信息。
 *
 * 注意：KDoc 里不能出现「斜杠+星号」，Kotlin 块注释可嵌套，会吞掉整个文件。
 */
object NativeBindingGuard {

    /**
     * 已经详细解释过的「版本@arch」。
     *
     * 进程重启后清空 —— 新的一次会话重新完整说明是合理的，
     * 也避免用户换了引擎后仍只看到那行摘要。
     */
    private val explained = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 设备 ABI → npm cpu 命名（与 EngineInstaller 的映射保持一致）。 */
    private fun cpuName(): String {
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return when {
            abi.contains("arm64") -> "arm64"
            abi.contains("x86_64") -> "x64"
            abi.contains("armeabi") -> "arm"
            abi.contains("x86") -> "ia32"
            else -> "arm64"
        }
    }

    /**
     * 检查当前版本能否拿到可用原生绑定；缺了就**自动装纯 JS 替身**。
     *
     * ## 处理顺序
     *
     *  1. 这个版本不依赖父包 → 与本问题无关，放行；
     *  2. 已有真实 android 原生绑定（平台包或 build 产物）→ 放行，
     *     真实二进制比替身可靠，不去动它；
     *  3. 缺绑定 → 装配 [RequireBuiltinShim] 并再次检查；
     *  4. 替身也装不上（磁盘只读等）→ 才判定不可启动。
     *
     * 也就是说「上游没发布 android 变体」不再是死路 —— 只要替身能写进去，
     * rc.5 / alpha.2 / 未来的版本都能启动。**不必困在旧版**。
     *
     * @param emit 日志回调（用 DSHRunner.onLog，与启动流程同一条输出）
     * @return true 表示可用（原生或替身）；false 表示确实无法启动
     */
    fun preflight(context: Context, emit: (String) -> Unit): Boolean {
        val version = DSHRunner.currentVersion(context) ?: return true
        val nm = File(DSHRunner.engineRoot(context, version), "node_modules")
        if (!nm.isDirectory) return true

        val parent = File(nm, "node-addon-require-builtin")
        if (!parent.isDirectory) {
            // 这个版本不依赖它 —— 与本问题无关
            return true
        }

        val arch = cpuName()

        // ① 已有真实 android 原生绑定？（平台包或 build 产物）
        if (hasRealNativeBinding(nm, parent, arch)) return true

        // ② 装纯 JS 替身。loader 会优先 require 同名可选包，
        //    且只校验导出形状，所以替身能顶替缺失的原生二进制。
        val installed = runCatching {
            RequireBuiltinShim.ensureFor(context, version, emit)
        }.getOrDefault(false)
        if (installed) {
            emit("ℹ️ 已用纯 JS 替身补齐 android 原生绑定（上游从未发布该变体）")
            return true
        }

        // ③ 替身都装不上才报错 —— 这时确实没别的办法
        //
        // 同一个「版本@arch」只详细解释一次：用户连点几次启动时，
        // 重复的说明会把日志刷满，把其它有用信息挤没。
        val key = "$version@$arch"
        val firstTime = synchronized(explained) { explained.add(key) }
        if (!firstTime) {
            emit("❌ $version 缺少原生绑定且装配替身失败，已中止启动（详见首次尝试）")
            return false
        }

        val declared = declaredAndroidVariant(parent, arch)

        emit("❌ 这个版本无法在当前设备上启动")
        emit("   原因: 缺少原生绑定 node-addon-require-builtin-android-$arch，且替身写入失败")
        emit("   查证: 该包在 npm 上返回 404，上游从未发布过 android 变体")
        if (declared == null) {
            emit("         父包的 optionalDependencies 里也没有任何 android 条目")
        } else {
            emit("         父包声明了 $declared，但这个包在 npm 上并不存在")
        }
        emit("   → 本启动器通常会用纯 JS 替身顶上，这次是替身写不进去")
        emit("   → 检查应用存储空间，或到下载中心重装该版本后再试")
        emit("   参考: 引擎版本 $version，设备 $arch")
        return false
    }

    /** 是否已有**真实**的 android 原生绑定（替身不算）。 */
    private fun hasRealNativeBinding(nm: File, parent: File, arch: String): Boolean {
        // 平台包目录：存在即可，但排除替身自己写的那个
        val platformPkg = File(nm, "node-addon-require-builtin-android-$arch")
        if (platformPkg.isDirectory) {
            val idx = File(platformPkg, "index.js")
            val isShim = runCatching { idx.readText() }.getOrNull().orEmpty()
                .contains("FCL_DSH_REQUIRE_BUILTIN_SHIM")
            // 真实平台包通常没有 index.js（是 .node 预编译），或不是替身标记
            if (!isShim) return true
        }

        // 本地构建产物
        val build = File(parent, "build")
        if (build.isDirectory) {
            val hit = runCatching {
                build.walkTopDown().any {
                    it.isFile && it.name == "require_builtin.node" && it.path.contains("android")
                }
            }.getOrDefault(false)
            if (hit) return true
        }
        return false
    }

    /**
     * 父包的 `optionalDependencies` 里有没有声明 android 变体。
     *
     * @return 声明了的包名；没声明返回 null
     */
    private fun declaredAndroidVariant(parent: File, arch: String): String? {
        val pkgJson = File(parent, "package.json")
        if (!pkgJson.isFile) return null
        val text = runCatching { pkgJson.readText() }.getOrNull() ?: return null
        val o = runCatching { JSONObject(text.removePrefix("\uFEFF")) }.getOrNull() ?: return null
        val deps = o.optJSONObject("optionalDependencies") ?: return null
        val marker = "-android-$arch"
        val keys = deps.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.endsWith(marker)) return k
        }
        return null
    }
}
