# fcl-dsh 开发报告

> 仿 FCL 风格的 DeepSeek Harness (dsh) Android 启动器
> 包名：`com.example.fcldsh` · 横屏应用 · 本文档汇总至 2026-10-27

---

## 一、项目定位

把最初"一个按钮 + 全屏 WebView"的 demo，重构为仿 FCL 的启动器：
**左侧竖排导航栏 + 右侧主区域**，启动后弹出**独立的全屏网页窗口**。

### 三层架构（沿用原始设计）

| 层 | 内容 |
|---|---|
| 业务层 | 原版 dsh 0.2.1-rc.2，通过 Termux PREFIX 环境运行 |
| 容器层 | `filesDir/usr` 解压 runtime.zip；用 `/system/bin/linker64` 拉起 Node；抓取带 token 的 URL |
| UI 层 | 双 Activity：启动器（`MainActivity`）+ 网页窗口（`DshWebActivity`） |

---

## 二、最终文件结构

```
app/src/main/kotlin/com/example/fcldsh/
├── MainActivity.kt      启动器主界面（FCL 布局）
├── DshWebActivity.kt    网页运行窗口（全屏沉浸）
├── AppState.kt          进程级共享状态
└── LauncherState.kt     UI 状态模型

app/src/main/kotlin/com/
└── DSHRunner.kt         Node 进程管理（package 声明在 fcldsh 下）

app/src/main/res/drawable/
├── ic_rail_home.xml     主界面     （扁平矢量）
├── ic_rail_tools.xml    实例设置   （扁平矢量）
├── ic_rail_download.xml 下载中心   （扁平矢量）
├── ic_rail_settings.xml 启动器设置 （扁平矢量）
└── ic_rail_exit.xml     退出       （扁平矢量）
```

---

## 三、UI 结构

### 启动器（MainActivity，横屏）

```
FrameLayout
 ├── ComposeView      右侧主区域，leftMargin = 72dp
 │    └── FclHome
 │         ├── 深色底 0xFF1B1A1F
 │         ├── 右侧浮层（165dp 宽）
 │         │    ├── AccountBlock：40dp 渐变头像 + "本地用户" + "离线账户"
 │         │    ├── Spacer(weight 1)
 │         │    └── LaunchBlock
 │         │         ├── 版本行卡片：28dp 图标 + 状态文字 + 实例名 + 齿轮
 │         │         ├── 「停止运行」（仅运行时显示）
 │         │         └── 「管理版本」卡片
 │         └── LogPanel（覆盖式，齿轮打开）
 └── LinearLayout     左侧原生导航栏 72dp，5 个矢量图标
      ic_rail_home      主界面      → toast「已经在主界面了」
      ic_rail_tools     实例设置    → toast「暂未实现」
      ic_rail_download  下载中心    → toast「暂未实现」
      ic_rail_settings  启动器设置  → toast「暂未实现」
      (Spacer weight 1)
      ic_rail_exit      退出启动器  → finishAffinity()
```

**功能现状**：按需求，只有 **「启动」** 和 **「退出」** 真正生效，其余图标点了给 toast 提示。

### 网页窗口（DshWebActivity，横屏全屏沉浸）

```
FrameLayout
 ├── WebView          唯一实例，全屏
 └── TextView("✕")    右上角 44dp 圆形退出钮
```

- API 30+ 用 `WindowInsetsController` 隐藏系统栏，低版本回退 `systemUiVisibility`
- 边缘划入时系统栏临时浮现（`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`）
- 刘海区延伸：`LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`
- 返回键：先网页后退，退无可退才关窗口

---

## 四、核心修复（按严重程度）

### 1. 白屏 —— 根本原因：0 尺寸时加载页面

**现象**：页面 DOM 完整、JS 正常、rAF 每秒 60 帧，但屏幕全白。

**关键证据**：
```
document.documentElement.offsetHeight === 0
document.documentElement.scrollHeight === 639
```
根元素高度塌成 0，内容溢出被裁剪，整页无可绘制区域，只剩 body 白底。

**根因**：旧代码把 `loadUrl` 放在 Compose `AndroidView` 的 `factory` 里——那是在**组合阶段**执行的，此时 WebView 还没 attach、尺寸是 `0x0`。页面以 0 高度视口完成首次布局，之后即使 View 被测量成 1080×1917，页面布局也不会自动恢复。

**修复**（三重）：
1. WebView 改原生托管（`FrameLayout` + `setContentView`），不用 `AndroidView`
2. `loadWhenSized()`：轮询 `width/height > 0` 后才 `loadUrl`（60ms × 最多 50 次，约 3 秒兜底）
3. `useWideViewPort = true` + `loadWithOverviewMode = true`——否则 WebView 忽略 `<meta viewport>`，改用 980px 布局视口

### 2. 界面能显示但无法交互

**根因**：`ComposeView` 全屏铺在 WebView 之上，拦截了所有触摸事件。

**修复**：
- 左侧导航栏改用**原生 `LinearLayout`**（只占 72dp，触摸只在自己边界内生效）
- 拆成双 Activity 后，网页窗口内已不存在 Compose 覆盖层

### 3. Android 上 bash / glob / grep 全部失效

**现象**：dsh 内部 agent 自检报错：
```
sandbox mode "workspace-write" is requested but no sandbox backend is usable
on this host; refusing to run the command unconfined.
```

**根因**：dsh 的 `sandbox-policy` 默认 `workspace-write`，要求宿主真正实施文件写入隔离。Linux 上靠 bubblewrap（需非特权 user namespace）或 Landlock（需内核 5.13+ 且启用 LSM）。**Android 两者都没有**，dsh 出于安全宁可拒绝执行也不裸跑。

**修复**：从 `@deepseek-ai/dsh-base@0.2.1-rc.2/cordis.patch.yml` 查到配置来自环境变量：
```yaml
mode: !!js process.env.DSH_PERMISSION_MODE ?? 'workspace-write'
```
所以在 `DSHRunner` 里下发：
```kotlin
env["DSH_PERMISSION_MODE"] = "danger-full-access"
```
**不需要 `--patch`**（patch 语义是"整行替换 config"，漏键会让该行静默失效并导致白屏——已踩过）。

> ⚠️ **安全代价（必须知情）**：`danger-full-access` 会让模型生成的命令以本 App 的 uid 无隔离执行，可读写 App 私有目录（含 `filesDir/usr` 运行时、`DSH_HOME` 下的凭据与会话记录）。上游同时把 approval 策略从 `ask` 改成 `never`，即不再逐条询问。
> 缓解因素：Android 应用沙箱把影响面限制在单个 App 私有存储内，不像桌面那样能触及整个用户目录。
> 想收回：把 `PERMISSION_MODE` 改回 `workspace-write`（bash 重新不可用）或 `read-only`。

### 4. 「无法创建默认工作区」

**根因**：dsh 把首次默认工作区放在 `<Documents>/deepseek-harness` 下，而 Linux 上定位 Documents 依赖 `xdg-user-dir` 二进制——Android 没有，也没有 `~/.config/user-dirs.dirs`。

**修复**（两条路一起给，不碰 Cordis 配置）：
1. 生成 `PREFIX/bin/xdg-user-dir` sh shim（按参数回 `$HOME/Documents` 等），并 `setExecutable(true)`
2. 写 `HOME/.config/user-dirs.dirs`

### 5. 子进程工作目录

`ProcessBuilder` 默认继承 App 的 cwd——Android 上是只读的 `/`。改为：
```kotlin
pb.directory(workDir)   // 指向可写的 HOME
```

### 6. Cookie 被反复清空

原实现在 `AndroidView` 的 `factory` 里每次创建都 `removeAllCookies`，导致登录态反复丢失。改为进程级一次性清理（`companion object { @Volatile var cookiesCleared }`），并放在 companion 而非实例字段——以扛住 Activity 重建。

---

## 五、功能实现

### 启动流程

```
点版本行
  → toggleRun()
  → 检查 filesDir/usr/bin/node 是否存在
      ├── 存在 → startExtractAndRun(null)
      └── 不存在 → 弹系统文件选择器选 runtime.zip
  → DSHRunner.extractRuntime()（如需要）
  → DSHRunner.startNode()
      → linker64 + node + bin.js --profile web
      → 正则抓 http://127.0.0.1:3080/?token=xxx
  → AppState.launcher.url = url（回主线程）
  → LaunchedEffect(state.url) → openWebWindow()
  → startActivity(DshWebActivity)
```

### WebView 交互（阶段四）

| 能力 | 实现 |
|---|---|
| 文件上传 | `onShowFileChooser` + `pendingFileCallback`；连点时先回传 null 释放旧回调 |
| 图片选择 | 纯图片 accept → API 33+ `ACTION_PICK_IMAGES`（缩略图网格、免存储权限）；低版本回退 `ACTION_GET_CONTENT` |
| 取消处理 | `resultCode != RESULT_OK` 时回传 null，保证该 input 还能再次点击 |
| JS `alert` / `confirm` / `prompt` | 原生 `AlertDialog` 接管，prompt 带输入框并回填默认值 |
| 下载 | http(s) 走 `DownloadManager`，**带 WebView Cookie**（否则服务端可能返回登录页 HTML） |
| blob 下载 | ❌ 仅记日志提示（需 JS 桥把 blob 转 base64，未实现） |
| 渲染进程崩溃 | `onRenderProcessGone` 记日志、移除并销毁 WebView、`finish()` |

### 日志面板（阶段二）

`LogPanel`，可通过启动卡片的齿轮打开：

- **实时滚动**：新日志到达自动滚到底，但**仅当用户已在底部附近**（`last >= size - 3`）——否则翻查历史时会被不断拽回底部
- **一键复制**：`LauncherState.logsAsText()` 生成带行号文本；过滤态下只复制可见行
- **只看错误**：过滤 ERR / ERROR / WARN / WINERR / REJECT / ❌ / 失败 / 异常，命中行用错误色高亮
- **清空**：`clearLogs()`
- 性能：`derivedStateOf` 缓存过滤结果，`LazyColumn` 承载长列表

---

## 六、关键设计取舍

### 为什么侧栏用原生 View 而不是 Compose

它只占左侧 72dp 一条，触摸分发只在自己边界内生效，右侧区域完全不受影响。若用 Compose 铺满全屏再在左边画图标，整个覆盖层会拦住 WebView 的触摸（见"问题 2"）。

### 为什么拆成两个 Activity

FCL 的交互就是"启动器 → 独立游戏窗口"。dsh 网页需要整块屏幕（它自己还有左侧会话列表），和导航栏挤在一起两边都局促。拆开后启动器的日志/状态在网页运行时也能随时查看。

### 为什么用 emoji 之外的矢量图

emoji（🏠🔧⚙）在深色背景上渲染成**彩色字形**，破坏扁平单色风格。改用 24dp viewport 的纯填充矢量图 + `imageTintList` 统一染色。

### 为什么需要「停止运行」按钮

FCL 退出游戏即结束进程，但我们的网页窗口关掉后 Node 仍在后台运行。没有显式停止入口就无法重启，只能杀 App。

---

## 七、踩过的坑（工程教训）

### Kotlin 的块注释支持嵌套

在 KDoc 里写 `image/*`（斜杠+星号）会**再开一层注释**，结尾的 `*/` 只关掉内层，外层一直开到文件末尾。表现为：

```
Syntax error: Unclosed comment
Syntax error: Missing '}'
+ 几十个级联的 Unresolved reference
```

**教训**：看到"大范围 Unresolved reference + Missing '}'"时，优先怀疑注释未闭合，而不是逐个去修符号。已把全文件的 `image/*` 改为 `image/…`。

### `WebChromeClient.FileChooserParams` 是嵌套类

在 `webChromeClient` 的匿名对象内部可以省略外层名，但在**外部辅助方法的签名**里必须写全限定名，否则报 `Unresolved reference`。

### 分析器的两个误判

- `TextView(this).apply { text = ... }` / `EditText(...).apply { ... }` 接收者推断不出来，会报一串 `Unresolved reference` → 必须改显式变量逐句赋值
- IDE 提示 `backgroundColor` "可用属性语法"是**误报**——WebView 没有对应 getter，属性写法编译失败，必须 `setBackgroundColor(...)`

### `theme.resolveAttribute(...)` 解析不稳

改用 `StateListDrawable` 手写按下反馈。

### 新建 drawable 后 `R.drawable.x` 短暂报未解析

不是代码问题——资源还没进 R 类索引。跑一次 `assemble:app:debug` 重新生成 R 即可。

### 我曾走过的弯路（诚实记录）

白屏排查中，我先后尝试并**证伪**了以下方向，各花费一轮以上：

| 尝试 | 结果 |
|---|---|
| 更新 WebView 内核 | 无效（实测 Chrome 154，已够新） |
| 检查 JS 报错 | 无任何 WINERR / REJECT |
| 检查白字白底 | `bodyColor: rgb(15,17,21)` 深色字，正常 |
| 检查渲染管线 | `raf` 每秒约 60 帧，正常 |
| `setLayerType(LAYER_TYPE_HARDWARE)` | 无效 |
| `setLayerType(LAYER_TYPE_SOFTWARE)` | 无效，反而让滚动掉帧 |
| 只加 `useWideViewPort` + `loadWithOverviewMode` | 仍白屏 |
| CSS `html/body/#root{height:100%}` | 仍白屏 |
| `#root{min-height:100vh}` | 能显示，但只占上半屏 |
| `#root{height:100vh !important; overflow:visible}` + `*{content-visibility:visible}` | 能铺满，但**子窗口（下拉/弹窗）展不开** |

**真正解决问题的是**：改原生托管 + 等尺寸再 `loadUrl`。这个方向在读了参考仓库 `Deepseek-Harness-Local-Android-main` 之后才确定——它一直用 `setContentView` + 原生 XML 布局，从来没遇到过这个窗口期问题。

**教训**：排查陷入僵局时，早一点去读一个"同类且正常工作"的实现，比继续在症状上试错高效得多。

---

## 八、验证状态

| 项目 | 状态 |
|---|---|
| 编译 | ✅ `assemble:app:debug` 通过 |
| 白屏修复 | ✅ 用户确认「效果很好」 |
| 交互修复 | ✅ 用户确认可正常点击 |
| 日志面板 | ✅ 编译通过 |
| 双窗口（启动 → 弹窗 → 返回） | ⚠️ 编译通过，**未真机验证** |
| FCL 风格 UI + 矢量图标 + 165dp 窄栏 | ⚠️ 编译通过，**未真机验证** |
| `DSH_PERMISSION_MODE` 是否让 bash 可用 | ⚠️ **未验证**（需重启 Node 进程后实测） |
| 文件上传 / JS 对话框 / 下载 | ⚠️ 编译通过，**未真机验证** |

编译警告均为无害项：`Icons.Filled.List` 废弃、`setDecorFitsSystemWindows` 废弃、2 处 Unnecessary safe call、`databaseEnabled` 废弃、若干"可用属性语法"提示。

---

## 九、尚未完成

### 阶段三：多实例与数据隔离（主要剩余工作）

1. 每个实例独立目录：`filesDir/instances/<id>/home`
2. 启动时动态注入 `DSH_HOME` 指向该实例目录
3. 多实例可能同时运行 → 动态分配端口（从 3080 递增）防止冲突
4. `DSHRunner` 从"单例 + 单进程"重构为"实例 → 进程"映射

这是改动最大的一步，需要动 `DSHRunner` 的核心结构。

### 其他次要项

- **blob 下载**：参考仓库用注入的 JS 桥（`@JavascriptInterface saveBase64`）把 blob 转 base64 落盘，未移植
- **16KB 页面大小对齐**：Android 15+ 对 native 库有对齐要求，runtime.zip 中的 `.so` 若未对齐可能影响兼容性
- 侧栏图标点击后的视觉反馈可再打磨（当前只有按下水波纹，无选中态）

---

## 十、参考资料

从 npm 拉取并确认的 dsh 实现细节（非猜测）：

| 来源 | 确认内容 |
|---|---|
| `@deepseek-ai/dsh-web-frontend/dist/index.html` | body 只有 `<div id="root"></div>`，含 `<meta name="viewport" content="width=device-width, initial-scale=1">` |
| `@deepseek-ai/dsh-web-app/lib/startup.js` | flag 表只有 `--host/--port/--public-url/--trusted-host/--no-open/--tls-*`，**无 workspace 参数** |
| `@deepseek-ai/dsh-base/cordis.patch.yml` | `sandbox-policy.mode` 来自 `process.env.DSH_PERMISSION_MODE`；`approval.policy` 同源 |
| `@deepseek-ai/dsh-api-workspace-controller` | 默认工作区在 `<Documents>/deepseek-harness`；Linux 定位 Documents 依赖 `xdg-user-dir` |
| `@deepseek-ai/dsh-host-directory-picker-auto` | Android 落到 `browse` 分支（网页内目录浏览），与 `onShowFileChooser` 无关 |

同目录的兄弟项目 `Deepseek-Harness-Local-Android-main` 提供了 WebView 配置与视口处理的关键参考。

---

## 附录：关键代码位置

| 功能 | 位置 |
|---|---|
| 进程启动与环境变量 | `DSHRunner.startNode()` |
| xdg-user-dir shim 生成 | `DSHRunner.startNode()` 中段 |
| WebView 配置 | `DshWebActivity.setupWebView()` |
| 等尺寸加载 | `DshWebActivity.loadWhenSized()` |
| 视口改写 + 强制重绘 | `DshWebActivity.applyViewport()` |
| 文件选择 / JS 对话框 | `DshWebActivity` 的 `webChromeClient` |
| 启动/停止入口 | `MainActivity.toggleRun()` |
| 打开网页窗口 | `MainActivity.openWebWindow()` |
| 跨 Activity 共享状态 | `AppState` |
| 日志面板 | `MainActivity.LogPanel()` |
| 侧栏构建 | `MainActivity.makeNavRail()` / `makeRailIcon()` |
