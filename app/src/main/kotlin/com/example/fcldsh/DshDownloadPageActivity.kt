package com.example.fcldsh

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「浏览器下载页」—— 每个 dsh 版本行右侧的「浏览器」按钮跳到这里。
 *
 * ## 这个页面负责什么
 *
 * 只做一件事：把指定版本的引擎包下载到本地。所以它
 * - **不检查是否已下载/已安装**（同一个版本想存几份就存几份）；
 * - **不负责安装**，下完就结束，装不装、什么时候装都由用户自己决定；
 * - 与下载中心里的「下载」按钮完全independent：那条路走 npm 安装，这条只是取文件。
 *
 * ## 重名怎么处理
 *
 * 文件名输入框就是「自定义重命名」的入口。点下载时若目标文件已存在，
 * 自动改成 `xxx-1.tgz`、`xxx-2.tgz`… 并在下方提示，
 * 用户也可以直接改输入框再来一次。
 *
 * ## 为什么不像浏览器那样套一个 WebView
 *
 * 真实页面只有一条 tarball 链接，放进 WebView 反而要点两次、还拦不住下载。
 * 这里保留浏览器页面的形态（地址栏 + 内容区），下载走原生线程，
 * 进度和文件名都能直接控制。
 */
class DshDownloadPageActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_VERSION = "extra_version"

        /** 从版本行跳过来。 */
        fun open(context: Context, version: String) {
            context.startActivity(
                Intent(context, DshDownloadPageActivity::class.java)
                    .putExtra(EXTRA_VERSION, version)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val version = intent.getStringExtra(EXTRA_VERSION).orEmpty()

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                FclTheme {
                    BrowserDownloadPage(version = version, onClose = { finish() })
                }
            }
        }
        setContentView(view)
    }
}

/** registry 上的 tarball 地址。作用域包的包体名不带 scope。 */
private fun tarballUrl(version: String) =
    "https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-$version.tgz"

/** 下载目录：应用自己的外部下载目录，不需要任何存储权限。 */
private fun downloadDir(context: Context): File {
    val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: File(context.filesDir, "Download")
    return base.apply { mkdirs() }
}

/** 若 [name] 已被占用，就在主名后加 `-1`、`-2`… 直到不冲突。 */
private fun uniqueFile(dir: File, name: String): File {
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var candidate = File(dir, name)
    var i = 1
    while (candidate.exists()) {
        candidate = File(dir, "$base-$i$ext")
        i++
    }
    return candidate
}

@Composable
private fun BrowserDownloadPage(version: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val dir = remember { downloadDir(context) }

    val defaultName = remember(version) { "dsh-$version.tgz" }

    // 文件名输入框：既是「自定义重命名」，也是重名时自动改写后的结果展示位
    var fileName by remember(version) { mutableStateOf(defaultName) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var hasTotal by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val canDownload = version.isNotBlank() && !running

    Surface(modifier = Modifier.fillMaxSize(), color = Fcl.Bg) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

            // ── 地址栏（浏览器形态）───────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Fcl.Radius),
                color = Fcl.Card
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🔒",
                        fontSize = 11.sp,
                        color = Fcl.TextTertiary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = tarballUrl(version.ifBlank { "<version>" }),
                        color = Fcl.TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Fcl.Track,
                        modifier = Modifier.fclClickable { if (!running) onClose() }
                    ) {
                        Text(
                            text = "关闭",
                            color = Fcl.TextPrimary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── 内容区 ────────────────────────────────────────────────
            FclCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "@deepseek-ai/dsh",
                    color = Fcl.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "版本 $version · 该页只用于下载，不检查是否已下载或已安装",
                    color = Fcl.TextSecondary,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "文件名（可自定义重命名）",
                    color = Fcl.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                BasicTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    singleLine = true,
                    enabled = !running,
                    textStyle = TextStyle(color = Fcl.TextPrimary, fontSize = 12.sp),
                    cursorBrush = SolidColor(Fcl.Theme),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (fileName.isEmpty()) {
                                Text(
                                    text = defaultName,
                                    color = Fcl.TextTertiary,
                                    fontSize = 12.sp
                                )
                            }
                            inner()
                        }
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = Fcl.Line)

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "保存位置：${dir.absolutePath}",
                    color = Fcl.TextTertiary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // 下载按钮：页面里唯一的功能入口
                Surface(
                    modifier = Modifier.fillMaxWidth()
                        .then(
                            if (canDownload) Modifier.fclClickable {
                                // 先按输入框里的名字算目标；重名则自动改名并回填输入框，
                                // 用户看到新名字后仍可再次修改。
                                var name = fileName.trim().ifBlank { defaultName }
                                name = name.replace('/', '_').replace('\\', '_')
                                if (!name.endsWith(".tgz") && !name.endsWith(".tar.gz")) {
                                    name = "$name.tgz"
                                }
                                val target = uniqueFile(dir, name)
                                if (target.name != name) {
                                    note = "已存在同名文件，自动重命名为 ${target.name}"
                                    fileName = target.name
                                } else {
                                    note = ""
                                }

                                running = true
                                progress = 0f
                                hasTotal = false
                                status = "正在下载 ${target.name} …"
                                downloadAsync(
                                    url = tarballUrl(version),
                                    target = target,
                                    main = main,
                                    onProgress = { p, total ->
                                        progress = p
                                        hasTotal = total
                                    },
                                    onFinish = { error ->
                                        running = false
                                        if (error == null) {
                                            progress = 1f
                                            status = "✅ 已保存到 ${target.absolutePath}"
                                            AppState.launcher.log(
                                                "📥 浏览器下载完成：${target.name}"
                                            )
                                        } else {
                                            status = "❌ 下载失败：$error"
                                            AppState.launcher.log(
                                                "❌ 浏览器下载失败：$error"
                                            )
                                        }
                                    }
                                )
                            } else Modifier
                        ),
                    shape = RoundedCornerShape(Fcl.Radius),
                    color = if (canDownload) Fcl.Theme else Fcl.Track
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (running) "下载中…" else "下载",
                            color = if (canDownload) Color.White else Fcl.TextTertiary,
                            fontSize = 13.sp
                        )
                    }
                }

                if (running) {
                    Spacer(modifier = Modifier.height(10.dp))
                    if (hasTotal) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = Fcl.Theme,
                            trackColor = Fcl.Track
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Fcl.Theme,
                            trackColor = Fcl.Track
                        )
                    }
                }

                if (note.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = note,
                        color = Fcl.Warning,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }

                if (status.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = status,
                        color = when {
                            status.startsWith("❌") -> Fcl.Danger
                            status.startsWith("✅") -> Fcl.Success
                            else -> Fcl.TextSecondary
                        },
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "下载完成后可回到下载中心的整合包页签用「导入本地」安装，" +
                    "或把文件传到别处使用。",
                color = Fcl.TextTertiary,
                fontSize = 10.sp,
                lineHeight = 15.sp
            )
        }
    }
}

/**
 * 后台下载到 [target]，进度与结果都投递回主线程。
 *
 * 不用协程：这里只有一条简单的字节流拷贝，线程 + Handler 足够，
 * 也和其他模块（EngineInstaller / EngineExporter）的写法保持一致。
 */
private fun downloadAsync(
    url: String,
    target: File,
    main: Handler,
    onProgress: (Float, Boolean) -> Unit,
    onFinish: (String?) -> Unit
) {
    Thread {
        var conn: HttpURLConnection? = null
        try {
            // 先收进一个非空局部变量：`conn` 是可空的 var，Kotlin 不会替它做智能转换，
            // 直接写 conn.responseCode 会编译不过。
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "fcl-dsh/${BuildConfig.VERSION_NAME} (Android)")
            }
            conn = c

            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")

            // getContentLengthLong 是 API 24 才有的；minSdk 是 23，低版本退回 Int 版本。
            val total = if (android.os.Build.VERSION.SDK_INT >= 24) c.contentLengthLong
            else c.contentLength.toLong()
            var read = 0L

            c.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val p = (read.toFloat() / total).coerceIn(0f, 1f)
                            main.post { onProgress(p, true) }
                        }
                    }
                    out.flush()
                }
            }

            main.post { onProgress(1f, total > 0) }
            main.post { onFinish(null) }
        } catch (e: Exception) {
            // 半截文件不留下：不然下次会被当成本地包，装的时候才发现坏了
            runCatching { target.delete() }
            val msg = e.message ?: e.javaClass.simpleName
            main.post { onFinish(msg) }
        } finally {
            conn?.disconnect()
        }
    }.start()
}
