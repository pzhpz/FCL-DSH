package com.example.fcldsh

import android.content.Context
import com.DSHRunner
import java.io.File

/**
 * `node-addon-require-builtin` 的纯 JS 替身。
 *
 * ## 为什么需要它
 *
 * dsh 0.2.2-alpha.2 / 0.2.1-rc.5 启动即崩：
 *
 * ```
 * dsh: host preparation failed:
 *   No usable native binding found for node-addon-require-builtin-android-arm64 (auto)
 * ```
 *
 * 查证 npm registry 后确认：`node-addon-require-builtin` 从 0.0.11 到 0.1.9
 * **从未发布过 android 变体**（optionalDependencies 只有 darwin / linux /
 * win32），`node-addon-require-builtin-android-arm64` 直接 404。
 *
 * 也就是说这不是「没装上」，而是「不存在」—— 等上游发布、换安装参数、
 * 重装引擎都不会有任何改变。
 *
 * ## 为什么 JS 替身能顶替原生模块
 *
 * 原生加载器 `node-addon-native-custom-loader` 的 `loadEntry` 按顺序尝试：
 *
 *  1. `require('node-addon-require-builtin-android-arm64')`  ← 可选包
 *  2. `build/nodeabi/<abi>-android-arm64/require_builtin.node` ← 本地构建
 *  3. `build/napi/<abi>-android-arm64/require_builtin.node`    ← 本地构建
 *
 * 第 1 条是**普通的 Node 模块解析**，没有要求必须是二进制。命中后
 * `validateLoadedBinding` 只校验导出形状：
 *
 * ```
 * requireBuiltin        : function
 * isAllowedInternalId   : function
 * getNativeBindingInfo  : function
 * info.mode / product / backend / abi : string
 * product === 'require-builtin'
 * abi === 'napi-v9'（backend=napi 时）
 * ```
 *
 * 全部可以由纯 JS 满足。所以只要在 `node_modules` 放一个同名包，
 * 加载器就会选中它，第 2、3 条根本不会走到。
 *
 * ## `requireBuiltin` 的语义怎么实现
 *
 * 这个原生模块的本职是「在运行时载入 Node 的内部模块」。而本项目启动
 * dsh 时**已经传了 `--expose-internals`**（见 DSHRunner.startNode 的参数表），
 * 该标志本身就让 userland 的 `require()` 能解析 `internal/xxx`。
 * 所以替身直接委托给 `require(moduleId)` 即可，不需要任何原生能力。
 *
 * ## 与 ensureFlockShim 的关系
 *
 * 两者是同一个思路的两个实例：上游只发布 linux/darwin 二进制，
 * Android 上没有可用实现，于是用 JS 实现顶替。
 *
 * 注意：KDoc 里不能出现「斜杠+星号」，Kotlin 块注释可嵌套，会吞掉整个文件。
 */
object RequireBuiltinShim {

    /** 标记：带版本号，改了 JS 内容必须同步改它，否则旧文件不会被重写。 */
    private const val MARKER = "FCL_DSH_REQUIRE_BUILTIN_SHIM_V1"

    /** 替身包名。必须与 loader 拼出来的可选包名完全一致。 */
    private const val PKG = "node-addon-require-builtin-android-arm64"

    /** 父包名：只有它存在（即这个版本真的依赖它）才需要装替身。 */
    private const val PARENT = "node-addon-require-builtin"

    /**
     * 为**所有已装版本**装配替身。
     *
     * 与 [DSHRunner.ensureFlockShim] 同理：只装当前版本的话，
     * 切到另一个版本又会撞上同一个崩溃。
     *
     * @return 是否至少有一个版本具备可用绑定（真原生或替身）
     */
    fun ensure(context: Context, emit: (String) -> Unit = {}): Boolean {
        var any = false
        for (v in DSHRunner.installedVersions(context)) {
            if (ensureFor(context, v, emit)) any = true
        }
        return any
    }

    /**
     * 为指定版本装配替身。
     *
     * @return true 表示该版本已有可用绑定（既有真实原生绑定，或替身写入成功）；
     *   false 表示该版本不需要（不依赖父包）或写入失败
     */
    fun ensureFor(context: Context, version: String, emit: (String) -> Unit = {}): Boolean {
        val nm = File(DSHRunner.engineRoot(context, version), "node_modules")
        if (!nm.isDirectory) return false

        // 这个版本不依赖父包 —— 与本次问题无关，不必装
        if (!File(nm, PARENT).isDirectory) return false

        // 已经有真实原生绑定就别动：真的比替身可靠
        if (hasRealBinding(nm)) return true

        val dest = File(nm, PKG)
        val index = File(dest, "index.js")
        val existing = runCatching { index.readText() }.getOrNull().orEmpty()
        if (existing.contains(MARKER)) {
            // 已装好，只需确保 package.json 也在
            ensurePackageJson(dest)
            return true
        }

        val ok = runCatching {
            dest.mkdirs()
            ensurePackageJson(dest)
            index.writeText(shimJs())
            true
        }.getOrDefault(false)

        if (ok) {
            emit("🔧 已安装纯 JS 替身 $PKG（$version）")
            emit("   上游没有发布 android 原生绑定，改用 --expose-internals 实现同等能力")
        } else {
            emit("❌ 无法写入替身包：${dest.absolutePath}")
        }
        return ok
    }

    /** 该版本是否已有真实的 android 原生绑定（平台包目录或构建产物）。 */
    private fun hasRealBinding(nm: File): Boolean {
        // ① 可选包目录（但不是我们写的替身）
        val pkgDir = File(nm, PKG)
        if (pkgDir.isDirectory) {
            val idx = File(pkgDir, "index.js")
            val isShim = runCatching { idx.readText() }.getOrNull().orEmpty()
                .contains(MARKER)
            // 目录存在且不是替身 → 视为真原生
            if (!isShim) return true
        }

        // ② 本地构建产物
        val build = File(nm, "$PARENT/build")
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

    /** 写 `package.json`：目录名是解析依据，但清单也要自洽。 */
    private fun ensurePackageJson(dest: File) {
        val pkgJson = File(dest, "package.json")
        if (pkgJson.isFile) return
        pkgJson.writeText(
            """
            {
              "name": "$PKG",
              "version": "0.0.0-fcl-shim",
              "main": "index.js",
              "description": "Pure-JS stand-in for the never-published android native binding."
            }
            """.trimIndent() + "\n"
        )
    }

    /**
     * 替身的 JS 源码。
     *
     * ⚠️ 这里刻意**不用模板字符串**（反引号 + 美元花括号）：
     * Kotlin 的原始字符串会把 `$` 当插值起点，写进去会编译不过。
     * 全部用普通字符串拼接，省掉一堆转义。
     */
    private fun shimJs(): String = """
        // $MARKER
        //
        // 由 DSH Launcher 自动生成，请勿手改。
        //
        // 上游 node-addon-require-builtin 从未发布 android 变体
        // （node-addon-require-builtin-android-arm64 在 npm 上是 404），
        // 而 node-addon-native-custom-loader 的 loadEntry 会优先
        // require 这个可选包，且加载后只校验导出形状、不校验是否为二进制。
        //
        // 因此这里用纯 JS 满足该契约：requireBuiltin 直接委托给 require，
        // 其能力来自启动参数 --expose-internals（让 internal/xxx 可被解析）。
        'use strict';

        function requireBuiltin(moduleId) {
          if (typeof moduleId !== 'string' || moduleId.length === 0) {
            throw new TypeError('requireBuiltin: moduleId must be a non-empty string');
          }
          return require(moduleId);
        }

        function isAllowedInternalId(moduleId) {
          if (typeof moduleId !== 'string' || moduleId.length === 0) return false;
          try {
            require.resolve(moduleId);
            return true;
          } catch (e) {
            return false;
          }
        }

        function getNativeBindingInfo() {
          return {
            mode: 'js-shim',
            product: 'require-builtin',
            backend: 'napi',
            abi: 'napi-v9'
          };
        }

        module.exports = {
          requireBuiltin: requireBuiltin,
          isAllowedInternalId: isAllowedInternalId,
          getNativeBindingInfo: getNativeBindingInfo
        };
    """.trimIndent() + "\n"
}
