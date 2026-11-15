package com.example.fcldsh

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 配置剪贴板的一条记录：**标题 + 正文**。
 *
 * 正文是纯文本，不做任何解析。这样它既能装一段 shell 命令，
 * 也能装一份 `cordis.patch.yml` 片段，还能装一段操作方案 ——
 * 三者的共同点只有「一段以后可能还要用的文字」。
 *
 * @property id 稳定标识，用于改/删。不用标题当主键：标题允许重复
 *   （同一个命令在两种场景下各存一份是合理的）。
 */
internal data class ClipEntry(
    val id: String,
    val category: ClipCategory,
    val title: String,
    val content: String,
    val updatedAt: Long
)

/**
 * 剪贴板的三个分类。
 *
 * 分类只影响**分组显示**，不改变存储格式 —— 一条记录属于哪个分类
 * 是它自己的字段，换分类就是改那个字段（[ConfigClipboard.update] 支持）。
 */
internal enum class ClipCategory(val label: String, val hint: String) {
    COMMAND("命令", "要反复敲的命令行、a11y 调用、npm 参数"),
    PLAN("方案", "排查思路、实施步骤、给 agent 的操作约定"),
    MCP("MCP", "MCP 服务器配置片段：cordis.patch.yml 条目、mcp.json 片段");

    companion object {
        /** 按显示顺序。不用 `entries`，兼容老一些的 Kotlin 编译器。 */
        val all: List<ClipCategory> = listOf(COMMAND, PLAN, MCP)

        fun ofName(name: String?): ClipCategory =
            all.firstOrNull { it.name == name } ?: COMMAND
    }
}

/**
 * 左侧的**筛选视图**：比 [ClipCategory] 多一个「全部」。
 *
 * ## 为什么不直接把 ALL 塞进 ClipCategory
 *
 * [ClipCategory] 是**存储字段**，每条记录必须属于其中一个 ——
 * 「全部」不是一个能存进去的归属，它只是「不筛选」。
 * 混在一起的话，[ConfigClipboard.add] 就多出一个「分类为 ALL」的非法状态，
 * 列表里也会出现归不进任何分类的孤儿记录。
 *
 * 所以分成两套：存储用 [ClipCategory]，界面筛选用 [ClipFilter]，
 * 由 [category] 建立映射（null = 不筛选）。
 */
internal enum class ClipFilter(val label: String, val hint: String, val category: ClipCategory?) {
    ALL("全部", "三个分类合在一起看", null),
    COMMAND("命令", ClipCategory.COMMAND.hint, ClipCategory.COMMAND),
    PLAN("方案", ClipCategory.PLAN.hint, ClipCategory.PLAN),
    MCP("MCP", ClipCategory.MCP.hint, ClipCategory.MCP);

    companion object {
        val all: List<ClipFilter> = listOf(ALL, COMMAND, PLAN, MCP)
    }
}

/**
 * 与实例无关的**配置剪贴板**。
 *
 * ## 为什么要做这个东西（以及它取代了什么）
 *
 * 原来这一页是「MCP 管理」，靠遍历实例私有目录去找 MCP 配置文件。
 * 那条路有几个绕不开的问题：
 *
 * - **位置靠猜**。dsh 的 MCP 可以写在 `mcp.json`、profile 补丁层的
 *   `cordis.patch.yml`、`local-plugins/<包>/cordis.patch.yml`、
 *   甚至插件自己的数据目录里，形态各不相同（JSON / JSONC / YAML）。
 *   每发现一种就得往扫描逻辑里加一条，永远补不完；
 * - **读了也改不了**。补丁层与插件包内的配置都是**共享文件**，
 *   里面还躺着别人的配置，面板里按 JSON 重写会把整份 YAML 写坏，
 *   所以只能「只读展示」—— 一个只能看不能动的管理页，价值很低；
 * - **跟着实例走**。切一个实例就看不到另一个实例的配置，
 *   而用户实际想要的是「这段配置我记下来，换个实例也能粘回去」。
 *
 * 所以改成现在这样：**不读任何实例目录**，只存用户自己粘进来的文本。
 * 位置无关、实例无关，也就不存在「扫描不到」这个故障模式。
 *
 * ## 存哪
 *
 * 独立的 SharedPreferences（`config_clipboard`），整张表序列化成
 * 一个 JSON 数组塞进单个键。条目数量在几十的量级，读写都是毫秒级，
 * 不需要数据库；而放在 SharedPreferences 里也不用担心
 * 「实例目录被删/被导出带走」—— 它是**应用级**的东西。
 *
 * ## 为什么要有 [revision]
 *
 * [list] 是普通函数，Compose 不会因为它背后的偏好文件变了而重组。
 * 把修订号做成快照状态，读它的地方就自动订阅了它；写操作 +1，
 * 所有显示点立刻重组。这个模式在 [VersionLabels] 里已经用过一次，
 * 比在每处调用点各自维护 `tick++` 可靠（漏一处就不刷新）。
 */
internal object ConfigClipboard {

    private const val FILE = "config_clipboard"
    private const val KEY = "entries"

    /**
     * 标题长度上限。
     *
     * 列表里只显示一行，太长会被省略号吃掉 —— 40 与 [VersionLabels.MAX_LEN]
     * 保持一致，两个「起名字」的地方观感统一。
     */
    const val MAX_TITLE = 40

    /**
     * 正文长度上限。
     *
     * SharedPreferences 单个值没有硬上限，但整份表是一个字符串 ——
     * 200 KB 已经能装下几千行配置，再大就该用文件了。
     * 超限时截断并保留尾部（配置的关键部分通常在后面）。
     */
    const val MAX_CONTENT = 200_000

    /** 修订号：写操作后 +1，读它的组合会被通知（见类注释）。 */
    var revision by mutableStateOf(0)
        private set

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ── 读 ────────────────────────────────────────────────────────────

    /**
     * 全部条目，**最近修改的排前面**。
     *
     * 同一分类内按 [ClipEntry.updatedAt] 降序：用户刚动过的那条最可能是
     * 现在要用的。不做「置顶」之类的附加概念 —— 目前没有这个需求。
     */
    fun all(context: Context): List<ClipEntry> {
        revision.let { }  // 订阅（见类注释）
        val raw = runCatching { prefs(context).getString(KEY, null) }.getOrNull()
            ?: return emptyList()
        if (raw.isBlank()) return emptyList()

        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ClipEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = runCatching { arr.optJSONObject(i) }.getOrNull() ?: continue
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
            val content = o.optString("content")
            out.add(
                ClipEntry(
                    id = id,
                    category = ClipCategory.ofName(o.optString("category")),
                    title = o.optString("title").ifBlank { untitled(content) },
                    content = content,
                    updatedAt = o.optLong("updatedAt", 0L)
                )
            )
        }
        return out.sortedByDescending { it.updatedAt }
    }

    /** 某个分类下的条目。 */
    fun list(context: Context, category: ClipCategory): List<ClipEntry> =
        all(context).filter { it.category == category }

    /** 各分类的条目数，给左栏统计用。 */
    fun counts(context: Context): Map<ClipCategory, Int> {
        val entries = all(context)
        return ClipCategory.all.associateWith { c -> entries.count { it.category == c } }
    }

    // ── 写 ────────────────────────────────────────────────────────────

    /**
     * 新增一条，返回它的 id。
     *
     * 标题留空时用正文首行兜底（见 [untitled]）：列表里总得有个能认的标题，
     * 而用户粘完一大段配置后往往懒得再起名。
     */
    fun add(context: Context, category: ClipCategory, title: String, content: String): String {
        val entry = ClipEntry(
            id = UUID.randomUUID().toString(),
            category = category,
            title = normalizeTitle(title, content),
            content = clipContent(content),
            updatedAt = System.currentTimeMillis()
        )
        write(context, all(context) + entry)
        return entry.id
    }

    /**
     * 改一条。id 不存在时什么都不做（可能被另一处删掉了）。
     *
     * [category] 允许变：把一条命令归类到「方案」是合理操作。
     */
    fun update(
        context: Context,
        id: String,
        category: ClipCategory,
        title: String,
        content: String
    ) {
        val entries = all(context)
        if (entries.none { it.id == id }) return
        val next = entries.map { e ->
            if (e.id != id) {
                e
            } else {
                e.copy(
                    category = category,
                    title = normalizeTitle(title, content),
                    content = clipContent(content),
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        write(context, next)
    }

    /** 删一条。 */
    fun delete(context: Context, id: String) {
        val entries = all(context)
        val next = entries.filterNot { it.id == id }
        if (next.size == entries.size) return
        write(context, next)
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    private fun write(context: Context, entries: List<ClipEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            // 手写 JSONObject 而不是反射/序列化库：只有五个字段，
            // 引一个库来干这件事不值得，而且格式必须**向前兼容**
            // （老版本读到不认识的字段会忽略，不认识整体也不会崩 —— 见 all()）
            val o = JSONObject()
            o.put("id", e.id)
            o.put("category", e.category.name)
            o.put("title", e.title)
            o.put("content", e.content)
            o.put("updatedAt", e.updatedAt)
            arr.put(o)
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
        revision++
    }

    private fun normalizeTitle(title: String, content: String): String =
        title.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(MAX_TITLE)
            .ifBlank { untitled(content) }

    /**
     * 没有标题时用正文推一个：取第一个非空行，压掉多余空白。
     *
     * 这样粘一段 `npm install …` 进来、标题留空，列表里显示的就是
     * 那条命令本身，而不是「未命名」这种看不出内容的占位。
     */
    private fun untitled(content: String): String {
        val first = content.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        return first.take(MAX_TITLE).ifBlank { "未命名" }
    }

    /**
     * 正文裁剪。
     *
     * 超长时**保留尾部**：配置文件的关键部分（servers 列表、命令参数）
     * 通常在后面，掐掉头比掐掉尾丢的信息少。
     * 换行统一成 `\n`，避免从 Windows 粘贴进来的 `\r\n` 在编辑器里显示成方块。
     */
    private fun clipContent(content: String): String {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.length <= MAX_CONTENT) return normalized
        return "…（前面已截断）\n" + normalized.takeLast(MAX_CONTENT)
    }
}
