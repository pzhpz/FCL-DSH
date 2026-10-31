package com.example.fcldsh

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * FCL 视觉体系（从 FoldCraftLauncher 的 values/colors.xml + drawable 提取）。
 *
 * ## 为什么色值是可变的
 *
 * FCL 在设置页里允许改主题色、主题色透明度、明暗模式。为了让这些设置立刻生效，
 * 色值不能是 `val` 常量，而是**读一次 Compose 状态的 getter**：
 * 任何在组合期间读过 `Fcl.Card` 的地方都会自动订阅，改主题即重组。
 *
 * ## 和 FCL 的对应关系
 *
 * | FCL                        | 这里                       |
 * |----------------------------|----------------------------|
 * | `default_theme_color`      | [Theme] 默认 `#7797CF`     |
 * | `default_theme_color_dark` | [ThemeDark]                |
 * | `ui_bg_color` `#40F4F4F4`  | [MenuBg] 左侧栏 / 浮层      |
 * | `right_menu_color` `#80F4F4F4` | [RightMenuBg] 右侧菜单  |
 * | `dialog_background` `#F4F4F4` | [DialogBg]               |
 * | `bg_container_white` 8dp   | [Card] + [Radius]          |
 * | `primary_text` `#0E0E0E`   | [TextPrimary]              |
 */
object Fcl {

    /** 明暗模式，对应 FCL 的 `settings_launcher_theme_mode` 三项。 */
    enum class Mode(val label: String) {
        FOLLOW("跟随系统"),
        LIGHT("浅色"),
        DARK("深色")
    }

    /** FCL 的默认主题色；「重置」按钮回到这个值。 */
    val DefaultTheme = Color(0xFF7797CF)

    // ── 可变的主题状态 ──────────────────────────────────────────────
    private var themeState by mutableStateOf(DefaultTheme)
    private var modeState by mutableStateOf(Mode.FOLLOW)
    /** 面板不透明度 0..1，对应 FCL 的 `SEEKBAR_COLOR_ALPHA`。 */
    private var panelAlphaState by mutableStateOf(0.5f)
    /**
     * 系统是否处于深色模式（FOLLOW 时用）。
     *
     * 名字带 `State` 后缀不是为了好看：Kotlin 会给 `var systemDark` 生成一个
     * `setSystemDark(Z)V` 的私有 setter，和下面手写的 `setSystemDark()` 撞 JVM 签名，
     * 编译期直接报 Platform declaration clash。
     */
    private var systemDarkState by mutableStateOf(false)

    val themeColor: Color get() = themeState
    val mode: Mode get() = modeState
    val panelAlpha: Float get() = panelAlphaState
    val isDark: Boolean
        get() = when (modeState) {
            Mode.DARK -> true
            Mode.LIGHT -> false
            Mode.FOLLOW -> systemDarkState
        }

    fun setThemeColor(c: Color) { themeState = c }
    fun setMode(m: Mode) { modeState = m }
    fun setPanelAlpha(a: Float) { panelAlphaState = a.coerceIn(0f, 1f) }
    fun setSystemDark(dark: Boolean) { systemDarkState = dark }

    /** 重新从磁盘读一遍用户设置（Activity 启动时调）。 */
    fun restore(theme: Color, mode: Mode, alpha: Float, systemDarkNow: Boolean) {
        themeState = theme
        modeState = mode
        panelAlphaState = alpha.coerceIn(0f, 1f)
        systemDarkState = systemDarkNow
    }

    // ── 由主题色派生的三个色 ────────────────────────────────────────
    /** 主题色本身：启动按钮、选中态、强调元素。 */
    val Theme: Color get() = themeColor

    /** 按下态 / 渐变的深端，对应 `default_theme_color_dark`。 */
    val ThemeDark: Color get() = shade(themeColor, 0.76f)

    /** 浅底上的强调文字：主题色压暗，保证对比度。 */
    val ThemeText: Color get() = if (isDark) lighten(themeColor, 1.35f)
    else shade(themeColor, 0.60f)

    private fun shade(c: Color, f: Float) =
        Color((c.red * f).coerceIn(0f, 1f), (c.green * f).coerceIn(0f, 1f), (c.blue * f).coerceIn(0f, 1f), 1f)

    private fun lighten(c: Color, f: Float) =
        Color((c.red * f).coerceIn(0f, 1f), (c.green * f).coerceIn(0f, 1f), (c.blue * f).coerceIn(0f, 1f), 1f)

    // ── 面板背景 ────────────────────────────────────────────────────
    /** 对应 `ui_bg_color` = #40F4F4F4（25% 白）。透明度随滑块变化。 */
    val MenuBg: Color
        get() = if (isDark) Color(0f, 0f, 0f, 0.18f + 0.45f * panelAlpha)
        else Color(1f, 1f, 1f, 0.15f + 0.55f * panelAlpha)

    /** 对应 `right_menu_color` = #80F4F4F4（50% 白）。比左侧栏更实一些。 */
    val RightMenuBg: Color
        get() = if (isDark) Color(0f, 0f, 0f, 0.30f + 0.50f * panelAlpha)
        else Color(1f, 1f, 1f, 0.35f + 0.50f * panelAlpha)

    /**
     * 侧栏展开的那几个大面板（下载 / 设置 / 日志）的底色。
     *
     * 与 [MenuBg]、[RightMenuBg] 共用同一个 [panelAlpha]，所以设置里的
     * 「面板不透明度」滑块一拉，左栏、右栏、所有面板会一起变透 ——
     * 而不是只有左右侧栏能动、面板仍是死白一块。
     *
     * 取 [RightMenuBg] 那一档（比左栏略实）：面板里全是文字和列表，
     * 透明度太低会直接压在背景图上，读起来很吃力。
     */
    val PanelBg: Color get() = RightMenuBg

    /** 对应 `dialog_background` = #F4F4F4。 */
    val DialogBg: Color get() = if (isDark) Color(0xFF25232A) else Color(0xFFF4F4F4)

    /** 对应 `bg_container_white`：所有列表行 / 卡片的底色。 */
    val Card: Color get() = if (isDark) Color(0xFF2A2830) else Color(0xFFFFFFFF)

    /** 页面底色，比卡片略暗一档，用来区分「页面」和「卡片」。 */
    val Bg: Color get() = if (isDark) Color(0xFF17161A) else Color(0xFFF7F9FC)

    val BgTop: Color get() = if (isDark) Color(0xFF14131A) else Color(0xFFE8EFF9)
    val BgBottom: Color get() = if (isDark) Color(0xFF1E1D24) else Color(0xFFF7F9FC)

    /** 弹窗遮罩下的页面底（对话框内容区）。 */
    val Sheet: Color get() = DialogBg

    // ── 线 / 分隔 ───────────────────────────────────────────────────
    val Line: Color get() = if (isDark) Color(0xFF3A3742) else Color(0xFFE1E7F0)
    val Divider: Color get() = if (isDark) Color(0xFF34313C) else Color(0xFFEDF1F6)
    val Track: Color get() = if (isDark) Color(0xFF3A3742) else Color(0xFFDCE3EE)

    // ── 文字 ────────────────────────────────────────────────────────
    val TextPrimary: Color get() = if (isDark) Color(0xFFEDE7F6) else Color(0xFF0E0E0E)
    val TextSecondary: Color get() = if (isDark) Color(0xFFA9A4B5) else Color(0xFF5C6472)
    val TextTertiary: Color get() = if (isDark) Color(0xFF7A7589) else Color(0xFF7C8494)

    /** 图标默认色，对应 FCL 图标的 darker_gray 一档。 */
    val Icon: Color get() = if (isDark) Color(0xFFC8C3D4) else Color(0xFF4A505C)

    // ── 语义色 ──────────────────────────────────────────────────────
    val Danger: Color get() = if (isDark) Color(0xFFEF9A9A) else Color(0xFFC0453F)
    val Success: Color get() = if (isDark) Color(0xFF7BD69A) else Color(0xFF35875A)
    val Warning: Color get() = if (isDark) Color(0xFFE8B86A) else Color(0xFFB87818)
    val Idle: Color get() = if (isDark) Color(0xFF8B93A1) else Color(0xFF8B93A1)

    // ── 几何 ────────────────────────────────────────────────────────
    /** FCL 全站统一的 8dp 圆角（bg_container_white / dialog_background / bg_item_rounded）。 */
    val Radius = 8.dp

    /** 左侧图标栏宽度。FCL 是 wrap_content（图标 24 + 左右 5 内边距）。 */
    val RailWidth = 30.dp

    /** 图标栏与屏幕边缘的距离。 */
    val RailInset = 6.dp

    /** 设置行内边距，对应 item_launcher_setting_* 的 paddingStart/End 12dp。 */
    val RowPadding = 10.dp

    /**
     * 设置行最小高度。
     *
     * 原先是照 FCL 的 48dp 抄的，但那套尺寸是为「一行就一个开关」的稀疏列表
     * 设计的；本项目很多行带两行描述，48dp 起跳会让整页显得松松垮垮。
     * 降到 36dp 后行高由内容自然撑开，列表密度明显提升。
     */
    val RowMinHeight = 36.dp

    /** 分组之间的空隙。原来是 8dp，配合收紧后的行高改成 6dp。 */
    val SectionGap = 6.dp
}

/**
 * 设置行的圆角变体，对应 FCL 的 `bg_item_rounded{,_top,_middle,_bottom}`。
 *
 * FCL 把同组相邻行连成一整块：组首只圆上面两角、组尾只圆下面两角、
 * 中间行不圆，组与组之间空出 8dp 形成分段。
 */
enum class RowShape {
    SINGLE, TOP, MIDDLE, BOTTOM;

    fun shape(radius: Dp = Fcl.Radius): Shape = when (this) {
        SINGLE -> RoundedCornerShape(radius)
        TOP -> RoundedCornerShape(topStart = radius, topEnd = radius)
        MIDDLE -> RoundedCornerShape(0.dp)
        BOTTOM -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
    }

    companion object {
        /** 由「组内序号 + 组内总数」推出该用哪个变体。 */
        fun of(index: Int, count: Int): RowShape = when {
            count <= 1 -> SINGLE
            index == 0 -> TOP
            index == count - 1 -> BOTTOM
            else -> MIDDLE
        }
    }
}

/**
 * 全局 Material 主题：把主题色灌进 Material3 的 colorScheme。
 *
 * 项目里用了 `TabRow` / `TextButton` / `AlertDialog` / 进度条等 Material 组件，
 * 它们从 `MaterialTheme.colorScheme` 取色；灌成 FCL 主题色以后不用逐个改参数就自动对味。
 */
@Composable
fun FclTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Fcl.Theme,
            onPrimary = Color.White,
            primaryContainer = Fcl.Theme.copy(alpha = 0.18f),
            onPrimaryContainer = Fcl.ThemeText,
            secondary = Fcl.ThemeDark,
            onSecondary = Color.White,
            background = Fcl.Bg,
            onBackground = Fcl.TextPrimary,
            surface = Fcl.Card,
            onSurface = Fcl.TextPrimary,
            surfaceVariant = Fcl.Track,
            onSurfaceVariant = Fcl.TextSecondary,
            outline = Fcl.Line,
            error = Fcl.Danger
        ),
        content = content
    )
}

/**
 * 屏幕壁纸：按明暗模式选一张背景图铺满。
 *
 * 明暗切换时换图（`background_light` / `background_dark`），
 * 用 `ContentScale.Crop` 填满并裁掉多余部分，不用 [Brush] 缩放——
 * 拉伸变形比裁切难看得多。
 *
 * 这两张图不随主题色变化：它们是「壁纸」，FCL 里也是用户自己挑一张不动。
 * 主题色只影响按钮/选中态等强调元素。
 */
@Composable
fun Modifier.fclBackground(): Modifier {
    val img = if (Fcl.isDark) R.drawable.background_dark else R.drawable.background_light
    return this.paint(
        painter = painterResource(id = img),
        contentScale = ContentScale.Crop
    )
}

/**
 * FCL 的按下反馈：整体轻微缩小（对应 FCL 的 `stateListAnimator="@xml/anim_scale"`）。
 *
 * FCL 的列表项、启动按钮按下时都会缩到 0.96 左右再弹回，是所有可点元素共有的手感。
 * 水波纹置 null：FCL 的 `FCLButton` 一律靠缩放动画给反馈，没有 Material 涟漪。
 */
@Composable
fun Modifier.fclClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "fcl-press-scale"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/** FCL 白色卡片容器（对应 `bg_container_white` + 8dp 圆角）。 */
@Composable
fun FclCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = Fcl.RowPadding,
    shape: Shape = RoundedCornerShape(Fcl.Radius),
    content: @Composable ColumnScope.() -> Unit
) {
    val base = if (onClick != null) modifier.fclClickable { onClick() } else modifier
    Surface(modifier = base, shape = shape, color = Fcl.Card) {
        Column(modifier = Modifier.padding(padding), content = content)
    }
}

/** 半透明浮层容器（对应 `bg_game_menu` / `bg_right_menu`）。 */
@Composable
fun FclGlassPanel(
    modifier: Modifier = Modifier,
    color: Color = Fcl.MenuBg,
    shape: Shape = RoundedCornerShape(Fcl.Radius),
    content: @Composable () -> Unit
) {
    Surface(modifier = modifier, shape = shape, color = color) {
        content()
    }
}

/**
 * FCL 设置页的一行（对应 `item_launcher_setting_*` 系列）。
 *
 * 结构照搬那边的 XML：外层是圆角卡片，内层第一排是「标题 + 右对齐控件」（minHeight 48dp），
 * 第二排是 12sp 的作用描述。同组相邻行通过 [shape] 连成一整块。
 */
@Composable
fun FclSettingRow(
    label: String,
    description: String? = null,
    shape: RowShape = RowShape.SINGLE,
    onClick: (() -> Unit)? = null,
    /**
     * 描述最多显示几行；null = 不限（保持原行为）。
     *
     * 列表类页面（插件列表、下载列表）必须限制：市场里的插件描述动辄
     * 四五行长文本，不截断的话一屏只搁得下两三个条目。截断后高度可预测，
     * 一屏能看到多少条是稳定的。
     */
    descMaxLines: Int? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val base = if (onClick != null) Modifier.fclClickable { onClick() } else Modifier
    Surface(
        modifier = base.fillMaxWidth(),
        shape = shape.shape(),
        color = Fcl.Card
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Fcl.RowPadding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Fcl.RowMinHeight)
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = Fcl.TextPrimary,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing
                )
            }
            if (description != null) {
                Text(
                    text = description,
                    color = Fcl.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    // null 表示不限行数 —— Text 的 maxLines 参数本身是 Int，
                    // 传 Int.MAX_VALUE 才是「不限」，不能传 null。
                    maxLines = descMaxLines ?: Int.MAX_VALUE,
                    overflow = if (descMaxLines != null) {
                        TextOverflow.Ellipsis
                    } else {
                        TextOverflow.Clip
                    },
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
    }
}

/**
 * 诊断类设置行：右侧一个「执行」按钮。
 *
 * 对应 FCL `item_launcher_setting_button.xml` 里 `@id/button1` 那种行内按钮，
 * 只是这里把按钮固定成一种样式：主题色底 + 白字，按下缩放。
 *
 * 抽成组件是因为设置面板里连着三行诊断项，若每行手写一遍
 * `Surface { Text }` 既啰嗦又容易把三行的圆角/边距写歪。
 */
@Composable
fun FclDiagnoseRow(
    label: String,
    desc: String,
    shape: RowShape = RowShape.SINGLE,
    buttonText: String = "执行",
    onRun: () -> Unit
) {
    FclSettingRow(
        label = label,
        description = desc,
        shape = shape
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = Fcl.Theme,
            modifier = Modifier.fclClickable { onRun() }
        ) {
            Text(
                text = buttonText,
                color = Color.White,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

/** 设置页的分组标题（FCL 里没有显式标题，靠组间 8dp 空隙分段；这里用于「权限」「诊断」等分块）。 */
@Composable
fun FclSettingSection(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        color = Fcl.TextSecondary,
        fontSize = 12.sp,
        modifier = modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp)
    )
}
