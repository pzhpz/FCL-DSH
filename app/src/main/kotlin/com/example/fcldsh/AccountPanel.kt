package com.example.fcldsh

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.DSHRunner
import java.util.UUID

/**
 * 用户（密钥）管理面板。
 *
 * ## 布局：左选项、右列表
 *
 * ```
 * ┌───────────────┬──────────────────────────────────┐
 * │ DeepSeek 官方  │  张三          DeepSeek 官方     │
 * │ 第三方密钥     │  李四          OpenAI 兼容       │
 * └───────────────┴──────────────────────────────────┘
 * ```
 *
 * 左栏只放两个**创建入口**，右栏是已有账号列表。选中的那一条就是启动时
 * 注入 dsh 的账号，行上直接标「使用中」。
 *
 * ## 为什么左栏是两个按钮而不是一个「添加」下拉
 *
 * 官方与第三方要填的字段完全不同（官方只要名字 + key，第三方要协议 +
 * 地址 + 模型），合成一个表单会让官方用户看到一堆与自己无关的输入框。
 * 分开之后每张表单都只问必要信息。
 */
@Composable
fun AccountPanel() {
    val context = LocalContext.current

    // 首次进入时从磁盘加载（幂等）
    remember { AccountStore.ensureLoaded(context); 0 }

    // 当前打开的是哪个创建弹窗
    var showOfficial by remember { mutableStateOf(false) }
    var showThird by remember { mutableStateOf(false) }

    // 待重命名 / 待删除的账号
    var renaming by remember { mutableStateOf<AccountStore.Account?>(null) }
    var deleting by remember { mutableStateOf<AccountStore.Account?>(null) }

    val accounts = AccountStore.accounts
    val selectedId = AccountStore.selectedId

    Row(modifier = Modifier.fillMaxSize().padding(12.dp)) {

        // ── 左栏：两个创建入口（1/3）────────────────────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            FclSettingSection("新建密钥")

            FclSettingRow(
                label = "DeepSeek 官方密钥",
                description = "只需用户名与 key，地址与模型由启动器固定。",
                shape = RowShape.TOP,
                onClick = { showOfficial = true }
            ) {
                Text(text = "添加", color = Fcl.ThemeText, fontSize = 12.sp)
            }

            FclSettingRow(
                label = "第三方密钥",
                description = "OpenAI 兼容 / Anthropic / Gemini 三种协议，地址自填。",
                shape = RowShape.BOTTOM,
                onClick = { showThird = true }
            ) {
                Text(text = "添加", color = Fcl.ThemeText, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingSection("说明")
            FclSettingRow(
                label = "密钥存放位置",
                description = "保存在应用私有目录，不随整合包导出。" +
                    "启动时注入 dsh 进程，只有被选中的那个账号会生效。",
                shape = RowShape.SINGLE
            ) {
                Text(text = "只读", color = Fcl.TextTertiary, fontSize = 10.sp)
            }

            Spacer(modifier = Modifier.height(6.dp))
            FclSettingRow(
                label = "当前生效",
                description = AccountStore.describeForLog() ?: "还没有选中任何账号",
                shape = RowShape.SINGLE
            ) {
                Text(
                    text = if (selectedId == null) "无" else "已选",
                    color = if (selectedId == null) Fcl.TextTertiary else Fcl.Success,
                    fontSize = 10.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        Spacer(modifier = Modifier.width(10.dp))

        // ── 右栏：账号列表（2/3）────────────────────────────────────
        Column(
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            FclSettingSection("用户（${accounts.size}）")

            if (accounts.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Fcl.Radius),
                    color = Fcl.Card
                ) {
                    Text(
                        text = "还没有用户。用左侧任意一个入口添加，" +
                            "添加成功后会出现在这里，点一下即选中为启动时使用的账号。",
                        color = Fcl.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(Fcl.RowPadding)
                    )
                }
            } else {
                accounts.forEachIndexed { i, a ->
                    val active = a.id == selectedId
                    FclSettingRow(
                        label = a.name,
                        description = "${a.subtitle} · 密钥 ${a.maskedKey}",
                        shape = RowShape.SINGLE,
                        // 点整行即选中
                        onClick = { AccountStore.select(context, a.id) }
                    ) {
                        Text(
                            text = if (active) "使用中" else "使用",
                            color = if (active) Fcl.Success else Fcl.ThemeText,
                            fontSize = 10.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        AccountAction(text = "重命名") { renaming = a }
                        Spacer(modifier = Modifier.width(2.dp))
                        AccountAction(
                            text = "删除",
                            danger = true
                        ) { deleting = a }
                    }
                    if (i < accounts.size - 1) Spacer(modifier = Modifier.height(4.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }

    // ── 创建弹窗 ───────────────────────────────────────────────────
    if (showOfficial) {
        OfficialDialog(
            onDismiss = { showOfficial = false },
            onCreated = { showOfficial = false }
        )
    }

    if (showThird) {
        ThirdPartyDialog(
            onDismiss = { showThird = false },
            onCreated = { showThird = false }
        )
    }

    // ── 重命名弹窗 ─────────────────────────────────────────────────
    renaming?.let { target ->
        InputNameDialog(
            title = "重命名用户",
            initial = target.name,
            hint = "这个名字只在启动器里显示，不会传给 dsh。",
            onDismiss = { renaming = null },
            onConfirm = { newName ->
                AccountStore.rename(context, target.id, newName)
                renaming = null
            }
        )
    }

    // ── 删除确认 ───────────────────────────────────────────────────
    deleting?.let { target ->
        ConfirmDialog(
            title = "删除用户「${target.name}」",
            detail = "只会删除启动器里保存的这份密钥记录，不影响 dsh 里的任何数据。" +
                "如果它正在被使用，删除后会自动切到列表里的下一个账号。",
            onDismiss = { deleting = null },
            onConfirm = {
                AccountStore.remove(context, target.id)
                deleting = null
            }
        )
    }
}

// ----------------------------------------------------------------------
// 创建弹窗
// ----------------------------------------------------------------------

/**
 * DeepSeek 官方密钥：只要用户名 + key。
 *
 * 地址与模型由启动器固定（`https://api.deepseek.com` / `deepseek-chat`），
 * 不让用户填 —— 官方就用官方端点，填错了只会让检测失败却不知道为什么。
 */
@Composable
private fun OfficialDialog(onDismiss: () -> Unit, onCreated: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    // 两个都必填才能创建
    val canSubmit = name.isNotBlank() && key.isNotBlank() && !busy

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !busy)
    ) {
        Surface(
            // 比默认小弹窗宽一些：输入框在里面更容易看清完整内容
            modifier = Modifier.width(340.dp),
            shape = RoundedCornerShape(Fcl.Radius),
            color = Fcl.Card
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "DeepSeek 官方密钥",
                    color = Fcl.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "地址固定为 $AccountStore.OFFICIAL_BASE，" +
                        "模型 $AccountStore.OFFICIAL_MODEL。",
                    color = Fcl.TextTertiary,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )

                // 「获取 key」：跳系统浏览器到 DeepSeek 开放平台的用量页。
                //
                // 放在这一行而不是塞进下面的输入框：填 key 之前先要拿到 key，
                // 让用户自己去搜「deepseek api key 在哪」是多余的。
                // 用系统浏览器而不是内置 WebView —— 那边要登录，
                // 内置窗口拿不到用户已登录的 Cookie，反而要多登一次。
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "还没有密钥？",
                        color = Fcl.TextTertiary,
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "获取 key",
                        color = Fcl.ThemeText,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Fcl.Radius))
                            .fclClickable {
                                openUrl(context, AccountStore.OFFICIAL_KEY_URL)
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                FormField(
                    label = "用户名",
                    value = name,
                    placeholder = "给这个用户起个名字",
                    onChange = { name = it }
                )

                Spacer(modifier = Modifier.height(10.dp))
                FormField(
                    label = "API Key",
                    value = key,
                    placeholder = "sk-…",
                    secret = true,
                    onChange = { key = it }
                )

                if (error.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = error,
                        color = Fcl.Danger,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(14.dp).height(14.dp),
                            strokeWidth = 2.dp,
                            color = Fcl.Theme
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "正在检测连接…",
                            color = Fcl.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !busy) {
                        Text("取消", color = Fcl.TextSecondary, fontSize = 12.sp)
                    }
                    TextButton(
                        enabled = canSubmit,
                        onClick = {
                            error = ""
                            busy = true
                            // 网络检测必须离开主线程
                            val draft = AccountStore.Account(
                                id = UUID.randomUUID().toString(),
                                name = name.trim(),
                                kind = AccountStore.AccountKind.OFFICIAL,
                                protocol = AccountStore.Protocol.OPENAI,
                                baseUrl = AccountStore.OFFICIAL_BASE,
                                apiKey = key.trim(),
                                model = AccountStore.OFFICIAL_MODEL
                            )
                            Thread {
                                val result = AccountStore.check(draft)
                                Handler(Looper.getMainLooper()).post {
                                    busy = false
                                    when (result) {
                                        is AccountStore.CheckResult.Ok -> {
                                            AccountStore.add(context, draft)
                                            DSHRunner.onLog(
                                                "✅ 已添加用户「${draft.name}」（DeepSeek 官方）"
                                            )
                                            onCreated()
                                        }
                                        is AccountStore.CheckResult.Fail -> {
                                            error = "连接失败: ${result.reason}"
                                        }
                                    }
                                }
                            }.start()
                        }
                    ) {
                        Text(
                            text = "创建",
                            color = if (canSubmit) Fcl.ThemeText else Fcl.TextTertiary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * 第三方密钥：名称 + 协议 + 地址 + 模型 + 密钥。
 *
 * 协议是**三选一**，不是自由文本 —— 三种协议的认证头与请求体结构不同，
 * 用户手输一个不存在的协议名只会让检测阶段报一句看不懂的错。
 */
@Composable
private fun ThirdPartyDialog(onDismiss: () -> Unit, onCreated: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf(AccountStore.Protocol.OPENAI) }
    var base by remember { mutableStateOf(AccountStore.Protocol.OPENAI.defaultBase) }
    var model by remember { mutableStateOf(AccountStore.Protocol.OPENAI.defaultModel) }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    // 切协议时把地址与模型换成该协议的默认值 —— 但要**先问一下**用户改没改过：
    // 没改过就跟着换（省事），改过就保留（不覆盖用户输入）。
    var touched by remember { mutableStateOf(false) }

    val canSubmit = name.isNotBlank() && base.isNotBlank() &&
        key.isNotBlank() && model.isNotBlank() && !busy

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !busy)
    ) {
        Surface(
            modifier = Modifier.width(360.dp),
            shape = RoundedCornerShape(Fcl.Radius),
            color = Fcl.Card
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    // 字段多，限高 + 内部滚动，避免小屏上按钮被挤到屏幕外
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "第三方密钥",
                    color = Fcl.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "创建前会发一次最小请求验证密钥与地址是否可用。",
                    color = Fcl.TextTertiary,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(12.dp))
                FormField(
                    label = "名称",
                    value = name,
                    placeholder = "给这个用户起个名字",
                    onChange = { name = it }
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text(text = "协议", color = Fcl.TextPrimary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountStore.Protocol.all.forEach { p ->
                        val on = p == protocol
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(Fcl.Radius))
                                .fclClickable {
                                    protocol = p
                                    if (!touched) {
                                        base = p.defaultBase
                                        model = p.defaultModel
                                    }
                                },
                            shape = RoundedCornerShape(Fcl.Radius),
                            color = if (on) Fcl.Theme else Fcl.Track
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = p.label,
                                    color = if (on) Color.White else Fcl.TextPrimary,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                FormField(
                    label = "API 地址",
                    value = base,
                    placeholder = protocol.defaultBase,
                    onChange = {
                        base = it
                        touched = true
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))
                FormField(
                    label = "模型",
                    value = model,
                    placeholder = protocol.defaultModel,
                    onChange = {
                        model = it
                        touched = true
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))
                FormField(
                    label = "API Key",
                    value = key,
                    placeholder = "sk-…",
                    secret = true,
                    onChange = { key = it }
                )

                if (error.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = error,
                        color = Fcl.Danger,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(14.dp).height(14.dp),
                            strokeWidth = 2.dp,
                            color = Fcl.Theme
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "正在检测连接…",
                            color = Fcl.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !busy) {
                        Text("取消", color = Fcl.TextSecondary, fontSize = 12.sp)
                    }
                    TextButton(
                        enabled = canSubmit,
                        onClick = {
                            error = ""
                            busy = true
                            val draft = AccountStore.Account(
                                id = UUID.randomUUID().toString(),
                                name = name.trim(),
                                kind = AccountStore.AccountKind.THIRD_PARTY,
                                protocol = protocol,
                                baseUrl = base.trim(),
                                apiKey = key.trim(),
                                model = model.trim()
                            )
                            Thread {
                                val result = AccountStore.check(draft)
                                Handler(Looper.getMainLooper()).post {
                                    busy = false
                                    when (result) {
                                        is AccountStore.CheckResult.Ok -> {
                                            AccountStore.add(context, draft)
                                            DSHRunner.onLog(
                                                "✅ 已添加用户「${draft.name}」" +
                                                    "（${protocol.label}）"
                                            )
                                            onCreated()
                                        }
                                        is AccountStore.CheckResult.Fail -> {
                                            error = "连接失败: ${result.reason}"
                                        }
                                    }
                                }
                            }.start()
                        }
                    ) {
                        Text(
                            text = "创建",
                            color = if (canSubmit) Fcl.ThemeText else Fcl.TextTertiary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 小组件
// ----------------------------------------------------------------------

/** 带标题的输入框，下方一条底线（与下载中心的输入框同一种画法）。 */
@Composable
private fun FormField(
    label: String,
    value: String,
    placeholder: String,
    secret: Boolean = false,
    onChange: (String) -> Unit
) {
    Column {
        Text(text = label, color = Fcl.TextPrimary, fontSize = 12.sp)
        Spacer(modifier = Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                color = Fcl.TextPrimary,
                fontSize = 12.sp,
                // 密钥默认打码：截图、投屏、旁人瞄一眼都不至于直接泄露
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            ),
            cursorBrush = SolidColor(Fcl.Theme),
            visualTransformation = if (secret) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Text
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = Fcl.TextTertiary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            }
        )
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(color = Fcl.Line)
    }
}

/** 行内小动作。禁用时不挂手势（真点不动，不是点了没反应）。 */
@Composable
private fun AccountAction(
    text: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val base = Modifier
        .clip(RoundedCornerShape(Fcl.Radius))
        .padding(horizontal = 6.dp, vertical = 4.dp)
    Text(
        text = text,
        color = when {
            !enabled -> Fcl.TextTertiary
            danger -> Fcl.Danger
            else -> Fcl.ThemeText
        },
        fontSize = 12.sp,
        modifier = if (enabled) base.fclClickable { onClick() } else base
    )
}

/** 单行改名弹窗。 */
@Composable
private fun InputNameDialog(
    title: String,
    initial: String,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = true)
    ) {
        Surface(
            modifier = Modifier.width(320.dp),
            shape = RoundedCornerShape(Fcl.Radius),
            color = Fcl.Card
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    color = Fcl.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = hint,
                    color = Fcl.TextTertiary,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                FormField(
                    label = "名称",
                    value = value,
                    placeholder = "新名字",
                    onChange = { value = it }
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = Fcl.TextSecondary, fontSize = 12.sp)
                    }
                    TextButton(
                        enabled = value.isNotBlank(),
                        onClick = { onConfirm(value) }
                    ) {
                        Text(
                            text = "确定",
                            color = if (value.isNotBlank()) Fcl.ThemeText else Fcl.TextTertiary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}
