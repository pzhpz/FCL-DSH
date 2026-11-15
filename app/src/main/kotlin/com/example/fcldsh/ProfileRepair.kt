package com.example.fcldsh

import android.content.Context
import com.DSHRunner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 修复 `profiles/web` 的清单，让 dsh **内部**的插件市场与卸载重新可用。
 *
 * ## 真实根因（由真机日志确定，别再猜）
 *
 * ```
 * ERR_PNPM_NO_MATCHING_VERSION
 * × removing a package
 * ├─▶ Failed to resolve dependency tree: No matching version found for
 * │   dsh-our-free-model@2.8.3 while fetching it from https://registry.npmjs.org/
 * help: The latest release of dsh-our-free-model is "2.1.0".
 * ```
 *
 * 三件事同时成立，缺一条都不会有这个现象：
 *
 * 1. **dsh 用的是 pnpm**（日志里全是 `pnpm-native` / `--registry=`），
 *    不是 npm。所以「删 `package-lock.json`」这种修法对它**完全无效** ——
 *    真正要动的是 `pnpm-lock.yaml`。
 * 2. `package.json` 的 `dependencies` 里有一条 **`dsh-our-free-model@2.8.3`**，
 *    而该包**最新只发布到 2.1.0**，这个版本在任何 registry 上都不存在
 *    （换 npmmirror 镜像同样失败，正好佐证了这点）。
 * 3. pnpm 的 `add` **和** `remove` 都要先**解析整棵依赖树**。只要树里有一个
 *    无法满足的声明，两个操作就一起失败 —— 这就是「市场说无合适版本」
 *    与「卸载报错」是同一个病根的证明。
 *
 * 所以修复的核心不是删锁文件，而是**把不可能满足的版本声明改掉**。
 *
 * ## 修复顺序（每一步都必须在前一步之后）
 *
 * 1. **联网校验每个依赖的版本是否真的存在**，把不存在的重新指向已发布的最新版；
 *    整包都不存在的直接摘除（连着从 `bundles` 里摘）。
 * 2. **删掉 pnpm 的锁与元数据**（`pnpm-lock.yaml`、`node_modules/.modules.yaml`），
 *    让 pnpm 以改好的 `package.json` 为准重新解析。
 * 3. 摘掉 `dsh.profile.bundles` 里指向**磁盘上不存在**的包。
 * 4. 把 `.bak-*` 备份移出 profile 目录（那是 dsh 会扫描的目录）。
 *
 * ## 为什么必须先改清单再删锁
 *
 * 反过来的话，pnpm 会拿旧的 `pnpm-lock.yaml` 去校验新的 `package.json`，
 * 报一句「lockfile 与 manifest 不一致」然后仍然失败。顺序在这里是有语义的。
 *
 * ## 「宁可少修」的边界
 *
 * - 只有当**声明的那个基础版本确实不在已发布列表里**时才改，且改前一定先备份；
 * - 整包 404 才摘除，网络失败（超时/5xx）一律**不动**并如实报告 ——
 *   把「查不到」当成「不存在」去删条目，会把用户的清单删空。
 */
internal object ProfileRepair {

    /** 备份目录（相对 HOME，位于 profile 之外，dsh 不会扫描）。 */
    private const val BACKUP_DIR = ".fcl-backups/profiles-web"

    /** 与 [PluginDownloader] 保持同一个官方源，避免两处结论不一致。 */
    private const val REGISTRY = "https://registry.npmjs.org/"

    /** 单次联网校验的上限。依赖再多也不该让「修复」等上几分钟。 */
    private const val MAX_NET_CHECKS = 40

    private const val NET_TIMEOUT_MS = 8_000

    private fun profileDir(context: Context): File =
        File(DSHRunner.dshHome(context), "profiles/web")

    // ────────────────────────── 数据结构 ──────────────────────────

    /** 一条「声明的版本在 registry 上不存在」的依赖。 */
    data class Pin(
        val name: String,
        /** 清单里写的原始声明，如 `2.8.3` 或 `^2.8.3`。 */
        val declared: String,
        /** 该包已发布的最新版；null 表示整包在 registry 上都找不到。 */
        val latest: String?
    )

    /** 一次诊断的结果。 */
    data class Report(
        val profile: File,
        val manifestExists: Boolean,
        /** `dependencies` 的条目数。 */
        val deps: Int,
        /** `dsh.profile.bundles` 的条目数。 */
        val bundles: Int,
        /** 清单里登记、但 `node_modules` 下没有的包名。 */
        val missing: List<String>,
        /** `node_modules` 里存在、但清单没登记的包（只报告，不处理）。 */
        val unlisted: List<String>,
        /** 需要删掉的锁 / 元数据文件（相对 profile）。 */
        val lockFiles: List<String>,
        /** profile 目录里的 `.bak-*` 文件。 */
        val strayBackups: List<String>,
        /** 联网校验发现的坏版本声明；[diagnose] 不填（不联网）。 */
        val badPins: List<Pin> = emptyList(),
        /** 联网校验是否被网络问题打断（此时 [badPins] 不完整）。 */
        val netFailed: Boolean = false,
        val parseError: String? = null
    )

    // ────────────────────────── 诊断 ──────────────────────────

    /**
     * 本地诊断（**不联网**，可在组合期调用）。
     *
     * 联网那部分单独走 [checkPins]，因为它会在主线程上卡住 ——
     * 界面上只用这里的摘要，坏版本声明等用户点「修复」时才查。
     */
    fun diagnose(context: Context): Report {
        val dir = profileDir(context)
        val manifest = File(dir, "package.json")
        val modules = File(dir, "node_modules")

        if (!manifest.isFile) {
            return Report(
                profile = dir, manifestExists = false, deps = 0, bundles = 0,
                missing = emptyList(), unlisted = emptyList(),
                lockFiles = emptyList(), strayBackups = emptyList(),
                parseError = "没有 profiles/web/package.json"
            )
        }

        val raw = runCatching { manifest.readText().removePrefix("\uFEFF") }.getOrNull()
        if (raw == null) {
            return Report(
                profile = dir, manifestExists = true, deps = 0, bundles = 0,
                missing = emptyList(), unlisted = emptyList(),
                lockFiles = emptyList(), strayBackups = emptyList(),
                parseError = "清单读取失败"
            )
        }

        val obj = runCatching { JSONObject(raw) }.getOrNull()
        if (obj == null) {
            return Report(
                profile = dir, manifestExists = true, deps = 0, bundles = 0,
                missing = emptyList(), unlisted = emptyList(),
                lockFiles = emptyList(), strayBackups = emptyList(),
                parseError = "清单不是合法 JSON（可能是 JSONC 或写坏了）"
            )
        }

        val depsNode = obj.optJSONObject("dependencies")
        val bundlesArr = obj.optJSONObject("dsh")
            ?.optJSONObject("profile")
            ?.optJSONArray("bundles")

        // 清单里登记的包名（dependencies ∪ bundles）
        val declared = LinkedHashSet<String>()
        depsNode?.let { d ->
            val keys = d.keys()
            while (keys.hasNext()) declared.add(keys.next())
        }
        bundlesArr?.let { a ->
            for (i in 0 until a.length()) {
                val n = a.optString(i)
                if (n.isNotBlank()) declared.add(n)
            }
        }

        val missing = declared.filter { !packagePresent(modules, it) }.sorted()

        // node_modules 里存在、但清单没登记的顶层包（@scope 展开一层）
        val unlisted = ArrayList<String>(8)
        runCatching { modules.listFiles() }.getOrNull()?.forEach { f ->
            if (!f.isDirectory) return@forEach
            if (f.name == ".bin") return@forEach
            if (f.name.startsWith("@")) {
                runCatching { f.listFiles() }.getOrNull()?.forEach { sub ->
                    val full = "${f.name}/${sub.name}"
                    if (full !in declared) unlisted.add(full)
                }
            } else if (f.name !in declared) {
                unlisted.add(f.name)
            }
        }

        // ★ pnpm，不是 npm ★
        // 只列真正会骗到 pnpm 的那两个：锁文件与它的元数据。
        // `node_modules/.modules.yaml` 记录着 pnpm 上次的「谁是谁的依赖」，
        // 手动塞过包之后它会和实际情况对不上，一并删掉最稳。
        val locks = listOf(
            "pnpm-lock.yaml",
            "node_modules/.modules.yaml"
        ).filter { File(dir, it).isFile }

        val stray = runCatching { dir.listFiles() }.getOrNull().orEmpty()
            .filter { it.name.contains(".bak-") }
            .map { it.name }
            .sorted()

        return Report(
            profile = dir,
            manifestExists = true,
            deps = depsNode?.length() ?: 0,
            bundles = bundlesArr?.length() ?: 0,
            missing = missing,
            unlisted = unlisted.sorted(),
            lockFiles = locks,
            strayBackups = stray,
            parseError = null
        )
    }

    /** `node_modules/<pkg>/package.json` 是否存在（包确实落在磁盘上）。 */
    private fun packagePresent(modules: File, pkg: String): Boolean {
        val dir = File(modules, pkg)
        return dir.isDirectory && File(dir, "package.json").isFile
    }

    // ────────────────────────── 联网校验 ──────────────────────────

    /**
     * 联网校验 `dependencies` 里每个声明的版本是否真的存在。**阻塞**，需后台线程。
     *
     * 只查「能确定为坏」的情况：声明里的基础版本（`^1.2.3` 里的 `1.2.3`）
     * 不在已发布列表里。范围表达式（`>=1 <2`、`*`、`latest`、`workspace:*`）
     * 一律跳过 —— 判断它们的可满足性需要完整 semver，误判代价比不修大得多。
     */
    fun checkPins(context: Context): Pair<List<Pin>, Boolean> {
        val manifest = File(profileDir(context), "package.json")
        val raw = runCatching { manifest.readText().removePrefix("\uFEFF") }.getOrNull()
            ?: return emptyList<Pin>() to false
        val obj = runCatching { JSONObject(raw) }.getOrNull()
            ?: return emptyList<Pin>() to false
        val deps = obj.optJSONObject("dependencies") ?: return emptyList<Pin>() to false

        val spec = ArrayList<Pair<String, String>>(deps.length())
        val keys = deps.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            spec.add(k to deps.optString(k))
        }

        val pins = ArrayList<Pin>(4)
        var netFailed = false
        var checks = 0
        for ((name, declared) in spec) {
            if (checks >= MAX_NET_CHECKS) break
            val base = baseVersion(declared) ?: continue
            checks++
            val info = registryVersions(name)
            if (info == null) {
                // 查不到 ≠ 不存在。网络问题不该让我们删别人的清单。
                netFailed = true
                continue
            }
            if (info.second.contains(base)) continue
            pins.add(Pin(name, declared, info.first))
        }
        return pins to netFailed
    }

    /**
     * 从声明里抠出「基础版本」。
     *
     * `2.8.3` / `^2.8.3` / `~2.8.3` / `>=2.8.3` 都取 `2.8.3`。
     * 取不到数字（`*`、`latest`、`workspace:*`、`file:`、git 地址）返回 null。
     */
    private fun baseVersion(declared: String): String? {
        val d = declared.trim()
        if (d.isEmpty() || d == "*" || d == "latest") return null
        if (d.startsWith("workspace:") || d.startsWith("file:") ||
            d.startsWith("link:") || d.startsWith("git") ||
            d.startsWith("http") || d.contains("||") || d.contains(" ")
        ) {
            return null
        }
        return Regex("""(\d+\.\d+\.\d+[0-9A-Za-z.\-+]*)""").find(d)?.groupValues?.get(1)
    }

    /**
     * 查一个包的已发布版本。
     *
     * @return (最新版, 全部版本集合)；网络/解析失败返回 null。
     */
    private fun registryVersions(pkg: String): Pair<String?, Set<String>>? {
        // scoped 包的 `/` 必须编码成 %2F，否则会被当成路径分隔符 404
        val path = URLEncoder.encode(pkg, "UTF-8").replace("+", "%20")
        val conn = runCatching {
            (URL("$REGISTRY$path").openConnection() as HttpURLConnection).apply {
                connectTimeout = NET_TIMEOUT_MS
                readTimeout = NET_TIMEOUT_MS
                setRequestProperty("User-Agent", "fcl-dsh")
                // npm registry 对 registry 元数据返回 JSON
                setRequestProperty("Accept", "application/json")
                instanceFollowRedirects = true
            }
        }.getOrNull() ?: return null

        try {
            val code = runCatching { conn.responseCode }.getOrNull() ?: return null
            // 404 = 包真的不存在；其余非 200 一律当「查不到」
            if (code == 404) return null to emptySet()
            if (code != 200) return null
            val text = runCatching {
                conn.inputStream.bufferedReader().use { it.readText() }
            }.getOrNull() ?: return null
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val latest = obj.optJSONObject("dist-tags")?.optString("latest")
                ?.takeIf { it.isNotBlank() }
            val versions = obj.optJSONObject("versions") ?: return latest to emptySet()
            val set = HashSet<String>(versions.length() * 2)
            val keys = versions.keys()
            while (keys.hasNext()) set.add(keys.next())
            return latest to set
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ────────────────────────── 修复 ──────────────────────────

    /**
     * 执行修复。**阻塞**（含联网），需在后台线程调用。
     *
     * @return 给人看的结果说明
     */
    fun repair(context: Context, onLog: (String) -> Unit): String {
        val base = diagnose(context)
        if (!base.manifestExists) return "❌ ${base.parseError ?: "找不到清单"}"

        val dir = base.profile
        val manifest = File(dir, "package.json")
        val backupDir = File(com.appHomeDir(context), BACKUP_DIR)
        var changed = false

        // ── ① 联网校验版本声明（这是本次真正的修复点）──────────────────
        onLog("🔎 正在联网校验依赖版本（最多 $MAX_NET_CHECKS 个）…")
        val (pins, netFailed) = checkPins(context)
        if (netFailed) {
            onLog("⚠️ 部分依赖联网校验失败（网络问题），这些条目未做改动")
        }
        if (pins.isNotEmpty()) {
            val raw = runCatching { manifest.readText().removePrefix("\uFEFF") }.getOrNull()
            val obj = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (obj == null) {
                onLog("❌ 清单无法解析，跳过版本修复")
            } else {
                // 改之前先留一份原件
                runCatching {
                    backupDir.mkdirs()
                    File(backupDir, "package.json").writeText(raw)
                }

                val deps = obj.optJSONObject("dependencies")
                val bundlesArr = obj.optJSONObject("dsh")
                    ?.optJSONObject("profile")
                    ?.optJSONArray("bundles")

                var repinned = 0
                var dropped = 0
                val dropNames = HashSet<String>()

                for (pin in pins) {
                    if (pin.latest == null) {
                        // 整包都不存在：只能摘掉，否则 pnpm 永远解析不过去
                        deps?.remove(pin.name)
                        dropNames.add(pin.name)
                        dropped++
                        onLog("🧹 摘除不存在的依赖：${pin.name}@${pin.declared}")
                    } else {
                        // 保留原有的 ^ / ~ 前缀，只把版本换成确实存在的最新版
                        val prefix = pin.declared.trim()
                            .takeWhile { it == '^' || it == '~' || it == '=' }
                        // `pin.latest` 已在上面判空，数据类的 val 属性可被智能转换
                        deps?.put(pin.name, prefix + pin.latest)
                        repinned++
                        onLog(
                            "🔧 修正版本：${pin.name}  ${pin.declared} → " +
                                "$prefix${pin.latest}"
                        )
                    }
                }

                // 被摘掉的包也要从加载清单里去掉，否则引擎启动时解析不到包会报错
                if (bundlesArr != null && dropNames.isNotEmpty()) {
                    val kept = JSONArray()
                    var removed = 0
                    for (i in 0 until bundlesArr.length()) {
                        val n = bundlesArr.optString(i)
                        if (n in dropNames) removed++ else kept.put(n)
                    }
                    if (removed > 0) {
                        runCatching {
                            obj.optJSONObject("dsh")?.optJSONObject("profile")
                                ?.put("bundles", kept)
                        }
                        onLog("🧹 从加载清单里摘掉 $removed 个条目")
                    }
                }

                if (repinned > 0 || dropped > 0) {
                    val ok = runCatching {
                        manifest.writeText(obj.toString(2) + "\n")
                        true
                    }.getOrDefault(false)
                    if (ok) {
                        changed = true
                        onLog("✅ 清单已更新（修正 $repinned 个版本，摘除 $dropped 个）")
                    } else {
                        onLog("❌ 写回清单失败，未做任何改动")
                    }
                }
            }
        } else if (!netFailed) {
            onLog("✅ 所有依赖版本在 registry 上都存在")
        }

        // ── ② 删 pnpm 的锁与元数据 ─────────────────────────────────────
        //
        // 必须放在 ① 之后：pnpm 会拿锁文件去校验清单，顺序反了会报
        // 「lockfile 与 manifest 不一致」然后仍然失败。
        for (rel in base.lockFiles) {
            val f = File(dir, rel)
            if (runCatching { f.delete() }.getOrDefault(false)) {
                onLog("🔓 已删除 profiles/web/$rel（下次 pnpm 操作会重建）")
                changed = true
            } else {
                onLog("⚠️ 删除 profiles/web/$rel 失败")
            }
        }

        // ── ③ 摘掉指向「磁盘上没有」的加载项 ──────────────────────────
        //
        // 与 ① 的摘除互补：这里处理「清单里写着、但从来没装上」的条目。
        // 只摘真不存在的，存在的一个都不动。
        if (base.missing.isNotEmpty()) {
            val raw = runCatching { manifest.readText().removePrefix("\uFEFF") }.getOrNull()
            val obj = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (obj != null) {
                runCatching {
                    backupDir.mkdirs()
                    File(backupDir, "package.json").writeText(raw)
                }

                val missingSet = base.missing.toHashSet()
                var dropped = 0
                obj.optJSONObject("dsh")?.optJSONObject("profile")
                    ?.optJSONArray("bundles")?.let { arr ->
                        val kept = JSONArray()
                        for (i in 0 until arr.length()) {
                            val n = arr.optString(i)
                            if (n in missingSet) dropped++ else kept.put(n)
                        }
                        if (dropped > 0) {
                            runCatching {
                                obj.optJSONObject("dsh")!!.optJSONObject("profile")!!
                                    .put("bundles", kept)
                            }
                        }
                    }

                if (dropped > 0) {
                    val ok = runCatching {
                        manifest.writeText(obj.toString(2) + "\n")
                        true
                    }.getOrDefault(false)
                    if (ok) {
                        changed = true
                        onLog(
                            "🧹 已从加载清单里摘掉 $dropped 个未安装的插件：" +
                                base.missing.joinToString(" ")
                        )
                    } else {
                        onLog("❌ 写回清单失败")
                    }
                }
            }
        }

        // ── ④ 把 .bak 备份移出 profile 目录 ────────────────────────────
        for (name in base.strayBackups) {
            val src = File(dir, name)
            runCatching {
                backupDir.mkdirs()
                val dst = File(backupDir, name)
                if (dst.exists()) dst.delete()
                if (src.renameTo(dst)) {
                    onLog("📦 备份已移出 profile：$name")
                } else {
                    // 改名失败（跨卷）就退回复制
                    src.copyTo(dst, overwrite = true)
                    src.delete()
                    onLog("📦 备份已移出 profile：$name")
                }
            }.onFailure { onLog("⚠️ 移动 $name 失败: ${it.message}") }
            changed = true
        }

        // ── ⑤ 只报告：磁盘上有但清单没登记 ────────────────────────────
        if (base.unlisted.isNotEmpty()) {
            onLog(
                "ℹ️ node_modules 里有 ${base.unlisted.size} 个包没登记进清单：" +
                    base.unlisted.take(8).joinToString(" ") +
                    if (base.unlisted.size > 8) " …" else ""
            )
        }

        return if (changed) {
            "✅ 已修复，请**重启实例**后重试（pnpm 会重建依赖树）"
        } else {
            "✅ 没有需要修的地方"
        }
    }

    /** 一行摘要，供界面直接显示（**不联网**）。 */
    fun summary(context: Context): String {
        val r = diagnose(context)
        if (!r.manifestExists) return "❌ ${r.parseError ?: "找不到清单"}"
        if (r.parseError != null) return "⚠️ ${r.parseError}"
        val parts = ArrayList<String>(4)
        if (r.missing.isNotEmpty()) parts.add("${r.missing.size} 个悬空登记")
        if (r.lockFiles.isNotEmpty()) parts.add("${r.lockFiles.size} 个 pnpm 锁文件")
        if (r.strayBackups.isNotEmpty()) parts.add("${r.strayBackups.size} 个残留备份")
        return if (parts.isEmpty()) {
            "✅ 本地清单一致（${r.deps} 依赖 / ${r.bundles} 加载项）\n" +
                "点「修复」还会联网校验版本声明（这是市场报「无合适版本」的关键）"
        } else {
            "⚠️ " + parts.joinToString(" · ")
        }
    }
}
