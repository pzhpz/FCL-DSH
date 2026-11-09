package com

import java.io.File

/**
 * 为 agent 安装 `shz` 命令行入口 —— Shizuku(adb 身份) 执行通道。
 *
 * ## 为什么需要它
 *
 * 引擎是独立进程，够不到本应用的 Shizuku binder（见
 * [com.example.fcldsh.ShizukuBridge] 的注释）。`shz` 是那条通路的命令行外壳：
 * agent 执行 `shz <任意命令>`，它把命令 POST 到本应用的桥，由桥以 adb(uid 2000)
 * 身份执行并把输出原样带回来。
 *
 * ## ★ 为什么「通道不可用」时也要装它 ★
 *
 * 早先的实现是「连不上 Shizuku 就不生成 `shz`」。结果出现了一个极难自查的状态：
 *
 * ```
 * command -v shz   → 什么都没有
 * shz id           → 127（命令不存在）
 * ```
 *
 * 用户和 agent 都只能看到「不存在」，看不到「为什么不存在」—— 于是反复猜
 * 「是不是没重启引擎」「是不是模式没生效」，实际原因（Shizuku 服务没在跑）
 * 被完全遮住了。
 *
 * 现在 `shz` **永远存在**：
 * - 通道可用 → 正常执行命令；
 * - 通道不可用 → 打印一份精确诊断（模式、SDK、是否安装、服务是否在跑、
 *   是否授权、以及该怎么做），退出码 2。
 *
 * 一个会说清理由的命令，远好过 `command -v` 没输出。
 *
 * ## 为什么外层是 sh 脚本
 *
 * bash 直接 execve 应用数据目录里的 ELF 会 EACCES（Android 10+ 的 W^X），
 * 但 execve **脚本**时内核按 shebang 转给 `/system/bin/sh`（系统文件，允许执行）。
 * 脚本里再 `exec /system/bin/linker64 <node> <cli>`，与启动引擎走同一套加载方式。
 * 首行必须是 `#!/system/bin/sh`，且要带 `fcl-shim` 标记 —— spawn 补丁靠它识别，
 * 缺了这个标记它会被当成 ELF 喂给 linker64，必然失败。
 *
 * @param prefix 容器绝对路径（`filesDir/usr`），其下有 `bin/` 与 `lib/`
 * @return 两个文件都写入成功时返回 true
 */
internal fun ensureShizukuCli(prefix: String): Boolean {
    val binDir = File(prefix, "bin").apply { mkdirs() }
    val libDir = File(prefix, "lib").apply { mkdirs() }

    val cliOk = runCatching {
        File(libDir, SHZ_CLI_FILE).writeText(SHZ_CLI_JS)
        true
    }.getOrDefault(false)

    // 脚本里的路径用绝对路径写死：spawn 时的环境变量未必与启动进程一致，
    // 靠 $PREFIX 拼有落空风险。
    val body = """
        #!/system/bin/sh
        # fcl-shim shz
        # Shizuku(adb 身份) 执行通道的入口。
        # 通道不可用时不会「命令不存在」，而是打印一份诊断说明为什么不可用。
        #
        # OpenSSL 配置必须在这里自己补：
        # 本命令由 Shizuku / adb(uid 2000) 身份启动，环境变量来自那边，
        # **不继承本应用进程**，所以启动引擎时设的 OPENSSL_CONF 到这里已经丢了。
        # 而容器里的 libssl 编译期把 OPENSSLDIR 写死成 Termux 的私有目录
        # `/data/data/com.termux/files/usr/etc/tls`：装了 Termux 的设备上那是
        # 别人的 0700 目录，OpenSSL 读配置会 EACCES 并以退出码 13 结束
        # （`node --version` 不触发配置加载所以看不出来，一碰 crypto/https 就挂）。
        # 只在文件真实存在时指过去 —— 指向不存在的文件比不设更糟。
        if [ -f "$prefix/etc/tls/openssl.cnf" ]; then
            OPENSSL_CONF="$prefix/etc/tls/openssl.cnf"
            export OPENSSL_CONF
        fi
        exec /system/bin/linker64 "$prefix/bin/node" "$prefix/lib/$SHZ_CLI_FILE" "${'$'}@"
    """.trimIndent() + "\n"

    val shimOk = runCatching {
        val shim = File(binDir, "shz")
        shim.writeText(body)
        // 写文件不还原权限，必须显式设置，否则 sh 打不开、报 Permission denied
        shim.setExecutable(true, false)
        true
    }.getOrDefault(false)

    return cliOk && shimOk
}

/** 真正的实现文件名。 */
private const val SHZ_CLI_FILE = "shz-cli.mjs"

/**
 * `shz` 命令的实现（Node ESM 脚本）。
 *
 * ⚠️ 这是 Kotlin 的 raw string，而 raw string **仍然做模板插值**。
 * 不只是 `` `${` ``，`` `$` 紧跟标识符 `` 一样会被替换：这里写 `$HOME`
 * 会在编译期报 `Unresolved reference 'HOME'`（已实测踩过）。
 *
 * 要输出字面 `$`，必须写成 `${'$'}`。所以：
 * - JS 里一律用字符串拼接，不用 JS 模板字面量；
 * - 注释里出现环境变量时，写 `${'$'}HOME` 或干脆去掉 `$`。
 *
 * 改文案时请保持这一点，否则会在编译期而非运行期出错。
 */
private val SHZ_CLI_JS = """
    // shz — Shizuku(adb 身份) 执行通道（由 FCL-DSH 自动生成，请勿手改）
    //
    // 用法：
    //   shz status          查看通道状态（总是可用，含不可用的原因）
    //   shz <命令>           以 adb(uid 2000) 身份执行
    //
    // 端口与 token 从 ${'$'}HOME/shz-status.json 读，由本应用在通道状态变化时更新。
    import { readFileSync } from 'node:fs';
    import http from 'node:http';

    const home = process.env.HOME || '';
    const statusPath = home + '/shz-status.json';

    let st = null;
    try {
      st = JSON.parse(readFileSync(statusPath, 'utf8'));
    } catch (e) {
      st = null;
    }

    function printStatus() {
      if (st === null) {
        console.log('shz: 读不到状态文件 —— ' + statusPath);
        console.log('  说明本应用还没初始化过 Shizuku 通道。');
        console.log('  请先打开一次 FCL-DSH（它会写入状态文件并安装本命令）。');
        return 2;
      }
      console.log('运行模式        : ' + st.mode);
      console.log('Android SDK     : ' + st.sdk);
      console.log('系统支持 Shizuku: ' + st.supported);
      console.log('已安装 Shizuku  : ' + st.installed);
      console.log('服务在运行      : ' + st.running);
      console.log('已授权本应用    : ' + st.granted);
      console.log('通道状态        : ' + st.state);
      console.log('桥端口          : ' + (st.port || 0));
      if (st.reason) {
        console.log('');
        console.log('原因: ' + st.reason);
      }
      if (st.hint) {
        console.log('处理: ' + st.hint);
      }
      return (st.port || 0) > 0 ? 0 : 1;
    }

    const argv = process.argv.slice(2);
    const sub = argv[0] || '';

    // 无参数 / status / help：只报状态，不执行业务命令。
    if (sub === '' || sub === 'status' || sub === 'help' || sub === '-h') {
      process.exit(printStatus());
    }

    // 要执行命令：先确认通道真的可用。
    if (st === null || !(st.port > 0)) {
      printStatus();
      console.log('');
      console.log('shz: 通道不可用，命令未执行。');
      process.exit(2);
    }

    const payload = argv.join(' ');
    const req = http.request({
      host: '127.0.0.1',
      port: st.port,
      path: '/exec',
      method: 'POST',
      headers: {
        'X-Shz-Token': st.token || '',
        'Content-Type': 'text/plain; charset=utf-8',
        'Content-Length': Buffer.byteLength(payload)
      }
    }, (res) => {
      let out = '';
      res.setEncoding('utf8');
      res.on('data', (d) => { out += d; });
      res.on('end', () => {
        process.stdout.write(out);
        if (out.length > 0 && !out.endsWith('\n')) process.stdout.write('\n');
        process.exit(res.statusCode === 200 ? 0 : 1);
      });
    });

    req.on('error', (e) => {
      console.error('shz: 连接失败 —— ' + e.message);
      console.error('  端口 ' + st.port + '，应用可能已重启。');
      console.error('  请让用户重新打开一次 FCL-DSH。');
      process.exit(3);
    });

    req.write(payload);
    req.end();
""".trimIndent()
