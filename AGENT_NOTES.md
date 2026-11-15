# fcl-dsh 经验与注意事项

> 本文件是**踩坑记录 / 设计约束**。项目基础结构（包名、模块、目录、版本号）见
> agent 记忆与 `app/module.toml`。
> 规则：**改动前先读相关小节**；得出新结论先更新这里，再动代码。

## 目录

1. [容器暴露给系统文件选择器](#1-容器暴露给系统文件选择器)
2. [OpenSSL / Termux 绝对路径](#2-openssl--termux-绝对路径)
3. [分析器与构建实操](#3-分析器与构建实操)
4. [导出包泄露 API 密钥](#4-导出包泄露-api-密钥)
5. [导出/导入后插件报 Cannot find module](#5-导出导入后插件报-cannot-find-module)
6. [「用浏览器打开」鉴权失败](#6-用浏览器打开鉴权失败)
7. [启动耗时：瓶颈在引擎，不在本仓库](#7-启动耗时瓶颈在引擎不在本仓库)
8. [版本号三处同步](#8-版本号三处同步)
9. [铁律速查](#9-铁律速查)

---

## 1. 容器暴露给系统文件选择器

**只能走 DocumentsProvider，不能真的挂到 `/`。**

- 挂载到真实 `/` 需要 root；proot 伪出的 `/` 只属于该进程自己的命名空间，
  系统文件管理器和别的应用都看不见。
- 系统选择器（DocumentsUI）根列表里那些行（CodeAssist「App files」、FCL、
  ZeroTermux「可用空间: 32.89 GB」）**全是 SAF 的 DocumentsProvider**，不是挂载。
  provider 跑在本应用 UID 内读私有目录，跨应用靠 **fd 授权**，
  不需要存储权限也不需要 root。

实现：`app/src/main/kotlin/com/example/fcldsh/ContainerDocumentsProvider.kt`

- authority 后缀 `".documents"`（常量 `AUTHORITY_SUFFIX`），
  `authority(ctx) = packageName + AUTHORITY_SUFFIX`。
- 根 id = `"container"`；documentId 形如 `container/home/.dsh-2.2.0`。
- **顶层白名单硬编码** `home` / `usr`（`TOP_LEVEL`）—— `cacheDir` 与 filesDir
  其他内容不会被猜 id 读到；`fileFor()` 是唯一越权防线
  （查 `..`/空段 + 顶层白名单 + 前缀校验，三道）。
- manifest 四个要素缺一不可：`exported="true"`（调用方是别的进程）、
  `permission="android.permission.MANAGE_DOCUMENTS"`（签名级，只有系统持有，
  安全性靠它而不是靠不导出）、`grantUriPermissions="true"`，
  外加 `intent-filter action=android.content.action.DOCUMENTS_PROVIDER`
  （少了它 provider 不会出现在任何根列表里）。
- `queryRoots` 里 **不要**加 `Root.FLAG_ADVANCED`：加了会被折叠进「更多」，
  用户以为没生效。设置 `COLUMN_AVAILABLE_BYTES`（StatFs on filesDir）后
  DocumentsUI 自动显示成「可用空间: xx GB」。
- `usr` 下有整棵 node_modules（几万个文件）：只逐层 `listFiles`，
  `MAX_CHILDREN=2000`；唯一递归是 `querySearchDocuments`，深度 6 / 200 条，
  且**不跟随符号链接**（`Os.lstat` + `S_ISLNK`，同 `AppHome.kt`）。
- Kotlin 里只 override `openDocument(id, mode, signal)` 三参版本即可 ——
  两参旧版由三参默认实现委托过来（相反方向的覆盖是多余的）。

---

## 2. OpenSSL / Termux 绝对路径

**统一走 `OpenSslEnv.ensure(prefix, onLog)`**（`OpenSslEnv.kt`）。

容器里的 Node / libssl 是 Termux 环境编译的，`OPENSSLDIR` 写死
`/data/data/com.termux/files/usr/etc/tls`（别人的 0700 私有目录 / 不存在）。
`ensure()` 确保 `$PREFIX/etc/tls/openssl.cnf` 存在（缺就写最小配置），
返回可以安全 `putAll` 的环境变量表（`OPENSSL_CONF` / `SSL_CERT_FILE` /
`NODE_EXTRA_CA_CERTS`）。

### 关键机理（别再搞反）

`Permission denied` ≠ `No such file`。

- **装了 Termux** 的用户 → 目录存在但属别人 uid(0700) → OpenSSL 报**硬错误**，
  进程以**退出码 13** 结束 → 必现故障。
- **没装 Termux** → 路径不存在，ENOENT 被吞掉，回落到内置默认值 → 正常。

所以「我手机上没问题」通常只说明没装 Termux，不是「碰巧能读跨应用私有目录」。

**固定特征**：`node --version` 通过、`npm --version` 挂掉。
因为 `--version` 在 Node 最早期分支就打印退出，**不加载 OpenSSL 配置、不碰 crypto**，
所以它是个**不可靠的健康检查**，会把故障推迟到 npm 那层才暴露，还带偏结论。

### 调用点（缺一不可）

Kotlin 侧五处：

1. `DSHRunner.startNode` —— 引擎进程 env（放 `PATH` 之后）
2. `DSHRunner.runNode` —— 诊断用的同步 runner（日志「平台诊断」段的 🔐 就是它）
3. `EngineInstaller.runNpmInstall` 的 pb env —— npm 拉 tarball 走 HTTPS
4. `EngineInstaller.probeNpmVersion` —— 预检必须与实际安装同环境，否则结论失真
5. `EngineInstaller.probeNodeVersion` —— 自检环境也要一致

外加 shell 脚本内联三处：`ShizukuCliShim.kt` / `A11yCliShim.kt` 生成的 sh 里
```sh
if [ -f "$prefix/etc/tls/openssl.cnf" ]; then
    OPENSSL_CONF="$prefix/etc/tls/openssl.cnf"
    export OPENSSL_CONF
fi
```
- ★ `shz` 必需：它由 **Shizuku/adb(uid 2000)** 启动，环境变量来自那边，
  **不继承本应用进程**，启动引擎时设的 `OPENSSL_CONF` 会丢。
- ★ `$prefix` 是 **Kotlin 模板插值**；要输出字面 `$` 才写 `${'$'}`。
- ★ 两个 shim **每次启动都会重写**：`ShizukuBridge.start()` 在**任何早退分支之前**
  无条件调 `ensureShizukuCli(prefix)`，成功时静默，只有失败才打
  `⚠️ 安装 shz 命令失败`。所以「日志里没有 shz 那行」= 成功，不是没装。

### 铁律

- 每个变量都**文件真实存在才注入**。指向不存在的文件比不设更糟：
  `OPENSSL_CONF` 指空文件会让 OpenSSL 初始化直接失败，等于换个路径重演故障。
  同理**没有**加 `--openssl-config` 启动参数 —— `OPENSSL_CONF` 已覆盖同一链路，
  多一个参数只多一个「路径算错就起不来」的失败面。
- 默认配置里**不写** `CipherString = DEFAULT@SECLEVEL=1`（安全降级），只注释备用。
- `probeNodeVersion` 用**真自检**而不是 `--version`：
  `node -e "…process.versions.node…; require('node:crypto').createHash('sha256')…"`。
  代价：crypto 初始化有别的问题时会提前中止装机流程。

### npm 版本号（已查证，别再当成 bug）

`fetchNpmVersion()` 是直接读 `https://registry.npmjs.org/npm/latest` 的 `version`
字段，**没有正则解析**。registry 实际返回 12.x，`npm-12.2.2.tgz` 也真实存在 ——
「npm 12.2.2 是抓错版本」这个判断是错的。

真正值得警惕的是：`ensurePackageManager` 永远补装**最新** npm，不看容器里 node
的主版本；npm 大版本对 node 有硬要求，届时表现是 `npm --version` 立刻失败
（日志里 `node 版本:` 与 `npm 版本:` 两行可直接对账）。

---

## 3. 分析器与构建实操

### 3.1 压制 Java 过时 API 告警：只有一种写法有效

```kotlin
val wf = run { @Suppress("DEPRECATION") wm.createWifiLock(mode, name) }
run { @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(false) }
```

**实测无效**的三种（全都还会报）：
- 函数级 `@Suppress("DEPRECATION")` 挂在 `fun` 上
- 语句上方单独一行 `@Suppress(...)`
- `val x = @Suppress(...) 调用`（注解不在调用表达式上）

判定只认「注解**直接贴住被报告的那个调用表达式**」。别提升到文件级
（`@file:Suppress` 会把整个文件真正该处理的告警一起遮掉）。

### 3.2 `request?.isForMainFrame == true` 不产生智能转换

改成 `request.url` 会立刻报 `Only safe (?.) or non-null asserted (!!) calls are allowed`。
必须写：

```kotlin
if (request != null && request.isForMainFrame) { … request.url … }
```

（对 `Boolean?` 做 `== true` 只是相等比较，编译器不据此窄化。）

### 3.3 `MainActivity.kt` 里的 `R.drawable` 需要显式 import

现象：**同一个包**里其他文件（`FclTheme.kt`、`EngineTaskService.kt`）写 `R.drawable.*`
零诊断，唯独 `MainActivity.kt` 三处报 `Unresolved reference: drawable`，
且 `go_to_definition` 把 `R` 解析成某个局部 `val r`（作用域遮蔽）。

修法：在 `import com.DSHRunner` 后加 **`import com.example.fcldsh.R`**（已实测生效）。
改用全限定名 `com.example.fcldsh.R.drawable.x` **无效**（当时仍报错），别走那条路。
`ScreenCaptureService.kt` 里用全限定名能过，只是因为它本来就没被遮蔽。

### 3.4 `TabRow` → `PrimaryTabRow`

material3 1.4.0 起 `TabRow` 过时，官方指向 `PrimaryTabRow` / `SecondaryTabRow`。
参数名 `selectedTabIndex` / `containerColor` / `contentColor` / `tabs` 完全兼容，
换名字即可，行为不变。符号在索引里显示为 mangled 名 `PrimaryTabRow-pAZo6Ak`
（因为带 value-class 参数），这是正常的。

### 3.5 `get_diagnostics` 会在超大文件上失败

`MainActivity.kt`（4500+ 行）、`DshWebActivity.kt`（1000+ 行）反复失败，重试无效。
`EngineTaskService.kt` 这类中等文件正常。

**替代验证手段**：
- `assemble:app:debug` 常常全 UP-TO-DATE（IDE 保存时已后台编译过），
  **此时它不能证明最新改动可编译**。
- 要真正从源码编译一遍，用 **`assemble:app:release`** —— 独立变体，
  `:app:compileKotlinRelease` 会实际执行（日志能看到 class 文件数、D8 分桶），
  这才是权威校验。

### 3.6 `TextView.textColor` 这个属性不存在 —— 分析器的建议是错的

`get_diagnostics` 会对 `setTextColor(...)` 报：

```
WARNING: Use property access syntax: 'textColor = ColorStateList.valueOf(...)'
```

**照着改直接编译失败**：

```
error: Unresolved reference 'textColor' on receiver of type 'TextView'.
```

原因：Kotlin 的合成属性要求 **getter + setter 成对**。`TextView` 只有两个
`setTextColor`（`int` / `ColorStateList`）setter，**没有** `getTextColor()`，
所以 `textColor` 这个只写属性根本不会被生成。`setTextColor(int)` 与
`setTextColor(ColorStateList)` 两种调法都会被建议成属性写法，**都无法采纳**。

**结论**：保留 `setTextColor(ColorStateList.valueOf(...))`（本文件里其余几处
用 `setTextColor(int)` 也可以），**忽略这条警告**。它与既有的一批
`Use property access syntax: 'outputStream'` 警告同属可接受噪音，不影响构建。

---

## 4. 导出包泄露 API 密钥

**现象**：实例导出 zip → 导入，密钥跟着过去了。

**两个确定的密钥载体**（本应用自己写的，都在 `$DSH_HOME` 下）：

| 文件 | 内容 | 写入点 |
|---|---|---|
| `$DSH_HOME/.env` | `DEEPSEEK_API_KEY=sk-…` 明文 | `AccountStore.kt:850` |
| `$DSH_HOME/android-account.json` | **全部**账号，每个带 apiKey 明文 | `AccountStore.kt:776` |

**为什么名字规则没抓到**：
- `CREDENTIAL_NAMES` 里是 `account`，而比较用的是**精确相等** → `android-account` ≠ `account`
- `.env` 的扩展名不在 `CREDENTIAL_EXTENSIONS` 里，路径段 `.env` 去点后也撞不上任何名字

**修法：三道防线**

1. **硬规则 `isHardSecretPath()`**（`EngineExporter.kt`）—— 只看文件名，覆盖
   `.env` / `.env.*` / `*.env` / `android-account.json` / `.netrc` / `.git-credentials` /
   `.npmrc*` / `.yarnrc*` / `id_rsa|dsa|ecdsa|ed25519`。
   ★ **必须排在 node_modules 与代码扩展名豁免之前** —— 那层豁免（见第 5 节）
   会正好把 `.env` 又放跑。
2. **内容级抹除 `redactSecrets()`** —— 剩余配置再扫一遍，`sk-…` 与
   `"apiKey": "…"` / `TOKEN=…` 的值换成 `REDACTED`。
   - 用反向引用 `\2` **保留原引号**，否则 `{"apiKey":"x"}` 会变成坏 JSON
   - 值要求 ≥12 字符，避免误伤 `"token": "auto"`
   - 只扫：node_modules 之外 + 配置类扩展名 + 1…256KB（`isRedactable`）
   - 日志 `🧽 已在 N 个配置文件中抹掉疑似密钥` / `🧽 内容复检：未发现…`
3. **导入端兜底**（`DSHRunner.installDsh`）—— 数据落盘后删掉
   `PACKAGE_SECRET_FILES`（`.env`、`android-account.json`），日志
   `🔑 已丢弃整合包里的 N 个密钥文件`。
   必要性：**用户手里的包可能是旧版本导出的**，一定带密钥。
   删掉不影响可用性：`AccountStore` 下次启动会用本机账号重新生成。

**`PACKAGE_SECRET_FILES` 必须显式标注 `List<String>`**：无类型推断时
`for (x in …)` 会因 `iterator()` 重载过多报 `Method 'iterator()' is ambiguous`
（伴随 `Unresolved reference`）。

**⚠️ 已泄露的 key 只能吊销**：改代码追不回已发出的文件。

---

## 5. 导出/导入后插件报 Cannot find module

**现象**：实例导出 zip → 导入 → 启用 `dsh-mcp-connector` 失败：
```
failed to import: Cannot find module './lib/web/cookies'
Require stack: - .../profiles/web/node_modules/undici/index.js
```

**真因**：`isCredentialPath()` 按**路径段名字**排除登录状态，
而数据侧打的是**整个 `DSH_HOME`**，其中 `profiles/<profile>/node_modules/`
混着大量第三方包源码。`undici/lib/web/cookies/` 命中 `CREDENTIAL_NAMES` 里的
`"cookies"` → 整个目录没进包 → 导入后 undici 的 index.js require 不到。
同理会中招的名字：`auth` `account` `login` `token` `secret` `credential`。

**铁律**：按名字猜凭据时，**必须先排除包内容**：
- 相对路径含 `node_modules` 段 → 一律进包（`isInsideNodeModules`）
- 代码类扩展名（`.js/.mjs/.cjs/.ts/.map/.css/.html/…`）→ 一律进包
  （`hasCodeExtension`，兜住不是 npm 布局的插件）

凭据不会长成 `.js`。**但硬规则（第 4 节）不给这两个豁免。**

**导入侧没有备份可救**：`installDsh` 只有在目标 `.dsh-<key>` **已存在**时才改名
`.dsh-<key>.bak`；`allocateVersion` 保证新键空闲，所以首次导入的坏数据
**没有 .bak**。修法只能「用修好的版本重新导出再导入」，或在该实例里重装插件
（npm 会把 undici 拉全）。

---

## 6. 「用浏览器打开」鉴权失败

现象：内置 WebView 正常，但 `ACTION_VIEW` 打开浏览器报
`dsh web authentication required; reopen the URL printed by dsh web`
（与完整 HTML 一起返回 —— SPA 外壳正常、只有会话鉴权失败）。

### 已证伪的三条路（不要再试）

1. **「token 一次性 → 自动重启换新地址」** —— 用户明确反对（中断 agent 任务、
   内置窗口失效），且实测无效。
2. **「用户显式点『重启后打开』」** —— 实测日志决定性否掉：全新令牌、端口换了、
   内置窗口没抢先（无 `📱` 行），**仍然失败**；且中断 agent 任务。
   已回退，`pendingBrowserOpen` / `urlConsumed` 字段一并删掉。
3. **「localhost → 127.0.0.1 别名替换」** —— 日志两次都是
   `localhost 未解析到 127.0.0.1，跳过别名替换`，即替换从未执行
   （该设备 localhost 解析到别的地址，可能 `::1`；dsh 若只绑 IPv4 也不通）。**代码已删。**

### 当前最强假设：浏览器侧 Cookie

dsh 会话 Cookie 是 HttpOnly + SameSite=Strict、绑定 host:port 但**不按端口隔离**，
前几轮在 `127.0.0.1:3080` 留下的旧 Cookie 会被带到 3081；
服务端把「没 Cookie / 签名不过 / 过期 / authority 不符」全并成同一句 401 且不写日志。

**待做的免代码验证**（必须用户/设备 agent 跑，代码侧无法代劳）：
1. 复制日志里完整 `dsh web:` 地址，在**无痕标签页手动粘贴**打开。
2. 不行则清掉 `127.0.0.1` 站点 Cookie，再在普通标签手动粘贴。

| 结果 | 结论 | 下一步 |
|---|---|---|
| 手动粘贴能进、App 按钮不能 | 「从外部应用发起」导致 Cookie 丢失 | 上回环反代替浏览器附 Cookie（大改动） |
| 无痕能进、普通标签不能 | 旧 Cookie | 清 Cookie 即可，App 里给提示 |
| 无痕也不能进 | 不是 Cookie 罐问题 | 转查遗留进程那条线 |

`connection.authenticatedUrl(baseUrl)`（host 进程里的重新签发入口）**先放下** ——
它解决的是令牌问题，而令牌已被证明不是原因。

**另一个待查的独立异常**：Node 已退出后新实例仍报「3080 已被占用」
（日志 40–49 行），怀疑有遗留旧引擎进程占端口且与新实例共用同一份数据目录。

---

## 7. 启动耗时：瓶颈在引擎，不在本仓库

**实测数据**（真机 0.2.2-rc.2，带 `⏱` 与逐行 `+Nms` 时间戳的日志）。

### 我们自己的代码：约 0.1 秒

| 阶段 | 耗时 |
|---|---|
| 容器与引擎校验 | 1ms |
| 装配 shim（flock/pty/fs/shell/原生绑定） | 26ms |
| 数据目录与附件准备 | 55ms |
| 扫描引擎参数（11 个） | 7ms |
| 端口确定 / Shizuku 通道 / 环境装配 | 0 / 1 / 5ms |
| **`pb.start()` 返回（累计）** | **97ms** |
| WebView 实例化 / 页面加载 | 15ms / 1281ms |

（曾有一项「WebView 预热 8ms」，已随 `WebViewWarmup` 一并删除 —— 它反而是
把引擎自举拖慢的嫌疑项之一，见下面两节「已被证伪」。）

### 引擎侧：11.5 秒，逐段如下

| 区间 | 耗时 | 内容 |
|---|---|---|
| `pb.start` → `+228ms` | 228ms | Node 进程自身启动 |
| `+235ms` → `+7358ms` | **7123ms** | **完全静默**：加载 node_modules（成千上万模块） |
| `+7358ms` → `+11407ms` | 4049ms | skill-panel → web 服务就绪 |
| `+11407ms` → `+11408ms` | 1ms | 尝试开系统浏览器并失败（`--no-open` 不被支持） |
| `+11812ms` | 405ms | `web-app: could not open the default browser` |
| `+13136ms` | 1324ms | `https://api.deepseek.com/models` 模型列表（页面加载后才发生） |

**结论**：再优化我们自己的代码也不会让用户感觉变快 —— 别在这上面白花时间。
引擎侧的固有开销改不动，除非去动 `node_modules` 里的 `@deepseek-ai/*` 包
（升级即被覆盖、且难验证，不建议轻易做）。

### ✗ 已被证伪：诊断与引擎抢磁盘 I/O 不是瓶颈

曾判断那 7 秒静默期是「引擎把闪存 I/O 压满、而 `logEngineState` 同时
递归遍历 `node_modules` 在抢同一块磁盘」，于是改成 `startEngineStateDiag`
**等引擎就绪再跑**（`engineReady` / `engineDone` / 20s 超时三个放行条件）。

**实测否定了这个假设**：改完后引擎反而从 11504ms 变成 14193ms，
静默期从 7.1s 变成 9.0s，而诊断已确认挪到 `dsh web:` 之后才跑（它不再抢 I/O）。

结论：那次改动**收益为零**（保留它，因为「诊断排在启动之后」本身更合理，
但不要把它当成提速手段）。**运行间方差高达 2.7 秒**（冷热缓存、后台应用、
闪存状态），所以单次日志不能用来判断某个改动是否有效 —— 至少跑两三次再比。

### 并行化：不可行，别试

- 我们自己的准备步骤合计 **111ms**，全部并行最多省几十毫秒（占 14s 的 0.4%），
  而 `ensureFlockShim` / `RequireBuiltinShim` / `PtySupport.ensure` 都会往
  同一棵 `node_modules` 写文件，并发跑反而引入竞态。
- 那 9 秒是 **V8 在主线程编译模块，单线程，外部改不了** —— 没有可并行的入口。

### ✗ 已被证伪并**已从代码中删除**：两个「提速」环境变量

**1. `NODE_COMPILE_CACHE`（Node ≥22.1）** —— 想把 V8 编译产物落盘复用。
漏掉的关键点：**缓存为空时要在编译每个模块的同时写一份缓存**，几千个模块
就是几千次随机写，**第一次启动必然更慢**，只有第二次起才可能变快。
实测引擎自举从 11.5s 涨到 15.7s。

**2. `UV_THREADPOOL_SIZE = CPU 核数`** —— 想让模块加载多用几个核。
但 **CommonJS 的模块加载走同步 fs，根本不经过 libuv 线程池**；
而手机闪存并发队列本来就浅，8 个线程只会增加排队与上下文切换开销。

两条都已从 `startNode` 的 env 里删除，只留下注释说明为什么不能再加。

### ✗ 已被证伪并**已从代码中删除**：`WebViewWarmup`

当初的假设：`WebView(this)` 进程内首次实例化要 1～2 秒（Chromium provider
冷启动），所以趁引擎在后台启动的空档先预热掉。

实测证伪了两点：
1. 实例化只要 **15～18ms**，根本没有 1～2 秒 —— 收益本来就不存在；
2. 预热在 `postDelayed(60ms)` 就启动，正好**和 Node 加载模块并发**，
   而 Chromium provider 要拉独立进程、占几百 MB 内存，跟 Node 抢 CPU 与内存。

为一个 18ms 的开销去和 9 秒的模块加载抢资源，完全不划算。文件已删除，
`DshWebActivity` 里只保留 `⏱ WebView 实例化` 那行计时（以后若有人说
「WebView 打开慢」，先看这个数字）。

### 教训：不要把「听起来合理」当优化合进启动路径

四次真机日志：**12507 / 11504 / 14193 / 15718ms**。趋势与「我加入这些
调优」重合，但**没有 A/B 对照**就无法归因 —— 而我在没有对照的情况下
连着加了**四个**改动（诊断让路、编译缓存、线程池、WebView 预热），
结果整体变慢，无法判断是谁的锅，只能全部回退。

规则：
- 一次只加一个变量，跑 2～3 次取中位数再判断；
- 拿不到对照数据时，"不做"比"做一个可能有害的改动"正确；
- **运行间方差可达 2.7 秒**（冷热缓存、后台应用、闪存状态），
  单次日志不足以判断任何一个改动是否有效。

### ★ 踩坑：局部函数定义了却从不调用，不会报错 ★

给启动流程加计时时写了 `fun mark(label)` 却一次没调用 —— 局部函数未被使用
**只有 warning**，编译照样通过，结果第一次真机日志里一行 `⏱` 都没有，
白跑了一轮验证。加诊断代码后务必确认它真的出现在日志里。

同类的坑还有一次：把 `logEngineState` 从 `pb.start()` 之前挪到之后，
注释写了「不阻塞启动」——**判断是错的**。它虽在 `pb.start()` 之后，
却仍在 **stdout 读取循环之前**，串行执行期间引擎输出全积在管道里没人读。

### 启动路径上做过的改动（哪些保留、哪些已回退）

**保留**（有实测依据或纯正确性修复）：

1. **日志批量投递**（`AppState.installRunnerCallbacks`）—— 原来是
   `onLog = { msg -> main.post { launcher.log(msg) } }`，一行一个 Handler
   Message，引擎启动几百行就把主线程灌满。现在攒进 `pendingLogs` 一批一次投递。
2. **延迟诊断队列**（`deferDiag` / `runDeferredDiagnostics`）—— **启动路径上不做
   任何只产出日志的活**。深度 6 递归遍历 `node_modules` 的自检（实测 2760～3130ms）、
   6 行路径回显、`SandboxBackend.logReport`（会起 `bwrap --version` 子进程）、
   `SandboxExec.describe` 全部改成「入队一个 lambda」，启动阶段零 I/O。
   两个触发点：`DshWebActivity.onPageFinished → notePageLoaded()`（正常，用户已在
   用界面）、`if (process === started)` 内 → `runDeferredDiagnostics("引擎异常退出")`
   （崩溃/启动失败，最需要诊断）。详见下面「延迟诊断」一节。
3. **`engineSupportedFlags` 加磁盘缓存** —— 落在引擎包目录的 `.launcher-flags`，
   用 `bin.js` 的 **size + mtime** 做戳，换版本自动失效。否则每次冷启动都要把
   `lib/` 下所有 JS 读一遍跑正则。
4. **端口分配挪到「知道引擎支不支持 `--port`」之后** —— 原来先
   `allocatePort(3080)` 再判参数，而 0.2.2-rc.x **全线不认 `--port`**：
   白扫最多 20 个端口，`currentPort` 被写成 3081，引擎实际用 3080，
   日志里报的是**假端口**（曾把「怀疑有遗留进程占 3080」的排查带偏）。
   现在不支持就**不扫**，如实报「由引擎决定」。
5. **引擎输出逐行时间戳** `+Nms`（`DSHRunner` 的 stdout/stderr 循环）——
   只影响显示字符串，`rememberOutput` 与 `EngineLogTap` 拿到的仍是原始行
   （否则 URL 正则 / 命令识别会因前缀失配）。**这是排查启动耗时的唯一有用工具**。
6. **`resetDiag()` 必须在任何 `deferDiag` 之前** —— 见下面「延迟诊断」一节的时序坑。
7. **`⏱ WebView 实例化` 计时**（`DshWebActivity`）—— 一行计时，保留。
   它当初推翻了 `WebViewWarmup` 的错误假设（实测 15～18ms，不是 1～2 秒）。

**已回退**（不要再加回来）：

- `WebViewWarmup`（整个文件已删除）
- `NODE_COMPILE_CACHE` 环境变量
- `UV_THREADPOOL_SIZE` 环境变量

三者的证伪过程见上面两节「✗ 已被证伪并已从代码中删除」。

### 延迟诊断（`deferDiag` / `runDeferredDiagnostics`）

**原则：启动路径上不做任何「只产出日志」的 I/O。**

之前那些诊断（引擎自检、沙箱探测、路径回显）无论怎么挪位置都还在启动流程里，
每一次挪动都只能减少一点阻塞，收益上限很低。现在彻底反过来：**只入队，不执行**。

```kotlin
private val diagTasks = ArrayList<() -> Unit>()   // 攒着
private fun deferDiag(task: () -> Unit) { … }     // 启动期调用它 = 零 I/O
fun runDeferredDiagnostics(reason: String) { … }  // 后台守护线程一次性跑完，幂等
fun notePageLoaded() { runDeferredDiagnostics("页面已就绪") }
```

**入队的项**（都不再占用启动时间）：

| 项 | 原耗时 |
|---|---|
| `logEngineState`（深度 6 递归 `node_modules`） | 2760～3130 ms |
| 6 行路径回显（cwd / Documents / DSH_HOME / 数据目录 / 权限模式 …） | 忽略不计 |
| `SandboxBackend.logReport`（起 `bwrap --version` 子进程） | 最多 1.5 s 超时 |
| `SandboxExec.describe` | 忽略不计 |

**两个触发点**：

1. `DshWebActivity` 的 `onPageFinished` → `DSHRunner.notePageLoaded()`
   —— 正常启动。此刻页面已显示、用户已在用界面，磁盘与 CPU 都空出来了。
   **不用「引擎吐出 `dsh web:` 的那一刻」**：那时 WebView 还在建、页面还在加载。
2. `startNode` 的 `if (process === started)` 内 → `runDeferredDiagnostics("引擎异常退出")`
   —— 崩溃 / 启动失败。这时没有页面可等，而它恰恰最需要诊断信息。

**留在启动路径上的唯一告警**：`⚠️ 完全访问模式` —— 关系安全边界，不能延迟。

#### ★ 时序坑：`resetDiag()` 必须在任何 `deferDiag` 之前 ★

最初把 `resetDiag()` 写在 `pb.start()` 之前（也就是 `deferDiag` **之后**），
结果它把本轮刚入队的任务全清掉了。表现是**页面就绪后日志里什么都没有，
而代码看起来完全正常** —— 没有编译错误、没有运行时异常，纯静默失败。

凡是「先攒后跑」的队列，复位必须发生在第一次入队之前。
现在 `resetDiag()` 位于 `startNode` 的 `try` 块最开头。

#### 为什么退出触发要放进 `if (process === started)` 而不是 `finally` 顶部

`stopProcess()` 会先把 `process` 置空，所以用户主动停止时会跳过这个分支 ——
否则一次「停止 → 再启动」的重启流程里，**旧进程**的收尾线程会把**新一轮**
刚入队的诊断提前吃掉，页面就绪时反而什么都不打印。

---

## 8. 版本号三处同步

版本号在**三个地方**，发版时必须一起改：

1. `app/module.toml` —— `versionCode` / `versionName`（构建系统的输入）
2. `app/src/main/AndroidManifest.xml` —— `android:versionCode` / `android:versionName`
3. `app/src/main/kotlin/com/example/fcldsh/AppBuildInfo.kt` —— `VERSION_CODE` / `VERSION_NAME`

**为什么要有第三处**：CodeAssist 的构建系统把工程模型留在**进程内存**里，
`module.toml` 改动后重新生成的 `BuildConfig` 与合并清单常给出**上一版的值**，
删掉 `gen-build-config`、`merged-manifest`、`resources.ap_` 与 APK 全部中间产物
再重建也依然如此。唯一能刷新它的是**重启 CodeAssist**（重新解析 `module.toml`）。

因此界面上的「应用版本」取 `AppBuildInfo.VERSION_NAME`（随源码走），
并同时显示已安装 APK 的 `PackageManager.versionName`，两者不一致时一眼可见。

⚠️ `versionCode` **必须递增**，否则同包名覆盖安装会被系统拒绝。

---

## 9. 铁律速查

- **多个调用点各自推导同一个根路径** = 踩过的坑。HOME / DSH_HOME / usr 一律走
  `DSHRunner.instanceHome()` / `ensureVersionDataDir()` 这类单一入口。
- `ProcessBuilder.environment()` 在 Kotlin 里是平台类型，别当 `MutableMap` 形参传；
  需要改环境时自己建 `LinkedHashMap` 再 `putAll`（见 `startNode`）。
- Root 模式下 `pb.environment()` 会被**整个丢弃**（命令经 `su -c "<整行>"`），
  所有 env 必须拼进命令行 —— 新增环境变量时两条路径都要覆盖。
- 进程 stdout/stderr 的监听线程**必须自己吞异常**：未捕获异常会杀掉整个 App 进程
  （「点停止就崩溃」的成因）。
