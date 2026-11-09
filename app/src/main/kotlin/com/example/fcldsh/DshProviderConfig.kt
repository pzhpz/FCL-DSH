package com.example.fcldsh

import com.DSHRunner
import java.io.File

/**
 * 把启动器里的第三方密钥**追加**成 dsh 的一个提供方路由。
 *
 * ## 为什么必须写这里，而不是只注入环境变量
 *
 * dsh 的 LLM 适配器 `@deepseek-ai/dsh-llm-pi-ai` 只认 `settings.yaml` 里
 * `llm-pi-ai.providers` 这个字典：键是**路由名**，值描述这个提供方的
 * 端点、协议、模型表与凭据变量名。
 *
 * 光往进程里塞 `OPENAI_API_KEY` 只会让 dsh 自带的 `openai` 路由以为
 * 「我有凭据」，然后拿这个 key 去连 `api.openai.com` —— 中转站压根不在这条路上。
 * 所以第三方网关**必须**作为一个新路由声明出来，密钥才有着落。
 *
 * ```yaml
 * llm-pi-ai:
 *   providers:
 *     my-gateway:                     # ← 路由键，小写字母/数字/连字符
 *       displayName: "我的中转站"
 *       apiKeyEnv: MY_GATEWAY_API_KEY # ← 只写变量名，密钥不进配置文件
 *       api: openai-completions
 *       baseURL: "https://gw.example/v1"
 *       models:
 *         - id: "gpt-4o-mini"         # ← 从端点拉取列表后选的那一个
 * ```
 *
 * ## ★ 只做添加，绝不覆盖已有模型 ★
 *
 * `providers.<路由>` 下的 `models` 是**替换**语义：只要写了这个键，
 * 该路由的模型表就以这里为准。所以本文件的铁律是：
 *
 *  · 路由键已存在 → **整条跳过**，一个字节都不改（用户可能在 dsh 界面里
 *    加过模型、改过容量，我们无权抹掉）；
 *  · 只往 `providers:` 下**插入**我们自己的那一段，文件其余内容原样保留
 *    （注释、别的路由、别的 namespace 全都不动）；
 *  · 结构看不懂（内联流式写法、缩进异常）→ 放弃并写日志，
 *    宁可这条路由不生效，也不写坏一份用户可能手改过的配置。
 *
 * 不做完整 YAML 解析是刻意的：项目里没有 YAML 依赖（见
 * `InstanceSettingsPanel` 里同样的取舍），而「按行插入」对这种
 * 「只追加一个块」的需求已经足够，且天然保注释。
 */
object DshProviderConfig {

    /** dsh 设置文档的默认文件名（`dsh-settings-file` 的 `path` 默认值）。 */
    private const val SETTINGS = "settings.yaml"

    /** 我们负责的 namespace。 */
    private const val NS = "llm-pi-ai"

    /** `providers` 的父键。 */
    private const val PROVIDERS = "providers"

    /**
     * 追加结果。
     *
     * 名字刻意不叫 `Result` —— 那会和 `kotlin.Result` 撞名（它是 final，
     * 继承会直接编译失败）。
     */
    sealed class Outcome {
        /** 写入了新路由。 */
        data class Added(val routeKey: String) : Outcome()

        /** 路由已存在，按「只添加不覆盖」原则跳过。 */
        data class Present(val routeKey: String) : Outcome()

        /** 没写，附原因（官方账号、缺模型、文件结构看不懂……）。 */
        data class Skipped(val reason: String) : Outcome()
    }

    /**
     * 按当前选中账号补一条路由。
     *
     * 幂等：同一账号反复启动只会写一次，第二次返回 [Outcome.Present]。
     *
     * @param home `$DSH_HOME`（**实际**下发给进程的那个，见 DSHRunner 里的
     *   `env["DSH_HOME"]`，不要自己再推导一遍）
     */
    fun applyTo(home: File, account: AccountStore.Account): Outcome {
        // 官方账号走 dsh 内置的 deepseek 路由，不需要我们声明任何东西。
        // 反过来，若这里也给它写一条 `deepseek` 路由，`models` 的替换语义
        // 就会把内置模型表整个顶掉 —— 正是要避免的事。
        if (account.kind != AccountStore.AccountKind.THIRD_PARTY) {
            return Outcome.Skipped("官方账号使用 dsh 内置路由")
        }

        val model = account.model.trim()
        if (model.isBlank()) {
            return Outcome.Skipped("没有选定模型（第三方账号需要先拉取模型列表并选择）")
        }

        val routeKey = account.effectiveRouteKey
        val file = File(home, SETTINGS)

        val text = if (file.isFile) {
            runCatching { file.readText().removePrefix("\uFEFF") }
                .getOrElse { return Outcome.Skipped("读取 settings.yaml 失败: ${it.message}") }
        } else {
            ""
        }

        val plan = planInsert(text)
            ?: return Outcome.Skipped("settings.yaml 结构无法安全追加（可能是内联写法，请手动添加）")

        if (plan.existingRoutes.contains(routeKey)) {
            return Outcome.Present(routeKey)
        }

        val block = renderRoute(routeKey, account, model, plan.routeIndent)
        val lines: MutableList<String> = if (text.isBlank()) {
            ArrayList()
        } else {
            ArrayList(text.lines())
        }

        // 空文件 / 无 llm-pi-ai 段：在文件末尾补出整棵树
        if (plan.insertAtLine < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("$NS:")
            lines.add("${" ".repeat(2)}$PROVIDERS:")
            lines.addAll(block)
        } else {
            // `providers:` 这一行本身也可能要新建（段存在但没有子键）
            if (plan.providersLineIndent >= 0) {
                lines.add(
                    plan.insertAtLine,
                    "${" ".repeat(plan.providersLineIndent)}$PROVIDERS:"
                )
                lines.addAll(plan.insertAtLine + 1, block)
            } else {
                lines.addAll(plan.insertAtLine, block)
            }
        }

        val out = lines.joinToString("\n").trimEnd('\n') + "\n"
        return runCatching {
            writeAtomic(file, out)
            Outcome.Added(routeKey)
        }.getOrElse {
            Outcome.Skipped("写入 settings.yaml 失败: ${it.message}")
        }
    }

    // ------------------------------------------------------------------
    // 定位：该往哪一行插
    // ------------------------------------------------------------------

    /**
     * 插入计划。
     *
     * @param insertAtLine 在此行**之前**插入；-1 表示文件里还没有
     *   `llm-pi-ai:` 段，需要在末尾补整棵树
     * @param routeIndent  路由键该用的缩进
     * @param existingRoutes `providers` 下已有的路由键（用来判断「已存在」）
     * @param providersLineIndent `providers:` 这一行本身也要新建时给它用的缩进；
     *   -1 表示文件里已经有 `providers:`，只插路由块
     */
    private class Plan(
        val insertAtLine: Int,
        val routeIndent: Int,
        val existingRoutes: Set<String>,
        val providersLineIndent: Int = -1
    )

    /**
     * 读一遍文本，决定插在哪。
     *
     * 返回 null 表示结构不安全 —— 调用方放弃写入。
     */
    private fun planInsert(text: String): Plan? {
        val lines = if (text.isBlank()) emptyList() else text.lines()

        // ── 1. 找顶层 `llm-pi-ai:` ──────────────────────────────────
        val nsIdx = lines.indexOfFirst { line ->
            indentOf(line) == 0 && keyOf(line) == NS
        }
        if (nsIdx < 0) {
            // 整棵树都不存在：末尾新建
            return Plan(insertAtLine = -1, routeIndent = 4, existingRoutes = emptySet())
        }

        // 段内内联写法（`llm-pi-ai: {...}`）我们不敢手工插行
        if (hasInlineValue(lines[nsIdx])) return null

        val nsIndent = indentOf(lines[nsIdx])
        val end = sectionEnd(lines, nsIdx)

        // ── 2. 段内找 `providers:`（取缩进最浅的那个）──────────────
        var provIdx = -1
        var provIndent = -1
        for (i in nsIdx + 1 until end) {
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (indentOf(line) <= nsIndent) continue
            if (keyOf(line) != PROVIDERS) continue
            val ind = indentOf(line)
            if (provIndent < 0 || ind < provIndent) {
                provIdx = i
                provIndent = ind
            }
        }

        if (provIdx < 0) {
            // 有 llm-pi-ai 段但没有 providers 子键：段首插入 providers + 路由块
            return Plan(
                insertAtLine = nsIdx + 1,
                routeIndent = nsIndent + 4,
                existingRoutes = emptySet(),
                providersLineIndent = nsIndent + 2
            )
        }

        if (hasInlineValue(lines[provIdx])) return null

        val routeIndent = provIndent + 2

        // ── 3. 收集已有路由键 ───────────────────────────────────────
        val existing = LinkedHashSet<String>()
        for (i in provIdx + 1 until end) {
            val line = lines[i]
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (indentOf(line) != routeIndent) continue
            keyOf(line)?.let { existing.add(it) }
        }

        return Plan(provIdx + 1, routeIndent, existing)
    }

    /** 段结束位置：下一条缩进回到 0 的实质行（空白/注释不算）。 */
    private fun sectionEnd(lines: List<String>, startIdx: Int): Int {
        for (i in startIdx + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val t = line.trimStart()
            if (t.startsWith("#")) continue
            if (t == "---" || t == "...") return i
            if (indentOf(line) == 0) return i
        }
        return lines.size
    }

    private fun indentOf(line: String): Int {
        var i = 0
        while (i < line.length && line[i] == ' ') i++
        return i
    }

    /**
     * 取一行的映射键。
     *
     * `baseURL: https://x` → `baseURL`；`- id: x` → null（列表项不是键）；
     * `"llm-pi-ai":` → `llm-pi-ai`（去引号，用户可能用引号写键）。
     */
    private fun keyOf(line: String): String? {
        val t = line.trimEnd()
        val colon = t.indexOf(':')
        if (colon <= 0) return null
        val head = t.substring(0, colon).trim()
        if (head.isEmpty() || head.startsWith("-")) return null
        if (head.contains(' ') || head.contains('\t')) return null
        return head.trim('"', '\'')
    }

    /** 冒号后是否已经有值（有值就是内联/流式写法，我们不改）。 */
    private fun hasInlineValue(line: String): Boolean {
        val t = line.trimEnd()
        val colon = t.indexOf(':')
        if (colon < 0) return false
        val rest = t.substring(colon + 1).trim()
        return rest.isNotEmpty() && !rest.startsWith("#")
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    /**
     * 生成一段路由。
     *
     * 只写**必需**字段：`contextWindow` / `maxTokens` 省略时会落到适配器的
     * 默认值（262144 / 32768），列出来反而容易和网关实际容量对不上。
     */
    private fun renderRoute(
        routeKey: String,
        account: AccountStore.Account,
        model: String,
        routeIndent: Int
    ): List<String> {
        val ind = " ".repeat(routeIndent)
        val f1 = " ".repeat(routeIndent + 2)
        val f2 = " ".repeat(routeIndent + 4)

        // 显示名用账号名；用户起的中文名要加引号才不会被 YAML 误判
        val display = yamlString(account.name.trim().ifEmpty { routeKey })

        return listOf(
            "$ind$routeKey:",
            "${f1}displayName: $display",
            "${f1}apiKeyEnv: ${account.apiKeyEnv}",
            "${f1}api: ${account.effectiveProtocol.piApi}",
            "${f1}baseURL: ${yamlString(account.effectiveBase.trimEnd('/'))}",
            "${f1}models:",
            "$f2- id: ${yamlString(model)}"
        )
    }

    /**
     * YAML 双引号标量。
     *
     * 一律加引号：模型名可能带 `:`（`llama3:8b`）、地址带 `?`，显示名带中文；
     * 加引号对这三种都是合法的，不加则要看运气。
     */
    private fun yamlString(raw: String): String {
        val cleaned = raw
            .replace("\r", " ")
            .replace("\n", " ")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return "\"$cleaned\""
    }

    /**
     * 原子写：先写临时文件再 rename。
     *
     * 直接覆写时若写到一半失败，会留下半截 YAML —— 而 dsh 对非法文档是
     * 「启动失败」，不是「忽略」。宁可保持旧文件也不留残片。
     */
    private fun writeAtomic(file: File, content: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        runCatching {
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(false, false)
            tmp.setWritable(true, true)
        }
        if (!tmp.renameTo(file)) {
            // rename 失败（跨挂载点等）时退回直接写，至少内容是对的
            file.writeText(content)
            tmp.delete()
        }
    }

    /** 给日志用的结果摘要（**不含密钥**）。 */
    fun describe(outcome: Outcome): String = when (outcome) {
        is Outcome.Added -> "🔌 已向 dsh 添加提供方路由 ${outcome.routeKey}"
        is Outcome.Present -> "ℹ️ dsh 已有路由 ${outcome.routeKey}，保留原配置（只添加，不覆盖已有模型）"
        is Outcome.Skipped -> "ℹ️ 未写入提供方配置：${outcome.reason}"
    }

    /** 只为了让 `DSHRunner.onLog` 在异常路径上也有个统一出口。 */
    fun logFailure(e: Throwable) {
        DSHRunner.onLog("⚠️ 写提供方配置失败: ${e.message}")
    }
}
