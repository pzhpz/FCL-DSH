package com.example.fcldsh

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileInputStream

/**
 * 「在应用私有目录里执行命令」这一层 —— Termux 的做法、本项目的现实、以及补法。
 *
 * ## Termux 是怎么做到的（关键，别理解错）
 *
 * 两条，缺一不可：
 *
 * **1. 它不做沙箱。** Termux 里跑的一切命令就是 Termux 应用自己的 UID，
 * 没有任何文件系统隔离。它**不用** bwrap，也**不用** Landlock ——
 * 这两样在 Android 出厂内核上本来就没有（`max_user_namespaces = 0`、
 * `CONFIG_SECURITY_LANDLOCK` 未开）。「Termux 是沙箱」是个误会：
 * 它和普通应用一样受 SELinux `untrusted_app` 约束，只是**不额外再套一层**。
 *
 * **2. 它把 `targetSdkVersion` 钉在 28。** 这才是「能执行私有目录里二进制」
 * 的真正原因。Android 10（API 29）起引入 W^X 策略（禁止 write-then-execute）：
 * `targetSdk >= 29` 的应用落在 SELinux 域 `untrusted_app_29`，该域**拒绝**
 * `execve` 落在应用可写数据目录（`/data/user/0/<包>/…`）里的文件；
 * `targetSdk <= 28` 的应用留在 `untrusted_app_27` 域，这条拒绝不存在。
 *
 * 一句话：**Termux 不是「破解」了限制，而是它的 targetSdk 让那条限制不适用。**
 *
 * ## 本项目的现实
 *
 * `targetSdk = 34`（见 `app/module.toml`），所以在 `untrusted_app_29` 域里，
 * 直接 exec `$PREFIX/bin/node` 必然 `Permission denied`。目前靠两条现成办法绕：
 *
 * - **ELF 二进制** → `/system/bin/linker64 <二进制>`。真正被 exec 的是
 *   `/system/bin` 里的 linker（系统文件，允许执行），目标二进制只是它的
 *   **参数** —— argv 是数据，不是 exec 目标。
 * - **shell 脚本** → `/system/bin/sh <脚本>`。同理：sh 把脚本当**数据**读进来解释。
 *
 * 这两条就是 [DSHRunner] 与 `assets/pty/node-pty-android.js` 里已在用的机制。
 *
 * ## 那还缺什么 —— 本对象补的就是这一层
 *
 * 缺的是**「手动在终端里敲命令」**：不该让人每次手写
 * `LD_LIBRARY_PATH=… /system/bin/linker64 … node`。
 *
 * 解法是给交互式 shell 生成一份 rc（经 `$ENV` 加载），里面为 `$PREFIX/bin` 下
 * 每个可执行项定义一个**同名 shell 函数**：
 *
 * ```sh
 * node() { LD_LIBRARY_PATH="$FCL_PREFIX/lib" /system/bin/linker64 "$FCL_PREFIX/bin/node" "$@"; }
 * ```
 *
 * 函数由 sh **自己**解释，函数体里 exec 的仍是系统 linker —— 一样不触发 W^X。
 * 而 sh 解析命令时**函数优先于 PATH**，所以 `node -v` 直接可用。
 *
 * ## 为什么用函数，而不是在 `$PREFIX/bin` 里放包装脚本
 *
 * 因为包装脚本本身也是「私有目录里等着被 exec 的文件」—— 同样被 W^X 拒绝，
 * 等于把问题往上挪一层。函数没有这个问题：它根本不是文件。
 *
 * ## 边界（如实说明，不假装支持）
 *
 * - **`#!/usr/bin/env node` 这类 shebang 仍不通**：内核会去 exec 解释器路径，
 *   而那个路径要么不存在、要么是被 W^X 拒绝的容器文件。所以脚本要用
 *   `sh 脚本` 或 `node 脚本` 显式指定解释器（[buildRc] 里对 `#!…node` 的脚本
 *   已做 `node 脚本` 的包装，认得出来就能直接敲）。
 * - **`vim` / `top` 这类全屏程序不可用**：终端没有 PTY（见 [TerminalSession]）。
 * - **函数不会传给子 shell**：`sh -c 'node -v'` 里的 `node` 仍需经 linker64 ——
 *   环境变量能继承、函数不能。
 */
object SandboxExec {

    private const val LINKER64 = "/system/bin/linker64"
    private const val LINKER32 = "/system/bin/linker"

    /** 生成的 rc 文件名（放在 `$PREFIX/etc` 下，与容器其它配置同级）。 */
    private const val RC_NAME = "fclsh.rc"

    /**
     * 系统 linker 路径。
     *
     * 64 位设备用 `linker64`，32 位设备只有 `linker`。两者都能把「私有目录里的
     * ELF」当参数加载起来 —— 选择依据只看设备上有没有这个文件。
     */
    fun linkerPath(): String = if (File(LINKER64).exists()) LINKER64 else LINKER32

    /**
     * W^X 是否对本应用生效。
     *
     * 判据只有一条：系统是 Android 10+ **且** 应用 targetSdk ≥ 29。
     * 两者同时成立才会落到 `untrusted_app_29` 域。
     */
    fun execRestricted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 29 && context.applicationInfo.targetSdkVersion >= 29

    /**
     * 一行现状描述，写进启动日志。
     *
     * 把「为什么能跑 / 为什么不能跑」摊开说，因为这个故障跨两层
     * （W^X 与沙箱后端是两件完全不同的事），只看一句 `Permission denied`
     * 极容易归错因。
     */
    fun describe(context: Context): String {
        val target = context.applicationInfo.targetSdkVersion
        return if (execRestricted(context)) {
            "exec 策略: targetSdk=$target（≥29，W^X 生效）→ 私有目录不可直接执行，" +
                "容器命令已包装为同名 shell 函数（内部经 ${linkerPath()} 加载）"
        } else {
            "exec 策略: targetSdk=$target（<29）→ 私有目录可执行，与 Termux 同级"
        }
    }

    /**
     * 生成（或更新）交互式 shell 的 rc 文件，返回它的绝对路径。
     *
     * 内容每次重算、变了才写：`$PREFIX/bin` 里的东西会随引擎安装/切换而变，
     * 用「重新生成 + 内容比较」既幂等，又不会因为上一份旧 rc 而漏掉新装的命令。
     *
     * @return rc 路径；写不出来返回 null（终端退化成「只有系统命令可用」）
     */
    fun ensureRc(prefix: File, home: File): File? {
        val etc = File(prefix, "etc")
        if (!etc.isDirectory && !etc.mkdirs()) return null
        val rc = File(etc, RC_NAME)
        val body = buildRc(prefix, home)
        if (runCatching { rc.readText() }.getOrNull() == body) return rc
        return runCatching {
            rc.writeText(body)
            // 只是给 sh 读的文本：不需要可执行位，但必须可读。
            rc.setReadable(true, false)
            rc
        }.getOrNull()
    }

    // ────────────────────────────── 生成 ──────────────────────────────

    /**
     * 拼出整份 rc。
     *
     * 注意这里输出的是一段**要交给 sh 的文本**，所以 `$` 全部写成 `\$`
     * （Kotlin 里 `\$` 就是字面 `$`）—— 不转义会被 Kotlin 当成模板插值，
     * 生成出一堆空字符串，而 sh 那边只会静默失效，极难排查。
     */
    private fun buildRc(prefix: File, home: File): String {
        val linker = linkerPath()
        val p = prefix.absolutePath
        val sb = StringBuilder(2048)

        sb.appendLine("# 由 FCL-DSH 自动生成，每次打开终端都会按当前容器重写。手改会被覆盖。")
        sb.appendLine("export FCL_PREFIX=\"$p\"")
        sb.appendLine("export HOME=\"${home.absolutePath}\"")
        sb.appendLine("export PATH=\"\$FCL_PREFIX/bin:/system/bin:/system/xbin:/sbin\"")
        // 没有 PTY 就如实声明，别写 xterm-256color 骗程序。
        sb.appendLine("export TERM=dumb")
        // ★ 故意把提示符清空 ★
        //
        // 终端面板用的是「内联输入」：提示符 `fcl$ ` 由界面画在输入框前面，
        // 光标就停在那后面，和 Termux 一样。
        //
        // 如果这里再让 shell 自己打印提示符，就会出现两个问题：
        // 1. 提示符没有换行，会被拼到下一行输出的开头
        //    （旧版行式读取里就是 `fcl$ file1` 这种错位）；
        // 2. 界面上会同时出现两个 `fcl$ `，一个死的、一个活的。
        //
        // `PS2` 一起清掉：后续行提示符同样会插进输出里。
        sb.appendLine("PS1=''")
        sb.appendLine("PS2=''")
        sb.appendLine()

        sb.appendLine("# ── 容器命令：包装成同名 shell 函数 ────────────────────────────")
        sb.appendLine("# 私有目录里的文件不能被 exec（W^X），所以函数体里把目标交给系统")
        sb.appendLine("# linker 当**参数**加载。函数不是文件，自己不需要任何权限。")
        sb.appendLine("fcl-exe() {")
        sb.appendLine("  [ \$# -ge 2 ] || { echo 'usage: fcl-exe <命令> [参数...]' >&2; return 2; }")
        sb.appendLine("  _c=\$1; shift")
        sb.appendLine("  LD_LIBRARY_PATH=\"\$FCL_PREFIX/lib\" $linker \"\$FCL_PREFIX/bin/\$_c\" \"\$@\"")
        sb.appendLine("}")
        sb.appendLine("fcl-sh() {")
        sb.appendLine("  [ \$# -ge 2 ] || { echo 'usage: fcl-sh <脚本> [参数...]' >&2; return 2; }")
        sb.appendLine("  _s=\$1; shift")
        sb.appendLine("  /system/bin/sh \"\$FCL_PREFIX/bin/\$_s\" \"\$@\"")
        sb.appendLine("}")
        sb.appendLine()

        // ── pkg：包管理器（绕行实现，不依赖 dpkg/apt）──────────────────
        //
        // ★ 为什么必须自己实现 ★
        //
        // 容器里的 `bin/pkg` 是 Termux 的残留脚本，它第 11 行
        // `source /data/data/com.termux/files/usr/bin/termux-setup-package-manager`
        // —— 那个路径在本机根本不存在，所以它是坏的；`apt` / `dpkg` 也全都没有。
        //
        // 而我们**不打算**装真正的 dpkg/apt：
        // · dpkg 要 fork+exec 维护者脚本（`*.postinst`）和 `dpkg-deb`，
        //   这两类 exec 都被 W^X 拦住，包会永远停在「已解包未配置」；
        // · Termux 的 dpkg/apt 把前缀编译死成 `/data/data/com.termux/files/usr`，
        //   我们的前缀更长，连原地改写都做不到；
        // · 想重编它们得先有 clang + 头文件（约 1.5~2 GB），当前容器没有。
        //
        // 所以走绕行：本应用内部已有 `TermuxPkg`（自己解 ar/tar/xz、剥 Termux
        // 前缀、改写文本路径、记录安装清单），把它经本地 HTTP 暴露出来，
        // 再用这个 shell 函数 + 一个 node 小 CLI 包成 `pkg` 命令。
        //
        // 用函数而不是 `bin/pkg` 脚本：脚本文件在私有目录、exec 会被拦，
        // 每次都得分两步 `sh $PREFIX/bin/pkg ...`；函数由 sh 自己解释，
        // 裸敲 `pkg install ffmpeg` 就行。
        sb.appendLine("# pkg：包管理器（内部经本应用的 TermuxPkg，不依赖 dpkg/apt）")
        sb.appendLine("pkg() {")
        sb.appendLine("  if [ ! -f \"\$FCL_PREFIX/lib/fcl-pkg.js\" ]; then")
        // ★ 必须用双引号 ★ 单引号里 shell 不展开变量，用户会看到字面量
        // `$FCL_PREFIX/...` 而不是真实路径 —— 这种报错文本会把人引向「路径写错了」。
        sb.appendLine("    echo \"pkg: 未就绪（\$FCL_PREFIX/lib/fcl-pkg.js 不存在）\" >&2")
        sb.appendLine("    echo 'pkg: 请重开一次终端；若反复出现，把这条告诉用户' >&2")
        sb.appendLine("    return 1")
        sb.appendLine("  fi")
        sb.appendLine(
            "  LD_LIBRARY_PATH=\"\$FCL_PREFIX/lib\" $linker " +
                "\"\$FCL_PREFIX/bin/node\" \"\$FCL_PREFIX/lib/fcl-pkg.js\" \"\$@\""
        )
        sb.appendLine("}")
        sb.appendLine()

        // 逐个 bin 项生成函数。认不出类型的跳过（不生成一个必然失败的命令）。
        val bin = File(prefix, "bin")
        val generated = ArrayList<String>(24)
        for (f in runCatching { bin.listFiles() }.getOrNull().orEmpty()) {
            if (!f.isFile) continue
            val name = f.name
            if (!SAFE_NAME.matches(name)) continue
            if (name == "fcl-exe" || name == "fcl-sh") continue
            // ★ `pkg` 必须跳过 ★
            //
            // 容器里的 `bin/pkg` 是**坏掉的** Termux 残留脚本（它 source 的
            // `/data/data/com.termux/files/usr/bin/termux-setup-package-manager`
            // 根本不存在）。而下面这个循环会对 bin 下每个可执行文件生成
            // 同名函数 —— 如果不跳过，它会把上面那个**真正可用的** `pkg()`
            // 覆盖成一个「跑坏脚本」的包装，裸敲 `pkg` 又回到原样报错。
            //
            // 这个覆盖是静默的（后定义的函数直接生效），排查时极难发现，
            // 所以在这里显式挡掉，并在下面 fcl-help 里说明。
            if (name == "pkg") continue
            when (kindOf(f)) {
                Kind.ELF -> {
                    // ★ 少数包把「前缀」编译进了代码，光靠 linker + LD_LIBRARY_PATH
                    // 不够，它们还会去某个写死的路径找自己的数据目录。
                    // 这几类必须额外给一个「家在哪儿」的变量，否则一运行就报
                    // 「找不到 stdlib / @INC」—— 而报错文本只会显示那个别人的
                    // 私有路径，很容易被误判成「装坏了」。
                    when {
                        // python：sys.prefix 由 PYTHONHOME 决定。不给的话它会用
                        // 编译期写死的 /data/data/com.termux/files/usr，
                        // 那是 Termux 的 0700 私有目录，必然找不到。
                        name.startsWith("python") ->
                            sb.appendLine(
                                "$name() { PYTHONHOME=\"\$FCL_PREFIX\" " +
                                    "LD_LIBRARY_PATH=\"\$FCL_PREFIX/lib\" " +
                                    "$linker \"\$FCL_PREFIX/bin/$name\" \"\$@\"; }"
                            )
                        // perl：@INC 里那条写死的前缀靠 PERL5LIB 覆盖不了，
                        // 但把我们的 lib 排在前面能让它先找到需要的模块。
                        name.startsWith("perl") ->
                            sb.appendLine(
                                "$name() { PERL5LIB=\"\$FCL_PREFIX/lib/perl5\" " +
                                    "LD_LIBRARY_PATH=\"\$FCL_PREFIX/lib\" " +
                                    "$linker \"\$FCL_PREFIX/bin/$name\" \"\$@\"; }"
                            )
                        else ->
                            sb.appendLine(
                                "$name() { LD_LIBRARY_PATH=\"\$FCL_PREFIX/lib\" " +
                                    "$linker \"\$FCL_PREFIX/bin/$name\" \"\$@\"; }"
                            )
                    }
                    generated.add(name)
                }
                Kind.NODE_SCRIPT -> {
                    // #! 指向 node 的脚本：直接喂给容器里的 node
                    sb.appendLine(
                        "$name() { LD_LIBRARY_PATH=\"\$FCL_PREFIX/lib\" " +
                            "$linker \"\$FCL_PREFIX/bin/node\" \"\$FCL_PREFIX/bin/$name\" \"\$@\"; }"
                    )
                    generated.add(name)
                }
                Kind.SH_SCRIPT -> {
                    sb.appendLine(
                        "$name() { /system/bin/sh \"\$FCL_PREFIX/bin/$name\" \"\$@\"; }"
                    )
                    generated.add(name)
                }
                Kind.SKIP -> Unit
            }
        }
        sb.appendLine()

        // 列表为空说明容器还没装（或者 bin/ 里没有可识别的可执行项）。
        // 直接拼一个空串会让帮助里出现「已包装的容器命令：」后面什么都没有，
        // 看起来像功能坏了 —— 如实说是「还没装」。
        val names = if (generated.isEmpty()) {
            "（无 —— 容器可能还没安装）"
        } else {
            generated.joinToString(" ")
        }

        sb.appendLine("# ── 帮助 ──────────────────────────────────────────────────────")
        sb.appendLine("fcl-help() {")
        sb.appendLine("  echo 'FCL-DSH 终端'")
        sb.appendLine("  echo '  · 已包装的容器命令：$names'")
        sb.appendLine("  echo '  · 它们经 $linker 加载，绕开 W^X（私有目录里的文件不可 exec）'")
        sb.appendLine("  echo '  · 未列出的命令：fcl-exe <名字> 参数…  或  fcl-sh <脚本> 参数…'")
        sb.appendLine("  echo '  · 新增自己的命令：放进 \$FCL_PREFIX/bin 后重开终端'")
        // pkg 不在上面那份自动包装列表里（bin/pkg 是坏的 Termux 残留脚本，
        // 被显式跳过并由本应用自己实现的函数取代），所以要单独说明。
        sb.appendLine("  echo '  · 装软件包：pkg install <包名>   （pkg help 看全部子命令）'")
        sb.appendLine("  echo '  · 无 PTY：vim / top 这类全屏程序不可用'")
        sb.appendLine("}")
        return sb.toString()
    }

    // ────────────────────────────── 判定 ──────────────────────────────

    private enum class Kind { ELF, NODE_SCRIPT, SH_SCRIPT, SKIP }

    /**
     * 只用前十几个字节判断「该怎么执行」，**不解析文件内容**。
     *
     * - ELF 魔数 `7F 45 4C 46` → 交给 linker。
     * - `#!` → 看 shebang 里有没有 `node`：有就喂 node，否则交给系统 sh。
     * - 其余（数据文件、空文件）→ 跳过。
     */
    private fun kindOf(f: File): Kind {
        val head = ByteArray(2)
        val n = runCatching { FileInputStream(f).use { it.read(head) } }.getOrDefault(-1)
        if (n < 2) return Kind.SKIP
        if (head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte()) return Kind.ELF
        if (head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte()) {
            // 只读第一行；脚本可能很长，但首行一定有
            val first = runCatching {
                f.bufferedReader().use { it.readLine().orEmpty() }
            }.getOrDefault("")
            return if (first.contains("node")) Kind.NODE_SCRIPT else Kind.SH_SCRIPT
        }
        return Kind.SKIP
    }

    /**
     * 能安全当 shell 函数名的名字。
     *
     * 首字符必须是字母或下划线（数字开头的名字在某些 shell 里会被当参数），
     * 其余允许 `-` 与 `.` —— mksh 认这些，而容器里确实有 `node-pty` 这种名字。
     * 不合规的项直接跳过：生成一个叫不出来的函数没有意义。
     */
    private val SAFE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_.+-]{0,63}$")
}
