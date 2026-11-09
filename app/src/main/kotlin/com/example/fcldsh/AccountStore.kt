package com.example.fcldsh

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.DSHRunner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * 用户（密钥）管理：本地存储、连通性检测、启动时注入 dsh。
 *
 * ## 存哪
 *
 * `filesDir/accounts.json`，**不放 `DSH_HOME`**。原因有两条：
 *
 *  1. `EngineExporter` 导出整合包时会排除 `DSH_HOME` 下的凭据类文件，
 *     放进去等于让密钥在导出时被文件名规则追着跑，漏一个就泄一次；
 *  2. 账号是**启动器**的概念（用户自己填的、给启动器注入用的），
 *     不属于某个 dsh 版本的运行数据 —— 换版本不该丢账号。
 *
 * 文件权限由 Android 应用沙箱保证（其它应用读不到 `filesDir`）。
 *
 * ## 三种协议
 *
 * 见 [Protocol]。它们是当下 LLM 服务端事实上的三种标准形状，
 * 差异在**认证头**与**请求体结构**，不在业务语义：
 *
 * | 协议 | 认证 | 路径 |
 * |---|---|---|
 * | OpenAI 兼容 | `Authorization: Bearer <key>` | `{base}/chat/completions` |
 * | Anthropic | `x-api-key: <key>` | `{base}/v1/messages` |
 * | Google Gemini | `?key=<key>` 查询参数 | `{base}/v1beta/models/{model}:generateContent` |
 *
 * DeepSeek 官方走的就是 OpenAI 兼容那一套（`https://api.deepseek.com`），
 * 所以 [AccountKind.OFFICIAL] 不需要单独一套请求逻辑，只是免去用户手填地址。
 *
 * ## 连通性检测怎么算「通过」
 *
 * 发一次**极小的**真实请求（`max_tokens`/`candidates` 都压到最小），
 * 只看 HTTP 状态码：
 *  · 200/201 → 通过
 *  · 401/403 → 密钥无效（这条要单独说，否则用户不知道该改哪个字段）
 *  · 404 → 地址或模型名不对
 *  · 其它 → 如实报状态码，不猜
 *
 * 请求本身要花掉一点点额度，所以不自动检测、只在用户点「创建」时跑一次。
 *
 * ## 启动时注入
 *
 * [applyToEnvironment] 把选中账号写进 dsh 进程的环境变量。用的是各家
 * 服务端**通用**的变量名（`OPENAI_API_KEY` 之类），这样 dsh 本体和
 * 走这些约定的插件都能读到；同时往 `$DSH_HOME/android-account.json`
 * 写一份结构化描述，供需要完整信息的插件使用。
 *
 * 注意：KDoc 里不能出现「斜杠+星号」，Kotlin 块注释可嵌套，会吞掉整个文件。
 */
object AccountStore {

    private const val FILE = "accounts.json"
    private const val KEY_SELECTED = "selected_account_id"

    /** 账号来源。 */
    enum class AccountKind(val label: String) {
        /** DeepSeek 官方：地址固定，只需要用户名与 key。 */
        OFFICIAL("DeepSeek 官方"),
        /** 第三方：地址、协议、模型都要用户自己填。 */
        THIRD_PARTY("第三方")
    }

    /**
     * 三种主流协议。
     *
     * @param defaultBase 默认 API 地址，用户可改（自建网关、中转站很常见）
     * @param defaultModel 默认模型名，只在「拉不到模型列表」时当占位提示
     * @param piApi 写进 `settings.yaml` 的 `api` 字段值。
     *   **必须是 pi-ai 的 `KnownApi` 字面量**，写错了 dsh 会在解析配置阶段
     *   就以「无法服务的路由」失败。三个值取自
     *   `@earendil-works/pi-ai` 的 `dist/types.d.ts`：
     *   ```ts
     *   type KnownApi = "openai-completions" | ... | "anthropic-messages"
     *                  | ... | "google-generative-ai" | ...
     *   ```
     */
    enum class Protocol(
        val label: String,
        val defaultBase: String,
        val defaultModel: String,
        val piApi: String
    ) {
        OPENAI(
            "OpenAI 兼容",
            "https://api.openai.com/v1",
            "gpt-4o-mini",
            "openai-completions"
        ),
        ANTHROPIC(
            "Anthropic",
            "https://api.anthropic.com",
            "claude-3-5-haiku-20241022",
            "anthropic-messages"
        ),
        GEMINI(
            "Google Gemini",
            "https://generativelanguage.googleapis.com",
            "gemini-1.5-flash",
            "google-generative-ai"
        );

        companion object {
            val all: List<Protocol> = listOf(OPENAI, ANTHROPIC, GEMINI)

            fun fromName(n: String?): Protocol =
                all.firstOrNull { it.name == n } ?: OPENAI
        }
    }

    /**
     * 一个账号。
     *
     * @param id        内部唯一标识（随机 UUID），改名字不影响它
     * @param name      **只在启动器页面显示**，不传给 dsh
     * @param kind      官方 / 第三方
     * @param protocol  第三方才有意义；官方固定按 OpenAI 兼容处理
     * @param baseUrl   API 地址
     * @param apiKey    密钥
     * @param model     模型名。第三方账号的模型由「拉取列表后选择」得来，
     *                  不是用户手填的 —— 见 [discoverModels]。
     * @param routeKey  注入 dsh 时用的**提供方路由键**（`settings.yaml` 里
     *                  `llm-pi-ai.providers.<routeKey>` 的那个键）。必须遵守
     *                  dsh 的命名规范：小写字母 / 数字 / 连字符。旧版本存下的
     *                  账号没有这个字段，为空时由 [sanitizeRouteKey] 依名字现推。
     */
    data class Account(
        val id: String,
        val name: String,
        val kind: AccountKind,
        val protocol: Protocol,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val routeKey: String = ""
    ) {
        /** 实际用的协议：官方固定 OpenAI 兼容。 */
        val effectiveProtocol: Protocol
            get() = if (kind == AccountKind.OFFICIAL) Protocol.OPENAI else protocol

        /** 实际用的地址：官方固定 DeepSeek 端点。 */
        val effectiveBase: String
            get() = if (kind == AccountKind.OFFICIAL) OFFICIAL_BASE else baseUrl

        /** 实际用的模型：官方固定 deepseek-chat。 */
        val effectiveModel: String
            get() = if (kind == AccountKind.OFFICIAL) OFFICIAL_MODEL else model

        /**
         * 实际写入 dsh 的路由键。
         *
         * 官方账号不写 provider 配置（dsh 自带 `deepseek` 路由），这里返回
         * 保留名只为让日志/UI 有个可显示的东西；[DshProviderConfig] 只处理
         * 第三方账号。
         */
        val effectiveRouteKey: String
            get() = if (kind == AccountKind.OFFICIAL) {
                OFFICIAL_ROUTE
            } else {
                sanitizeRouteKey(routeKey.ifBlank { name })
            }

        /** 这条路由从哪个环境变量读密钥。 */
        val apiKeyEnv: String
            get() = apiKeyEnvFor(effectiveRouteKey)

        /** 列表里显示的一行副标题。 */
        val subtitle: String
            get() = if (kind == AccountKind.OFFICIAL) {
                "DeepSeek 官方 · $OFFICIAL_MODEL"
            } else {
                "${protocol.label} · ${effectiveModel.ifBlank { "未指定模型" }}"
            }

        /** 密钥打码显示，避免截图或投屏时泄露。 */
        val maskedKey: String
            get() = when {
                apiKey.length <= 8 -> "****"
                else -> apiKey.take(4) + "…" + apiKey.takeLast(4)
            }
    }

    /** DeepSeek 官方端点与模型。 */
    const val OFFICIAL_BASE = "https://api.deepseek.com"
    const val OFFICIAL_MODEL = "deepseek-chat"

    /** 官方账号对应的 dsh 内置路由键（我们**不**往 settings.yaml 里写它）。 */
    const val OFFICIAL_ROUTE = "deepseek"

    // ==============================================================
    // 提供方路由键（dsh 的命名规范）
    // ==============================================================

    /**
     * 路由键最大长度。
     *
     * dsh 会把它当提供方名显示在模型选择器里，太长会被截断；同时它还要
     * 派生出环境变量名（大写 + 下划线），过长在 shell 里也不好读。
     */
    private const val ROUTE_KEY_MAX = 32

    /**
     * pi-ai 内置的提供方路由名。
     *
     * ★ 这些键**绝对不能**拿来当第三方路由 ★
     *
     * 在 `llm-pi-ai` 里，同名路由的 `models` 列表是**替换**内置 catalog，
     * 不是扩充。用 `openai` 当路由键写一条 `models`，等于把用户原有的
     * OpenAI 模型表整个顶掉 —— 正好违反「只添加，不覆盖已有模型」。
     *
     * 取自 `@earendil-works/pi-ai` 的 `KnownProvider`。
     */
    private val RESERVED_ROUTES = setOf(
        "amazon-bedrock", "ant-ling", "anthropic", "google", "google-vertex",
        "openai", "azure", "openai-codex", "radius", "typesafe", "nvidia",
        "deepseek", "github-copilot", "xai", "groq", "cerebras", "openrouter",
        "vercel-ai-gateway", "zai", "zai-coding-cn", "mistral", "minimax",
        "minimax-cn", "moonshot", "moonshot-cn", "siliconflow", "together",
        "fireworks", "deepinfra", "hyperbolic", "ollama", "lmstudio"
    )

    /**
     * 把任意字符串收成合法的路由键。
     *
     * 规则（dsh / pi-ai 实际接受的范围）：小写字母、数字、连字符。
     *  · 大写 → 小写
     *  · 下划线、空格、点、其它分隔符 → 连字符
     *  · 中文等非 ASCII 字符直接丢弃（YAML 键里能放，但派生成环境变量名会坏）
     *  · 首字符不能是数字（否则 `123_API_KEY` 这种变量名多数读取方不认）
     *  · 撞上 [RESERVED_ROUTES] 时加 `-gw` 后缀，避免顶掉内置 catalog
     *
     * 收敛后为空（比如名字全是中文）时返回 `"gw"`，调用方再靠去重避让。
     */
    fun sanitizeRouteKey(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw.trim().lowercase()) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> sb.append(ch)
                // 这些一律视为分隔符，收成一个连字符
                ch == '-' || ch == '_' || ch == '.' || ch == ' ' || ch == '\t' -> sb.append('-')
                // 其它（含中文、标点）丢弃
            }
        }
        var s = sb.toString()
            .replace(Regex("-+"), "-")
            .trim('-')
        if (s.isEmpty()) s = "gw"
        // 首字符必须是字母：数字开头派生出的环境变量名不合规
        if (s.first() in '0'..'9') s = "gw-$s"
        if (s.length > ROUTE_KEY_MAX) {
            // 截断后可能留下尾部连字符，再修一次
            s = s.take(ROUTE_KEY_MAX).trimEnd('-').ifEmpty { "gw" }
        }
        if (s in RESERVED_ROUTES) s = "$s-gw".take(ROUTE_KEY_MAX)
        return s
    }

    /** 路由键是否合法（UI 用来决定「创建」按钮能不能点）。 */
    fun isValidRouteKey(s: String): Boolean =
        s.isNotEmpty() && s.length <= ROUTE_KEY_MAX &&
            s.first() in 'a'..'z' && s.all { it in 'a'..'z' || it in '0'..'9' || it == '-' } &&
            !s.endsWith("-") && !s.contains("--") && s !in RESERVED_ROUTES

    /**
     * 路由键 → 密钥环境变量名。
     *
     * `settings.yaml` 里只写**变量名**（`apiKeyEnv`），密钥本身仍走进程环境，
     * 这样密钥不会落进配置文件。两边必须用同一个函数推导，否则 dsh 会以
     * `MISSING_CREDENTIAL` 失败。
     */
    fun apiKeyEnvFor(routeKey: String): String =
        sanitizeRouteKey(routeKey).uppercase().replace('-', '_') + "_API_KEY"

    /**
     * 「获取 key」跳转的地址：DeepSeek 开放平台的用量/密钥页。
     *
     * 用这个页面而不是注册首页 —— 用户点「获取 key」时多半已经在用
     * DeepSeek，需要的是「我已有的 key 在哪」，而不是重新注册一遍。
     */
    const val OFFICIAL_KEY_URL = "https://platform.deepseek.com/usage"

    // ── 供 UI 订阅的状态 ────────────────────────────────────────────

    /** 账号列表（内存镜像，写操作会同步落盘）。 */
    val accounts = mutableStateListOf<Account>()

    /** 当前选中账号的 id；null 表示没有用户。 */
    var selectedId by mutableStateOf<String?>(null)
        private set

    /** 启动时要用的账号；没有就返回 null。 */
    fun selected(): Account? =
        selectedId?.let { id -> accounts.firstOrNull { it.id == id } }

    /** 是否已经加载过磁盘。避免每次重组都读文件。 */
    private var loaded = false

    /**
     * 从磁盘加载（幂等）。
     *
     * 首次调用读文件；之后直接返回。写操作都会即时落盘，
     * 所以不需要「重新读一遍」的入口 —— 那只会让内存状态和磁盘打架。
     */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
        val sel = prefs.getString(KEY_SELECTED, null)

        val f = File(context.filesDir, FILE)
        if (!f.isFile) return
        runCatching {
            val arr = JSONArray(f.readText().removePrefix("\uFEFF"))
            val list = ArrayList<Account>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { UUID.randomUUID().toString() }
                list.add(
                    Account(
                        id = id,
                        name = o.optString("name").ifBlank { "未命名" },
                        kind = runCatching {
                            AccountKind.valueOf(o.optString("kind"))
                        }.getOrDefault(AccountKind.THIRD_PARTY),
                        protocol = Protocol.fromName(o.optString("protocol")),
                        baseUrl = o.optString("baseUrl"),
                        apiKey = o.optString("apiKey"),
                        model = o.optString("model"),
                        // 旧文件没有 routeKey：留空，由 effectiveRouteKey 依名字现推
                        routeKey = o.optString("routeKey")
                    )
                )
            }
            accounts.clear()
            accounts.addAll(list)
            selectedId = sel?.takeIf { s -> list.any { it.id == s } }
        }
    }

    /** 落盘。失败只写日志，不打断 UI —— 用户刚填的内容不该因为写盘失败就消失。 */
    private fun persist(context: Context) {
        runCatching {
            val arr = JSONArray()
            accounts.forEach { a ->
                arr.put(
                    JSONObject().apply {
                        put("id", a.id)
                        put("name", a.name)
                        put("kind", a.kind.name)
                        put("protocol", a.protocol.name)
                        put("baseUrl", a.baseUrl)
                        put("apiKey", a.apiKey)
                        put("model", a.model)
                        put("routeKey", a.routeKey)
                    }
                )
            }
            File(context.filesDir, FILE).writeText(arr.toString(2))
        }.onFailure {
            DSHRunner.onLog("⚠️ 账号保存失败: ${it.message}")
        }
    }

    /** 新增一个账号，返回它的 id。 */
    fun add(context: Context, account: Account): String {
        accounts.add(account)
        // 第一个账号自动选中，省得用户还要多点一次
        if (selectedId == null) select(context, account.id)
        persist(context)
        return account.id
    }

    /** 删除账号。删的正好是选中项时，自动落到列表里剩下的第一个。 */
    fun remove(context: Context, id: String) {
        accounts.removeAll { it.id == id }
        if (selectedId == id) {
            selectedId = accounts.firstOrNull()?.id
            val prefs = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SELECTED, selectedId).apply()
        }
        persist(context)
    }

    /** 重命名。空名字忽略，避免列表里出现一条空白。 */
    fun rename(context: Context, id: String, newName: String) {
        val n = newName.trim()
        if (n.isEmpty()) return
        val idx = accounts.indexOfFirst { it.id == id }
        if (idx < 0) return
        accounts[idx] = accounts[idx].copy(name = n)
        persist(context)
    }

    /** 选中某个账号（启动时用它）。 */
    fun select(context: Context, id: String) {
        if (accounts.none { it.id == id }) return
        selectedId = id
        context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED, id).apply()
    }

    // ==============================================================
    // 连通性检测
    // ==============================================================

    /** 检测结果：成功，或带一句话的原因。 */
    sealed class CheckResult {
        data object Ok : CheckResult()
        data class Fail(val reason: String) : CheckResult()
    }

    /**
     * 用极小请求验证密钥与地址是否可用。
     *
     * **必须在后台线程调用**（内部会发同步 HTTP 请求）。
     * UI 侧统一包在 Thread 里，避免主线程网络异常。
     */
    fun check(account: Account): CheckResult {
        val proto = account.effectiveProtocol
        val base = account.effectiveBase.trimEnd('/')
        val key = account.apiKey.trim()
        val model = account.effectiveModel.trim()

        if (key.isBlank()) return CheckResult.Fail("密钥为空")
        if (base.isBlank()) return CheckResult.Fail("API 地址为空")
        if (model.isBlank()) return CheckResult.Fail("模型名为空")

        val (url, body) = when (proto) {
            Protocol.OPENAI -> "$base/chat/completions" to
                """{"model":"$model","messages":[{"role":"user","content":"ping"}],"max_tokens":1,"stream":false}"""

            Protocol.ANTHROPIC -> "$base/v1/messages" to
                """{"model":"$model","max_tokens":1,"messages":[{"role":"user","content":"ping"}]}"""

            Protocol.GEMINI -> "$base/v1beta/models/$model:generateContent?key=$key" to
                """{"contents":[{"parts":[{"text":"ping"}]}]}"""
        }

        var conn: HttpURLConnection? = null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 25_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "fcl-dsh/${BuildConfig.VERSION_NAME} (Android)")
                // 认证方式按协议分：这是三种协议唯一的硬差异
                when (proto) {
                    Protocol.OPENAI -> setRequestProperty("Authorization", "Bearer $key")
                    Protocol.ANTHROPIC -> {
                        setRequestProperty("x-api-key", key)
                        // Anthropic 要求显式版本头，缺了会直接 400
                        setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    // Gemini 的 key 放在查询参数里，已经在 url 中
                    Protocol.GEMINI -> Unit
                }
            }
            conn = c
            c.outputStream.use { it.write(body.toByteArray()) }

            when (val code = c.responseCode) {
                in 200..299 -> CheckResult.Ok
                401, 403 -> CheckResult.Fail("密钥无效或没有权限（HTTP $code）")
                404 -> CheckResult.Fail("地址或模型名不对（HTTP 404）")
                429 -> CheckResult.Fail("请求过于频繁或额度用尽（HTTP 429）")
                else -> {
                    // 把服务端的一句话带出来，比只说状态码有用得多
                    val detail = runCatching {
                        c.errorStream?.bufferedReader()?.use { it.readText() }
                    }.getOrNull().orEmpty()
                    val brief = detail.replace("\n", " ").take(120)
                    CheckResult.Fail(
                        if (brief.isBlank()) "HTTP $code" else "HTTP $code · $brief"
                    )
                }
            }
        } catch (e: Exception) {
            CheckResult.Fail(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ==============================================================
    // 拉取模型列表
    // ==============================================================

    /** 拉取模型列表的结果。 */
    sealed class ModelListResult {
        data class Ok(val models: List<String>) : ModelListResult()
        data class Fail(val reason: String) : ModelListResult()
    }

    /**
     * 问端点「你发布了哪些模型」。
     *
     * ## 为什么必须拉列表，而不是让用户手填模型名
     *
     * 第三方网关的模型名是**它自己的命名**（`qwen-max`、`deepseek-v3`、
     * 带日期后缀的快照名……），用户照着别人截图手填几乎必然拼错；而 dsh
     * 侧一旦模型名对不上，报的是 `UNKNOWN_MODEL`，用户根本看不出是拼写问题。
     * 三级协议都有标准的列表端点，直接问比猜可靠。
     *
     * ## 三个端点
     *
     * | 协议 | 端点 | 认证 |
     * |---|---|---|
     * | OpenAI 兼容 | `GET {base}/models` | `Authorization: Bearer` |
     * | Anthropic | `GET {base}/v1/models` | `x-api-key` + 版本头 |
     * | Gemini | `GET {base}/v1beta/models` | `?key=` 查询参数 |
     *
     * **必须在后台线程调用**（内部发同步 HTTP 请求）。
     */
    fun discoverModels(account: Account): ModelListResult {
        val proto = account.effectiveProtocol
        val key = account.apiKey.trim()
        // 去掉尾部斜杠；两个协议的路径里已经带 v1/v1beta，先剥掉免得拼成 /v1/v1
        var base = account.effectiveBase.trim().trimEnd('/')
        if (base.isBlank()) return ModelListResult.Fail("API 地址为空")
        if (key.isBlank()) return ModelListResult.Fail("密钥为空")

        val url = when (proto) {
            Protocol.OPENAI -> "$base/models"
            Protocol.ANTHROPIC -> {
                if (base.endsWith("/v1")) base = base.dropLast(3)
                "$base/v1/models"
            }
            Protocol.GEMINI -> {
                if (base.endsWith("/v1beta")) base = base.dropLast(7)
                "$base/v1beta/models?key=$key"
            }
        }

        var conn: HttpURLConnection? = null
        return try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 25_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "fcl-dsh/${BuildConfig.VERSION_NAME} (Android)")
                when (proto) {
                    Protocol.OPENAI -> setRequestProperty("Authorization", "Bearer $key")
                    Protocol.ANTHROPIC -> {
                        setRequestProperty("x-api-key", key)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    Protocol.GEMINI -> Unit
                }
            }
            conn = c

            val code = c.responseCode
            if (code !in 200..299) {
                val detail = runCatching {
                    c.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty().replace("\n", " ").take(120)
                val reason = when (code) {
                    401, 403 -> "密钥无效或没有权限（HTTP $code）"
                    404 -> "地址不对，或该网关不支持模型列表端点（HTTP 404）"
                    429 -> "请求过于频繁或额度用尽（HTTP 429）"
                    else -> if (detail.isBlank()) "HTTP $code" else "HTTP $code · $detail"
                }
                return ModelListResult.Fail(reason)
            }

            val text = c.inputStream.bufferedReader().use { it.readText() }
            val models = parseModelList(proto, text)
            if (models.isEmpty()) {
                ModelListResult.Fail("端点没有公布任何可用模型")
            } else {
                ModelListResult.Ok(models)
            }
        } catch (e: Exception) {
            ModelListResult.Fail(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /**
     * 解析列表响应。
     *
     * OpenAI / Anthropic 都是 `{"data":[{"id":…}]}`；Gemini 是
     * `{"models":[{"name":"models/gemini-…"}]}`，名字要剥掉 `models/` 前缀。
     * 顺带兼容个别网关直接返回顶层数组 `[{"id":…}]` 的情况。
     */
    private fun parseModelList(proto: Protocol, text: String): List<String> {
        val out = LinkedHashSet<String>()

        fun addId(raw: String?) {
            val id = raw?.trim().orEmpty()
            if (id.isNotEmpty() && id != "models/" && !out.contains(id)) out.add(id)
        }

        runCatching {
            val trimmed = text.trim().removePrefix("\uFEFF")
            if (trimmed.startsWith("[")) {
                // 少数网关直接返回数组
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    addId(arr.optJSONObject(i)?.optString("id"))
                }
            } else {
                val o = JSONObject(trimmed)
                if (proto == Protocol.GEMINI) {
                    val arr = o.optJSONArray("models") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val m = arr.optJSONObject(i) ?: continue
                        // 只保留能对话的模型：embedding / 图像生成之类列进来也选不了
                        val methods = m.optJSONArray("supportedGenerationMethods")
                        if (methods != null) {
                            var chat = false
                            for (j in 0 until methods.length()) {
                                if (methods.optString(j) == "generateContent") chat = true
                            }
                            if (!chat) continue
                        }
                        addId(m.optString("name").removePrefix("models/"))
                    }
                } else {
                    val arr = o.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        addId(arr.optJSONObject(i)?.optString("id"))
                    }
                }
            }
        }

        return out.sorted()
    }

    // ==============================================================
    // 注入 dsh
    // ==============================================================

    /**
     * 算出该注入 dsh 的环境变量，并把结构化描述写进 `$DSH_HOME`。
     *
     * ## ★ 只注入密钥，绝不覆盖地址与模型 ★
     *
     * 上一版把 `OPENAI_BASE_URL` / `DEEPSEEK_BASE_URL` 也一起注入了，
     * 真机实测启动即失败：
     *
     * ```
     * DeepSeek Messages request failed (404)
     * ```
     *
     * 原因是 dsh 自己的 DeepSeek provider 有它自己的端点约定（走的是
     * messages 风格接口），我们用通用变量名把 base URL 覆盖成
     * `https://api.deepseek.com` 之后，它拼出来的路径在服务端根本不存在。
     *
     * **端点与模型由 dsh 自己配置最靠谱，启动器不该覆盖。** 所以这里只给
     * 密钥（各家通用的 `*_API_KEY`），地址与模型只写进 `android-account.json`
     * 供需要的插件读取，不碰 dsh 的既有配置。
     *
     * ## 与 dsh 已有账号共存（而不是二选一）
     *
     * 这一条是**核心设计约束**，也是之前踩过的坑。
     *
     * ### dsh 自己的解析链（来自其模型设置页的提示）
     *
     * ```
     * 优先使用已保存的凭据
     * 环境后备（按顺序）：DEEPSEEK_API_KEY
     *   来源依次为：启动环境、$cwd/.env、$DSH_HOME/.env
     * ```
     *
     * 这条链说明了两件关键事实：
     *
     *  1. **已保存的凭据永远优先** —— dsh 里自己配的账号不会被启动器顶掉。
     *     所以「共存」不是我们把优先级让出去，而是它本来就在上面。
     *  2. **`.env` 是官方支持的正式通道**，不是野路子。落盘后对后续启动
     *     也生效，比只在本次进程里存在的环境变量更持久。
     *
     * ### 三通道分工
     *
     * | 通道 | 承载什么 | 与 dsh 自有账号的关系 |
     * |---|---|---|
     * | 进程环境变量 | 仅「选中」的那一个密钥 | 缺位才补，已有值一律不覆盖 |
     * | `$DSH_HOME/.env` | 同一个密钥，落盘持久化 | 只维护带标记的区块，其余内容原样保留 |
     * | `android-account.json` | **全部**账号（数组 + 选中 id） | 合并写，不丢弃已有键 |
     *
     * 前两条是 dsh 认得的「环境后备」，第三条是给插件/工具读的完整清单。
     * 用户不必在启动器账号与 dsh 账号之间做选择 —— 两者并列存在，
     * 由 dsh 自己的优先级决定谁生效。
     *
     * 早期版本只把「选中」那一个写进 JSON，启动器里的其它账号永远用不上，
     * 而且完全没有写 `.env`，等于放弃了官方通道 —— 那才是真正的二选一。
     *
     * 之所以对已有值这么保守，是因为更早一版曾无条件覆盖
     * `DEEPSEEK_BASE_URL`，直接把用户原本能用的 dsh 账号打成 404。
     * 用户配好的东西必须保留。
     *
     * ## 为什么返回 Map 而不是直接写进 ProcessBuilder 的环境
     *
     * `ProcessBuilder.environment()` 在 Kotlin 里是平台类型，直接当
     * `MutableMap` 形参传递会编译不过（推断成只读 `Map`）。返回一份
     * 待写变量表、由调用方自己 `env[k] = v`，两头都不需要强转。
     *
     * ## 显示名不注入
     *
     * 账号名（[Account.name]）只用于启动器界面，**不写进任何注入内容** ——
     * 它是给人看的标签，对 dsh 没有语义。
     *
     * @param existing dsh 进程已有的环境变量。这里出现的键不会被覆盖。
     * @return 需要**新增**到 dsh 环境变量的键值；没有选中账号时返回空表
     */
    fun applyToEnvironment(
        context: Context,
        existing: Map<String, String> = emptyMap()
    ): Map<String, String> {
        val account = selected() ?: return emptyMap()
        val proto = account.effectiveProtocol
        val base = account.effectiveBase.trimEnd('/')
        val key = account.apiKey.trim()
        val model = account.effectiveModel.trim()

        // 候选变量：按协议给各家通用的密钥变量名
        val candidates = LinkedHashMap<String, String>(4)
        when (proto) {
            Protocol.OPENAI -> {
                // DeepSeek 官方也走这里 —— 它本来就是 OpenAI 兼容
                candidates["OPENAI_API_KEY"] = key
                if (account.kind == AccountKind.OFFICIAL) {
                    // 官方额外给一份 DeepSeek 专属变量名，备 dsh 内置取用
                    candidates["DEEPSEEK_API_KEY"] = key
                }
            }
            Protocol.ANTHROPIC -> candidates["ANTHROPIC_API_KEY"] = key
            Protocol.GEMINI -> {
                candidates["GEMINI_API_KEY"] = key
                candidates["GOOGLE_API_KEY"] = key
            }
        }

        // ── 第三方账号：再补一个**路由专属**变量 ────────────────────────
        //
        // settings.yaml 里写的 apiKeyEnv 就是这个变量名。dsh 按路由解析凭据，
        // 不复用上面那些通用名 —— 通用名会串味：把中转站的 key 塞进
        // OPENAI_API_KEY，dsh 内置的 openai 路由会拿它去连 api.openai.com。
        if (account.kind == AccountKind.THIRD_PARTY) {
            candidates[account.apiKeyEnv] = key
        }

        // 已有值的一律跳过：不覆盖 dsh 自己的配置
        val out = LinkedHashMap<String, String>(candidates.size)
        for ((k, v) in candidates) {
            val has = existing[k]
            if (!has.isNullOrBlank()) {
                DSHRunner.onLog("ℹ️ $k 已由 dsh 配置，保留原值（启动器不覆盖）")
                continue
            }
            out[k] = v
        }

        // 结构化描述：合并写，只更新本启动器负责的字段
        runCatching {
            val home = DSHRunner.dshHome(context)
            home.mkdirs()
            val file = File(home, "android-account.json")
            // 先读现有内容。它不是我们独占的文件 —— 可能被别的插件写过。
            val o = if (file.isFile) {
                runCatching {
                    JSONObject(file.readText().removePrefix("\uFEFF"))
                }.getOrNull() ?: JSONObject()
            } else {
                JSONObject()
            }

            // ── 关键：写**全部**账号，而不是只有选中的那个 ──────────────
            //
            // 环境变量天然只能表达一个密钥，但 dsh 的 llm-* 插件支持多账号。
            // 若这里只写选中项，启动器的多个账号就永远只有第一个能用 ——
            // 那才是真正的「二选一」。
            //
            // 写成数组后，dsh 侧（或插件）能读到完整列表，启动器账号与
            // dsh 自己注册的账号是**并列**关系，不是互相顶掉。
            val all = JSONArray()
            accounts.forEach { a ->
                all.put(
                    JSONObject().apply {
                        put("id", a.id)
                        put("name", a.name)
                        put("kind", a.kind.name)
                        put("protocol", a.effectiveProtocol.name)
                        put("baseUrl", a.effectiveBase.trimEnd('/'))
                        put("model", a.effectiveModel.trim())
                        // 密钥照写：这份文件在 DSH_HOME 里，导出整合包时会按
                        // 凭据规则被排除（名字含 account），不会漏进分享包。
                        put("apiKey", a.apiKey.trim())
                        put("official", a.kind == AccountKind.OFFICIAL)
                        put("selected", a.id == selectedId)
                    }
                )
            }
            o.put("format", 2)
            o.put("accounts", all)
            o.put("selectedId", selectedId ?: "")

            // 兼容字段：把选中账号平铺一份。
            // 早期插件可能只认顶层 protocol/baseUrl/model/apiKey，
            // 保留它们可以让那些读取方继续工作，不必同步升级。
            o.put("protocol", proto.name)
            o.put("baseUrl", base)
            o.put("model", model)
            o.put("apiKey", key)
            o.put("official", account.kind == AccountKind.OFFICIAL)
            o.put("name", account.name)

            file.writeText(o.toString(2))
            DSHRunner.onLog("ℹ️ 已写入 ${accounts.size} 个账号到 android-account.json（选中: ${account.name}）")
        }.onFailure {
            DSHRunner.onLog("⚠️ 写 android-account.json 失败: ${it.message}")
        }

        // ── 写 $DSH_HOME/.env（dsh 官方支持的载入口）──────────────────
        //
        // dsh 模型设置页的提示原文：
        //
        //   优先使用已保存的凭据。此处显示的环境后备按以下顺序排列：
        //   DEEPSEEK_API_KEY。来源依次为启动环境、$cwd/.env、$DSH_HOME/.env。
        //
        // 所以 `.env` 是官方认可的正式通道，不是野路子。写它的好处：
        //  · 进程环境变量只在本次运行有效，`.env` 落盘后对后续启动也生效；
        //  · dsh 自己的 UI 会把它识别为「环境后备」，与已保存凭据并列展示，
        //    而不是被当成外来注入。
        //
        // ★ 只写密钥类变量，绝不写 base URL / 模型 ★
        //
        // 这是踩过的坑：dsh 的 DeepSeek provider 有自己的端点约定，
        // 覆盖 DEEPSEEK_BASE_URL 会让它拼出不存在的路径，直接 404。
        // [candidates] 里全是 *_API_KEY，正好符合这个约束。
        runCatching {
            val envFile = File(DSHRunner.dshHome(context), ".env")
            writeManagedEnvBlock(envFile, candidates)
        }.onFailure {
            DSHRunner.onLog("⚠️ 写 .env 失败: ${it.message}")
        }

        return out
    }

    /** `.env` 里受管区块的起止标记（只替换这一段，其余内容原样保留）。 */
    private const val ENV_BEGIN = "# >>> fcl-dsh account (auto-generated) >>>"
    private const val ENV_END = "# <<< fcl-dsh account <<<"

    /**
     * 把 [pairs] 写进 `$DSH_HOME/.env` 的**受管区块**。
     *
     * ## 为什么用标记区块而不是整文件覆盖
     *
     * `.env` 是用户和 dsh 共用的文件：里面可能有人手写的注释、其它提供商
     * 的密钥、以及 dsh 自己写的变量。整文件覆盖会把这些全冲掉。
     *
     * 所以只维护两个标记之间的一段，每次替换整段。好处是：
     *  · 用户在标记外写的东西永远不受影响；
     *  · 换账号时旧密钥会被正确清掉（不会两个密钥同时留在文件里）。
     *
     * 标记外若已存在同名变量（用户手写的 `DEEPSEEK_API_KEY`），
     * **不删除也不改动** —— 按 dsh 的优先级，同一文件内的解析顺序由它自己
     * 决定，我们不该替它裁决。
     */
    private fun writeManagedEnvBlock(file: File, pairs: Map<String, String>) {
        val old = if (file.isFile) {
            runCatching { file.readText() }.getOrDefault("")
        } else {
            ""
        }

        // 剥掉旧的受管区块，其余行原样留下
        val keep = ArrayList<String>()
        var inside = false
        for (line in old.lines()) {
            val t = line.trim()
            when {
                t == ENV_BEGIN -> inside = true
                t == ENV_END -> inside = false
                !inside -> keep.add(line)
            }
        }
        // 去掉尾部空行，避免每次写入都累积空白
        while (keep.isNotEmpty() && keep.last().isBlank()) keep.removeAt(keep.size - 1)

        val sb = StringBuilder()
        keep.forEach { sb.append(it).append('\n') }
        if (keep.isNotEmpty()) sb.append('\n')

        if (pairs.isNotEmpty()) {
            sb.append(ENV_BEGIN).append('\n')
            pairs.forEach { (k, v) ->
                sb.append(k).append('=').append(quoteIfNeeded(v)).append('\n')
            }
            sb.append(ENV_END).append('\n')
        }

        file.writeText(sb.toString())
    }

    /**
     * `.env` 的取值按需加引号。
     *
     * 密钥本身通常不需要，但自建网关的地址、带空格的值会被 `.env` 解析器
     * 截断或误判 `#` 为注释起点，所以见到这些字符就包一层双引号。
     */
    private fun quoteIfNeeded(v: String): String {
        val needsQuote = v.any { it == ' ' || it == '#' || it == '"' || it == '\'' || it == '=' }
        if (!needsQuote) return v
        return "\"" + v.replace("\"", "\\\"") + "\""
    }

    /**
     * 给日志用的一句话摘要（**不含密钥**）。
     *
     * 启动时打印这一行，用户能确认「这次用的是哪个账号」，
     * 而日志是可以随手贴出来的，所以绝不能带密钥。
     */
    fun describeForLog(): String? {
        val a = selected() ?: return null
        return "${a.name}（${a.subtitle}，密钥 ${a.maskedKey}）"
    }
}
