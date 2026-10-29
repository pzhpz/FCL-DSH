package com

import java.io.File

/**
 * 为 agent 安装 `a11y` 命令行入口。
 *
 * ## 为什么单独一个文件
 *
 * [DSHRunner] 已经有 2500+ 行，其中包含几个很大的 JS raw string。
 * 把这段逻辑放在同级包里，既避免继续膨胀那个文件，也避开了
 * 「往超大文件里做定点替换时容易失手」的问题。
 *
 * ## 为什么是包内顶层函数，而不是 [DSHRunner] 的成员
 *
 * `DSHRunner.startNode()` 里的调用点写的是裸名 `ensureA11yCli(prefix)`。
 * Kotlin 解析裸名时会先找成员、再找**同包的顶层函数** ——
 * 所以这里用 `internal` 顶层函数即可被它解析到，不必改动 DSHRunner 一行。
 *
 * ## 为什么要做成命令，而不是让 agent 自己 curl
 *
 * 参考实现（Deepseek-Harness-Local-Android 的 `AgentContextSeed`）就是这么做的：
 * 它在 PATH 上放 `scr` / `loop` / `notify` 等包装命令，文档明确写
 * 「通常不需要去找 bridge token、不需要 curl 端口」。我们照抄这个思路。
 *
 * 之前只在 AGENTS.md 里教 agent「先 cat 凭据再拼 curl」—— 步骤多、容易错，
 * 且 token 轮换即失效。做成命令后鉴权在脚本内部完成，agent 只要 `a11y shot`。
 *
 * ## 为什么外层是 sh 脚本
 *
 * bash 直接 execve 应用数据目录里的 ELF 会 EACCES（W^X 限制），
 * 但 execve **脚本**时内核按 shebang 转给 `/system/bin/sh`（系统文件，允许执行）。
 * 脚本里再 `exec /system/bin/linker64 <node> <cli>`，与启动引擎走同一套加载方式。
 * 首行必须是 `#!/system/bin/sh`，且要带 `fcl-shim` 标记（spawn 补丁靠它识别）。
 *
 * @param prefix 容器的绝对路径（通常是 `filesDir/usr`），其下有 `bin/` 与 `lib/`。
 * @return 两个文件都写入成功时返回 true。
 */
internal fun ensureA11yCli(prefix: String): Boolean {
    val binDir = File(prefix, "bin").apply { mkdirs() }
    val libDir = File(prefix, "lib").apply { mkdirs() }

    val cliOk = runCatching {
        File(libDir, A11Y_CLI_FILE).writeText(A11Y_CLI_JS)
        true
    }.getOrDefault(false)

    // 脚本里的路径用绝对路径写死：spawn 时的环境变量未必与启动进程一致，
    // 靠 $PREFIX 拼有落空风险。
    val body = """
        #!/system/bin/sh
        # fcl-shim a11y
        # 手机能力桥接的命令行入口。鉴权在 a11y-cli.mjs 内部完成，
        # agent 不需要读取 token，也不需要自己拼 curl。
        exec /system/bin/linker64 "$prefix/bin/node" "$prefix/lib/$A11Y_CLI_FILE" "${'$'}@"
    """.trimIndent() + "\n"

    val shimOk = runCatching {
        val shim = File(binDir, "a11y")
        shim.writeText(body)
        // 写文件不还原权限，必须显式设置，否则 sh 打不开、报 Permission denied
        shim.setExecutable(true, false)
        true
    }.getOrDefault(false)

    val ok = cliOk && shimOk
    if (ok) {
        DSHRunner.onLog("🔗 已安装 a11y 命令（agent 用 `a11y shot` / `a11y status` 调用）")
    } else {
        DSHRunner.onLog("⚠️ 安装 a11y 命令失败（cli=$cliOk shim=$shimOk）")
    }
    return ok
}

/** 真正的实现文件名。 */
private const val A11Y_CLI_FILE = "a11y-cli.mjs"

/**
 * `a11y` 命令的实现（Node ESM 脚本）。
 *
 * ⚠️ 这是 Kotlin 的 raw string：内部**不能出现 Kotlin 模板起始序列**
 * （`$` 紧跟 `{`）。所以 JS 里一律用字符串拼接，不用 JS 模板字面量。
 * 这些字符串里目前没有任何 `$` 字符 —— 改文案时请保持这一点。
 */
private val A11Y_CLI_JS = """
    // a11y — 手机能力桥接的命令行入口（由 FCL-DSH 自动生成，请勿手改）
    import { readFileSync } from 'node:fs';
    import http from 'node:http';

    function fail(msg, code) {
      console.error('a11y: ' + msg);
      process.exit(code || 2);
    }

    const home = process.env.HOME || '';
    const cfgPath = home + '/a11y-bridge.json';

    let cfg;
    try {
      cfg = JSON.parse(readFileSync(cfgPath, 'utf8'));
    } catch (e) {
      fail('读不到 ' + cfgPath + ' —— 无障碍桥接未启动。' +
        '请让用户到 系统设置 → 无障碍 开启「FCL-DSH 屏幕操作」');
    }

    const argv = process.argv.slice(2);
    const c = argv[0] || '';
    let reqPath = '/status';
    let method = 'GET';
    let onlyPath = false;

    if (c === '' || c === 'status') {
      reqPath = '/status';
    } else if (c === 'shot' || c === 'screenshot') {
      reqPath = '/screenshot';
      onlyPath = true;
    } else if (c === 'screen' || c === 'text') {
      reqPath = '/screen/text?limit=' + (argv[1] || '200');
    } else if (c === 'tree') {
      reqPath = '/screen?limit=' + (argv[1] || '200');
    } else if (c === 'tap') {
      reqPath = '/tap?x=' + encodeURIComponent(argv[1] || '') +
        '&y=' + encodeURIComponent(argv[2] || '');
      method = 'POST';
    } else if (c === 'tapText') {
      reqPath = '/tapText?text=' + encodeURIComponent(argv[1] || '');
      if (argv[2] === 'exact') reqPath += '&exact=true';
      method = 'POST';
    } else if (c === 'input' || c === 'type') {
      // input <文字> [target提示]   整体替换输入框内容
      reqPath = '/input?text=' + encodeURIComponent(argv[1] || '') +
        (argv[2] ? '&target=' + encodeURIComponent(argv[2]) : '');
      method = 'POST';
    } else if (c === 'append') {
      // append <文字> [target提示]  在原有内容后追加
      reqPath = '/input?append=true&text=' + encodeURIComponent(argv[1] || '') +
        (argv[2] ? '&target=' + encodeURIComponent(argv[2]) : '');
      method = 'POST';
    } else if (c === 'clear' || c === 'clearInput') {
      // clear [target提示]          清空输入框
      reqPath = '/input?text=' +
        (argv[1] ? '&target=' + encodeURIComponent(argv[1]) : '');
      method = 'POST';
    } else if (c === 'longPress') {
      reqPath = '/longPress?x=' + encodeURIComponent(argv[1] || '') +
        '&y=' + encodeURIComponent(argv[2] || '') +
        '&ms=' + (argv[3] || '800');
      method = 'POST';
    } else if (c === 'back') {
      reqPath = '/back';
      method = 'POST';
    } else if (c === 'home') {
      reqPath = '/home';
      method = 'POST';
    } else if (c === 'windows' || c === 'win') {
      reqPath = '/windows';
    } else if (c === 'swipe') {
      reqPath = '/swipe?x1=' + encodeURIComponent(argv[1] || '') +
        '&y1=' + encodeURIComponent(argv[2] || '') +
        '&x2=' + encodeURIComponent(argv[3] || '') +
        '&y2=' + encodeURIComponent(argv[4] || '') +
        '&ms=' + (argv[5] || '600');
      method = 'POST';
    } else if (c === 'drag') {
      reqPath = '/drag?dx=' + encodeURIComponent(argv[1] || '') +
        '&dy=' + encodeURIComponent(argv[2] || '');
      method = 'POST';
    } else if (c === 'minimize' || c === 'min') {
      reqPath = '/minimize?scale=' + (argv[1] || '0.55');
      method = 'POST';
    } else if (c === 'check') {
      // check <x> <y>            目标单点
      // check <l> <t> <r> <b>    目标矩形
      reqPath = argv.length >= 5
        ? '/check?l=' + encodeURIComponent(argv[1] || '') +
          '&t=' + encodeURIComponent(argv[2] || '') +
          '&r=' + encodeURIComponent(argv[3] || '') +
          '&b=' + encodeURIComponent(argv[4] || '')
        : '/check?x=' + encodeURIComponent(argv[1] || '') +
          '&y=' + encodeURIComponent(argv[2] || '');
    } else if (c === 'mode') {
      reqPath = '/windows';
    } else if (c === 'step' || c === 'smaller') {
      reqPath = '/window/step?scale=' + (argv[1] || '0.55');
      method = 'POST';
    } else if (c === 'restore' || c === 'expand') {
      reqPath = '/window/restore';
      method = 'POST';
    } else if (c === 'help' || c === '--help' || c === '-h') {
      console.log('用法: a11y <命令> [参数]');
      console.log('  status              可用性检查（含截图通道）');
      console.log('  shot                截图，stdout 输出 PNG 绝对路径');
      console.log('  screen [n]          读屏纯文本（默认 200 条）');
      console.log('  tree [n]            读屏节点 JSON（含坐标与可点击性）');
      console.log('  tap <x> <y>         按屏幕像素点击');
      console.log('  tapText <文字> [exact]  按文字点击（优先用这个）');
      console.log('  input <文字> [提示]     往输入框写整段文字（不用逐字点按键）');
      console.log('  append <文字> [提示]    在输入框原有内容后追加');
      console.log('  clear [提示]            清空输入框');
      console.log('  longPress <x> <y> [ms]');
      console.log('  back / home         返回键 / 回桌面');
      console.log('');
      console.log('小窗（需用户先手动把本应用设为小窗）:');
      console.log('  mode                本应用窗口范围 + 形态 large/small/ball/unknown');
      console.log('  check <x> <y> | <l> <t> <r> <b>   目标是否被小窗遮住');
      console.log('  drag <dx> <dy>      拖动小窗（抓顶部中央，正=右/下）');
      console.log('  step [scale]        缩小一档：大窗 → 小窗 → 悬浮球');
      console.log('  restore             从悬浮球/小窗恢复（点最后已知位置）');
      console.log('  minimize [scale]    step 的旧名，语义相同');
      console.log('  swipe <x1> <y1> <x2> <y2> [ms]    任意滑动');
      console.log('  也可直接传路径: a11y /screen?limit=50');
      process.exit(0);
    } else if (c.charAt(0) === '/') {
      reqPath = c;
    } else {
      reqPath = '/' + c;
    }

    const req = http.request({
      host: '127.0.0.1',
      port: cfg.port,
      path: reqPath,
      method: method,
      headers: { 'X-A11y-Token': cfg.token }
    }, function (res) {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', function (chunk) { body += chunk; });
      res.on('end', function () {
        let json = null;
        try { json = JSON.parse(body); } catch (e) { json = null; }

        // shot 只输出路径：agent 拿到后直接用读图能力打开，
        // 不必再解析 JSON，也不用手动 base64。
        if (onlyPath) {
          if (json && json.ok && json.path) {
            console.log(json.path);
            if (json.ageMs && json.ageMs > 1500) {
              console.error('a11y: 这一帧已过期 ' + json.ageMs + 'ms');
            }
            process.exit(0);
          }
          console.error(body);
          process.exit(1);
        }

        console.log(body);
        process.exit((json && json.ok === false) ? 1 : 0);
      });
    });

    req.on('error', function (e) {
      fail('连接 127.0.0.1:' + cfg.port + ' 失败: ' + e.message);
    });

    req.setTimeout(15000, function () {
      req.destroy();
      fail('请求超时');
    });

    req.end();
""".trimIndent()
