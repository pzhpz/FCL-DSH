package com

import java.io.File

/**
 * 生成 Node 侧的 `fs` 补丁（`$PREFIX/lib/dsh-fs-patch.mjs`）。
 *
 * ## 为什么单独一个文件
 *
 * `DSHRunner.ensureFsPatch()` 里那段 JS 已经很长，而本次要往里加第二块补丁
 * （祖先目录 open 容错）。抽到包内顶层函数后：
 * - 不必继续膨胀 `DSHRunner.kt`（它已 2600+ 行，定点替换容易失手）；
 * - 内容与版本号聚在一处，改文案时不容易漏掉版本号。
 *
 * ## 与 `DSHRunner.ensureFsPatch()` 的关系
 *
 * 两者写**同一个文件**，用**同一个标记**（`DSH_LAUNCHER_FS_PATCH_V2`）。
 * 本函数由 `MainActivity.onCreate` 在 dsh 启动前调用，写入完整版本；
 * 之后 `ensureFsPatch()` 检查到标记已存在就跳过重写，直接返回路径。
 * 所以只要启动器跑过一次，生效的永远是这里的完整版本。
 *
 * 本函数自己的判据不是「标记存在」，而是「**内容特征存在**」
 * （`ANCESTOR_DIRS`）。这样即使顺序反过来（DSHRunner 先用旧内容写了
 * V2 标记），本函数仍会把它覆盖成完整版本。
 *
 * ## 补丁一：fs.link 降级为复制
 *
 * Android 的 `protected_hardlinks` / SELinux 会让 `fs.link()` 返回 EACCES，
 * 而 dsh 用「临时文件 + 硬链接」做会话文件的原子替换，失败即整个对话中断。
 * 只对 EACCES / EPERM / EXDEV 降级，其余错误（EIO、ENOSPC）照旧抛 ——
 * 真故障不该被掩盖。
 *
 * ## 补丁二：祖先目录只读 open 容错
 *
 * 见下方 JS 内的长注释。核心事实：**软链不改变字面路径**，
 * 所以「把子目录外置到 /storage」并不能让逐级上行的代码绕开 `/data/user/0`。
 *
 * @param prefix 容器根（`filesDir/usr`）。
 * @return 补丁文件路径；写入失败返回 null。
 */
internal fun ensureFsPatchV2(prefix: String): String? {
    val patchFile = File(prefix, "lib/dsh-fs-patch.mjs")
    patchFile.parentFile?.mkdirs()

    // 判据用「内容特征」而不是标记：见上面注释。
    val existing = runCatching { patchFile.readText() }.getOrNull().orEmpty()
    if (existing.contains("ANCESTOR_DIRS")) return patchFile.absolutePath

    // 「安全的目录 fd」：祖先目录 open 失败时用它的 fd 顶替。
    // 必须是应用自己拥有、能正常打开的目录 —— filesDir 就是（0700 属主是自己）。
    val safeDir = File(prefix).parentFile?.absolutePath ?: prefix

    val js = """
        // DSH_LAUNCHER_FS_PATCH_V2
        //
        // 由 DSH Launcher 自动生成，请勿手改。
        //
        // 两块补丁：
        //  1) fs.link 在 EACCES/EPERM/EXDEV 时降级为复制
        //  2) 祖先目录（/data/user/0 等 0711 目录）的只读 open 容错
        import fs from 'node:fs';
        import { syncBuiltinESMExports } from 'node:module';

        // ── 补丁一：硬链接降级 ───────────────────────────────────────────
        const FALLBACK = new Set(['EACCES', 'EPERM', 'EXDEV']);
        const log = (how, from, to, code) => {
          try {
            console.error('[dsh-launcher] fs.' + how + ' ' + code +
              '，已降级为复制: ' + from + ' -> ' + to);
          } catch { /* 日志失败不影响主流程 */ }
        };

        const origLink = fs.link;
        fs.link = function (from, to, cb) {
          return origLink.call(fs, from, to, (err) => {
            if (err && FALLBACK.has(err.code)) {
              log('link', from, to, err.code);
              return fs.copyFile(from, to, (e2) => cb(e2 || null));
            }
            cb(err);
          });
        };

        const origLinkSync = fs.linkSync;
        fs.linkSync = function (from, to) {
          try {
            return origLinkSync.call(fs, from, to);
          } catch (err) {
            if (err && FALLBACK.has(err.code)) {
              log('linkSync', from, to, err.code);
              return fs.copyFileSync(from, to);
            }
            throw err;
          }
        };

        const origPLink = fs.promises.link;
        fs.promises.link = async function (from, to) {
          try {
            return await origPLink.call(fs.promises, from, to);
          } catch (err) {
            if (err && FALLBACK.has(err.code)) {
              log('promises.link', from, to, err.code);
              return fs.promises.copyFile(from, to);
            }
            throw err;
          }
        };

        // ── 补丁二：祖先目录只读 open 容错 ───────────────────────────────
        //
        // 症状：
        //   EACCES: permission denied, open '/data/user/0'
        // 被打开的是**目录本身**，不是任何文件；报错停在 /data/user/0
        // 而不进入用户给的具体文件。换路径、复制文件、改 cwd 全部无效。
        //
        // 根因：/data/user/0 是指向 /data/data 的符号链接，权限
        // root:root 0711 —— 应用有 x（可穿过）没有 r（不可列出）。
        // 而 dsh 写盘时会 ensureDurableDirectory()：从目标目录逐级 open
        // 父目录并 fsync 到根，走到这一级必然失败。
        //
        // ★ 软链不改变字面路径 ★
        // 把 attachments / Documents 软链到 /storage/emulated/0 之后，
        // 引擎拿到的仍是 ${'$'}DSH_HOME/... 这个字面路径，往上逐级走依旧经过
        // /data/user/0。这就是「已经外置」「把图复制到外置工作区」
        // 「直接读外部路径」全是同一个报错的原因 —— 失败点在路径根，
        // 与图片放在哪里无关。
        //
        // 修法：只对**这几个祖先目录的只读 open** 重定向到应用私有目录。
        // 调用方（fsync / close）只需要一个「能打开的目录 fd」，
        // 语义等价，且不改动任何数据。写模式与其余路径一律照旧抛错。
        const ANCESTOR_DIRS = new Set([
          '/data', '/data/user', '/data/user/0', '/data/data'
        ]);
        const SAFE_DIR = '$safeDir';

        const isReadOnlyOpen = (flags) => {
          if (flags === undefined || flags === null) return true;
          if (typeof flags === 'string') return flags === 'r';
          // O_WRONLY=1, O_RDWR=2；无写位即只读
          return (flags & 3) === 0;
        };

        let fakeLogCount = 0;
        const fakeable = (p, flags) =>
          typeof p === 'string' && ANCESTOR_DIRS.has(p) && isReadOnlyOpen(flags);

        // 前 5 次打调用栈：这是**取证**用的。
        // 若日志里从没出现这一行，说明打开祖先目录的不是 fs.open 而是
        // opendir/readdir —— 那时要改的方向完全不同，不能继续在这条路上打补丁。
        const logFake = (how, p) => {
          if (fakeLogCount >= 5) return;
          fakeLogCount++;
          try {
            const st = (new Error().stack || '').split('\n').slice(1, 5)
              .map((s) => s.trim()).join(' | ');
            console.error('[dsh-launcher] ' + how + ' EACCES ' + p +
              '，已重定向到应用私有目录（' + fakeLogCount + '/5）: ' + st);
          } catch { /* 日志失败不影响主流程 */ }
        };

        const origOpenSync = fs.openSync;
        fs.openSync = function (p, flags, mode) {
          try {
            return origOpenSync.call(fs, p, flags, mode);
          } catch (err) {
            if (err && err.code === 'EACCES' && fakeable(p, flags)) {
              logFake('openSync', p);
              return origOpenSync.call(fs, SAFE_DIR, 'r');
            }
            throw err;
          }
        };

        const origOpen = fs.open;
        fs.open = function (p, flags, mode, cb) {
          let callback = cb;
          let f = flags;
          let m = mode;
          if (typeof m === 'function') { callback = m; m = undefined; }
          if (typeof f === 'function') { callback = f; f = 'r'; m = undefined; }
          return origOpen.call(fs, p, f, m, (err, fd) => {
            if (err && err.code === 'EACCES' && fakeable(p, f)) {
              logFake('open', p);
              return origOpen.call(fs, SAFE_DIR, 'r', (e2, fd2) => callback(e2, fd2));
            }
            callback(err, fd);
          });
        };

        const origPOpen = fs.promises.open;
        fs.promises.open = async function (p, flags, mode) {
          try {
            return await origPOpen.call(fs.promises, p, flags, mode);
          } catch (err) {
            if (err && err.code === 'EACCES' && fakeable(p, flags)) {
              logFake('promises.open', p);
              return await origPOpen.call(fs.promises, SAFE_DIR, 'r');
            }
            throw err;
          }
        };

        // 让 `import { link } from 'node:fs'` 这类具名导入也拿到补丁后的版本
        syncBuiltinESMExports();
    """.trimIndent() + "\n"

    return runCatching {
        patchFile.writeText(js)
        DSHRunner.onLog("🧩 已生成 fs 补丁 V2（硬链接降级 + 祖先目录 open 容错）")
        patchFile.absolutePath
    }.getOrElse { e ->
        DSHRunner.onLog("⚠️ 写入 fs 补丁失败: ${e.message}")
        null
    }
}
