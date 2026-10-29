# 阶段三设计：FCL 风格多实例架构

> 状态：**设计待确认**，尚未实现。
> 目标：把 `DSHRunner`（单例 + 单进程）重构为 `InstanceManager`（多实例），
> 并把 UI 从「双 Activity」改为「单 Activity + Compose 导航」。

---

## 零、必须先正视的三个风险

这次重构会**重新踩到已经解决过的两个坑**，不提前约束就会回到「白屏 + 点不动」。

### 风险 1：WebView 不能放进 Compose 的 `AndroidView`

白屏的根因。`AndroidView` 的 `factory` 在**组合阶段**执行，此刻 WebView 还没
attach、尺寸是 `0x0`。页面以 0 高度视口完成首次布局后，dsh 里依赖 `height:100%`
的根容器解析成 0（实测 `html.offsetHeight === 0` 而 `scrollHeight === 639`），
整页没有可绘制区域，只剩 body 白底。

**约束**：单 Activity 可以，但 WebView 必须是 **Activity 根布局里的原生 View**，
与 ComposeView 平级，由 FrameLayout 管理，**永远不能**塞进 `AndroidView`。

### 风险 2：全屏 ComposeView 会拦截 WebView 触摸

「界面正常但点不动」的根因。只要 ComposeView 是 `MATCH_PARENT` 且 `VISIBLE`，
所有触摸都落在它身上。

**约束**：运行态下 Compose 层**只能占据有界区域**（底部控制台），
绝不能用全屏透明 Compose 去画悬浮控件。

### 风险 3：端口探测的 TOCTOU 竞态

`ServerSocket` 探测「空闲」到 Node 真正 `bind` 之间有时间窗。

**约束**：探测之外还要维护「本进程已分配端口集合」，取并集；
并在启动失败时换端口重试一次。

---

## 一、整体架构

### 1.1 分层

```
MainActivity（唯一 Activity）
 FrameLayout
  ├── webView       原生 WebView，MATCH_PARENT
  ├── composePages  页面层，MATCH_PARENT
  └── composeDock   运行态控制台，底部有界区域（44dp ~ 40% 屏高）
        │
        ├── InstanceManager   进程 / 端口 / 沙箱 / 日志
        └── LauncherUiState   页面路由 / 抽屉 / 聚焦实例
```

### 1.2 层的可见性规则（核心约束）

| 场景 | webView | composePages | composeDock |
|---|---|---|---|
| 浏览页面 | GONE | VISIBLE | GONE |
| 运行态（看网页） | VISIBLE | GONE | VISIBLE（44dp） |
| 运行态 + 控制台展开 | VISIBLE | GONE | VISIBLE（~40%） |

**`composePages` 与 `webView` 永不同时 VISIBLE。**

---

## 二、数据模型

```kotlin
/** 持久化描述，存 filesDir/instances/<id>/instance.json。不含 Process（不可序列化）。 */
data class Instance(
    val id: String,                     // UUID
    val name: String,
    val dshVersion: String,             // "0.2.1-rc.2"
    val port: Int,
    val permissionMode: PermissionMode, // 实例级覆盖，默认继承全局设置
    val createdAt: Long
)

enum class InstanceState { STOPPED, STARTING, RUNNING, ERROR }

/** 运行时状态，用 Compose 可观察类型，UI 直接订阅。 */
class InstanceRuntime(val instance: Instance) {
    var state by mutableStateOf(InstanceState.STOPPED)
    var url by mutableStateOf<String?>(null)
    var process: Process? = null                    // 普通字段
    val logs = mutableStateListOf<String>()         // 每实例独立日志
    var lastError by mutableStateOf<String?>(null)
}

object InstanceManager {
    val runtimes = mutableStateMapOf<String, InstanceRuntime>()
    var attachedId by mutableStateOf<String?>(null)  // 当前附着在 WebView 上的实例
    private val claimedPorts = mutableSetOf<Int>()    // 防 TOCTOU

    fun loadAll(context: Context)                    // 启动时扫描 instances/
    fun create(context: Context, name: String): Instance
    fun remove(context: Context, id: String)         // 停止 + 删目录
    fun start(context: Context, id: String)
    fun stop(id: String)
    fun restart(context: Context, id: String)
    fun attach(id: String)                           // WebView 显示该实例
    fun detach()                                     // 退回页面层，进程继续跑
}
```

**日志必须按实例分开**：多实例混在一起就无法排查。

---

## 三、端口动态分配

```kotlin
/**
 * 从 startPort 开始找可用端口。
 * 判据取两者之并：
 * 1. ServerSocket 能否绑定（系统层面占用）
 * 2. 本进程已分配给其他实例（避免探测与 bind 之间的竞态）
 */
private fun allocatePort(startPort: Int = 3080, maxTries: Int = 200): Int {
    for (p in startPort until startPort + maxTries) {
        if (p in claimedPorts) continue
        if (!isPortFree(p)) continue
        claimedPorts += p
        return p
    }
    throw IllegalStateException("3080..${startPort + maxTries} 无可用端口")
}

private fun isPortFree(port: Int): Boolean = try {
    // 绑 127.0.0.1 而非 0.0.0.0：dsh 只监听回环，
    // 用 0.0.0.0 探测会漏掉「仅回环被占用」的情况
    ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", port)) }
    true
} catch (e: IOException) { false }
```

**失败重试**：stderr 含 `EADDRINUSE` 时，释放端口 → 重新分配 → 重试一次。
**停止时释放**：`stop()` 里必须 `claimedPorts -= port`，否则反复启停会耗尽端口。

---

## 四、目录隔离

```
filesDir/
├── usr/                       ← 共享运行时（Node + dsh）
├── instances/
│   ├── <id-A>/
│   │   ├── instance.json      ← Instance 持久化
│   │   ├── home/              ← HOME 环境变量 & 进程 cwd
│   │   │   ├── Documents/
│   │   │   ├── .config/user-dirs.dirs
│   │   │   └── workspace/
│   │   ├── dsh-home/          ← DSH_HOME（凭据、会话、配置）
│   │   └── logs/engine.log
│   └── <id-B>/ ...
└── home/                      ← 旧的全局目录，迁移后可删
```

每实例环境变量：

```kotlin
val instDir = File(context.filesDir, "instances/${rt.instance.id}")
val home    = File(instDir, "home").apply { mkdirs() }
val dshHome = File(instDir, "dsh-home").apply { mkdirs() }
val tmp     = File(context.cacheDir, "instances/${rt.instance.id}").apply { mkdirs() }

pb.environment().apply {
    put("PREFIX", prefix)
    put("HOME", home.absolutePath)
    put("TMPDIR", tmp.absolutePath)
    put("LD_LIBRARY_PATH", "$prefix/lib")
    put("PATH", "$prefix/bin:/system/bin")
    put("DSH_HOME", dshHome.absolutePath)           // ★ 隔离的关键
    put("DSH_PERMISSION_MODE", rt.instance.permissionMode.id)
}
pb.directory(home)                                   // cwd 也指向实例目录
```

`xdg-user-dir` shim 仍装在共享的 `usr/bin`（一份即可），但它输出的路径基于
`$HOME`，而 `$HOME` 已按实例隔离，所以 `~/.config/user-dirs.dirs` 天然独立。

启动参数：

```
/system/bin/linker64 <node> --expose-internals <bin.js>
    --profile web --no-open
    --host 127.0.0.1 --port <该实例端口>
```

---

## 五、状态机

```
create → STOPPED ─start()→ STARTING ─抓到URL→ RUNNING
            ↑                  │                 │
            │                  │ 失败            │ stop()
            └──── ERROR ←──────┘                 │
            ↑                                    │
            └────────────────────────────────────┘
```

规则：
- `STARTING` 期间禁用该实例启动按钮（防连点）
- 只有 `RUNNING` 才能 `attach` 到 WebView
- `stop()` 从任意状态都能安全调用（幂等）

---

## 六、UI 设计

### 6.1 页面路由

```kotlin
sealed interface Screen {
    data object InstanceList : Screen
    data class LaunchControl(val id: String) : Screen
    data object Settings : Screen
}

class LauncherUiState {
    var screen by mutableStateOf<Screen>(Screen.InstanceList)
    var drawerOpen by mutableStateOf(false)
    var logExpanded by mutableStateOf(false)
    var focusedId by mutableStateOf<String?>(null)
}
```

**不用 Navigation-Compose**：只有 3 个页面、无深链需求，手写 sealed class 更轻。

### 6.2 Activity 层装配（伪代码）

```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var composePages: ComposeView
    private lateinit var composeDock: ComposeView

    override fun onCreate(...) {
        val root = FrameLayout(this)

        webView = WebView(this).apply {
            setupWebView()
            visibility = View.GONE
        }
        root.addView(webView, MATCH_PARENT, MATCH_PARENT)

        composePages = ComposeView(this).apply {
            setContent { MaterialTheme { PagesRoot(uiState, InstanceManager) } }
        }
        root.addView(composePages, MATCH_PARENT, MATCH_PARENT)

        // 控制台层：高度有界，否则拦截整屏触摸（风险 2）
        composeDock = ComposeView(this).apply {
            setContent { MaterialTheme { RunningDock(...) } }
            visibility = View.GONE
        }
        root.addView(composeDock, MATCH_PARENT, consoleHeightPx)

        setContentView(root)
    }

    /** 三层的唯一同步点，保证 webView 与 composePages 永不同时 VISIBLE。 */
    private fun syncLayers() {
        val showingWeb = InstanceManager.attachedId != null
        webView.visibility      = if (showingWeb) VISIBLE else GONE
        composePages.visibility = if (showingWeb) GONE    else VISIBLE
        composeDock.visibility  = if (showingWeb) VISIBLE else GONE
    }

    /** attach 时必须等 WebView 有尺寸再 loadUrl（风险 1）。 */
    private fun attachAndLoad(rt: InstanceRuntime) {
        val url = rt.url ?: return
        loadWhenSized(url) { wv ->          // 轮询 width/height > 0
            wv.loadUrl(url)
            syncLayers()
        }
    }
}
```

### 6.3 实例列表页（FCL 版本列表风格）

```kotlin
@Composable
fun InstanceListPage(
    instances: List<InstanceRuntime>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCreate: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp),
               verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(instances, key = { it.instance.id }) { rt ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(rt.instance.id) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF25232A))
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                        .background(Brush.linearGradient(listOf(橙, 深橙))),
                        contentAlignment = Alignment.Center) {
                        Text("JS", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(rt.instance.name, fontSize = 14.sp,
                             fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape)
                                .background(rt.state.dotColor()))      // 状态圆点
                            Spacer(Modifier.width(4.dp))
                            Text(rt.instance.dshVersion, fontSize = 11.sp, color = 次文字)
                            Spacer(Modifier.width(8.dp))
                            Text(":${rt.instance.port}", fontSize = 11.sp, color = 次文字)
                        }
                    }
                    IconButton(onClick = { /* 实例设置 */ }) { /* 齿轮 */ }
                    IconButton(onClick = { onDelete(rt.instance.id) }) { /* 删除 */ }
                }
            }
        }
        item {
            OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
                Text("新建实例")
            }
        }
    }
}
```

### 6.4 启动控制页（FCL「启动游戏」风格）

```kotlin
@Composable
fun LaunchControlPage(
    rt: InstanceRuntime,
    onBack: () -> Unit,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onRestart: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { /* 返回 */ }
            Text(rt.instance.name, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(16.dp))

        // 环境信息：DSH_HOME 路径、端口、权限模式、版本
        InfoCard("运行环境", listOf(
            "DSH_HOME" to rt.instance.dshHomePath,
            "监听端口" to "127.0.0.1:${rt.instance.port}",
            "权限模式" to rt.instance.permissionMode.id,
            "dsh 版本" to rt.instance.dshVersion
        ))

        Spacer(Modifier.weight(1f))

        // 正中大启动按钮（FCL 的灵魂）
        Button(
            onClick = {
                when (rt.state) {
                    InstanceState.RUNNING  -> onAttach()
                    InstanceState.STARTING -> Unit
                    else                   -> onLaunch()
                }
            },
            enabled = rt.state != InstanceState.STARTING,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(when (rt.state) {
                InstanceState.RUNNING  -> "打开界面"
                InstanceState.STARTING -> "启动中…"
                else                   -> "启动"
            }, fontSize = 16.sp)
        }

        if (rt.state.isRunningLike()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onStop, Modifier.weight(1f)) { Text("停止") }
                OutlinedButton(onClick = onRestart, Modifier.weight(1f)) { Text("重启") }
            }
        }
    }
}
```

### 6.5 运行态控制台（FCL 悬浮控制条）

```kotlin
/**
 * 运行态控制台。
 * 高度由 Activity 侧 LayoutParams 控制（44dp 折叠 / 40% 展开），
 * 这里只管内容与显隐。绝不能铺满全屏，否则吞掉 WebView 触摸。
 */
@Composable
fun RunningDock(
    rt: InstanceRuntime,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onBackToLauncher: () -> Unit      // 收起网页但保持进程运行
) {
    Column(Modifier.fillMaxSize().background(半透明深色)) {
        // 折叠条：状态点 + 实例名 + 最后一行日志 + 箭头
        Row(Modifier.fillMaxWidth().height(44.dp).clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically) {
            Box(状态圆点)
            Spacer(Modifier.width(6.dp))
            Text(rt.instance.name, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(rt.logs.lastOrNull() ?: "", fontSize = 9.sp, maxLines = 1,
                 overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f))
            Text(if (expanded) "▼" else "▲", fontSize = 12.sp)
        }

        if (expanded) {
            HorizontalDivider()
            LogList(rt.logs, Modifier.weight(1f))     // 复用现有滚动/复制/过滤逻辑
            Row(Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBackToLauncher) { Text("返回启动器") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRestart) { Text("重启") }
                TextButton(onClick = onStop) { Text("停止") }
            }
        }
    }
}
```

---

## 七、实例切换流程

场景：「A 在运行，我想跑 B」

```
实例列表点 B
  → 进入 B 的 LaunchControl 页
  → 点「启动」
      ├── A 正在运行？
      │     → 弹确认：「A 正在运行，停止 A 并启动 B？」
      │       ├── 取消 → 留在 B 的启动页
      │       └── 确认 → stop(A) → 等 A 的 state 变 STOPPED → start(B)
      └── A 未运行 → 直接 start(B)
  → B 抓到 URL → attachedId = B → syncLayers() → WebView 加载
```

**为什么必须先停 A**：整个 App 只有一个 WebView 实例。真正决定只能显示一个的是
WebView，不是端口。所以「同时运行多个实例」在 UI 上无法直观呈现，
本设计选择**同一时刻只允许一个实例运行**（与 FCL 一致）。

> 如果你确实需要「A 在跑的同时后台起 B」，可以扩展为：允许多个进程同时存在，
> 但只有一个 `attachedId` 附着 WebView。代价是端口、日志、内存都要按实例隔离，
> 且 UI 需要额外的「运行中实例」指示。建议先做单实例运行版本。

---

## 八、迁移步骤（建议顺序）

| 步骤 | 内容 | 可验证点 |
|---|---|---|
| 1 | 引入 `Instance` / `InstanceRuntime` / `InstanceManager`，但只支持一个实例，沿用现有目录 | 功能与现在完全等价 |
| 2 | 改成 `instances/<id>/` 目录 + 每实例端口 | 新建第二个实例能独立启动 |
| 3 | 单 Activity 改造：WebView 与两个 ComposeView 平级 | 白屏/触摸问题不复发 |
| 4 | 实例列表页 + 启动控制页 | 能创建/切换/删除实例 |
| 5 | 运行态控制台（折叠 + 日志 + 停止/重启） | 运行中可操作 |
| 6 | 删除旧的 `DshWebActivity` 与 `AppState` | 无残留死代码 |

**每步都要真机验证**，尤其第 3 步——它是白屏风险最高的地方。

---

## 九、需要你确认的三个决策

1. **单实例运行 vs 多实例并发**：建议先做单实例运行（与 FCL 一致、实现简单）。
2. **运行态能否打开侧滑抽屉**：本设计为「不能」，要回启动器走控制台的
   「返回启动器」按钮。若希望抽屉在运行态也可用，需要把抽屉做成有界浮层，
   会增加触摸管理的复杂度。
3. **实例的 dshVersion 是否可变**：本设计假设所有实例共用 `filesDir/usr` 里
   同一份 dsh 包（下载中心只更新它）。若要支持多版本并存，
   目录需扩为 `instances/<id>/usr/`，磁盘占用会成倍增长。
