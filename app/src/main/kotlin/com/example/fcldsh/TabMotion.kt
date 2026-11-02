package com.example.fcldsh

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 标签页内容的竖向滑动动画容器。
 *
 * ## 行为
 *
 * 1. **初次展开**：子面板从下方滑入到位（不是直接出现）。
 * 2. **切换标签**：旧内容向下滑走的同时，新内容从下方滑入 —— 两条同时跑，
 *    不是「先退后进」。
 * 3. **整个面板缩回**：什么都不做。横向缩回由 `PanelHost` 负责，
 *    这里再叠一层只会让画面同时往两个方向动，显得乱。
 *
 * ## 为什么不用 `AnimatedContent` / `slideInVertically`
 *
 * 它们都在 `androidx.compose.animation:animation` 里，而本项目只依赖到
 * foundation（只带 animation-core）。为了两条滑动动画引入整个 animation
 * 包不划算 —— 这与 `PanelHost` 里已经记录过的取舍一致，两处保持同一套做法。
 *
 * ## 为什么是「两条进度」而不是「每个标签一条」
 *
 * `PanelHost` 能为每块面板各持一条进度，是因为面板是封闭枚举、可以
 * `values()` 一次建全。这里的内容是泛型 [T]，组件内部无法枚举。
 *
 * 于是改成「当前 + 正在退场」两个槽位：任意时刻最多两份内容同时存在，
 * 足够表现交叉滑动。快速连点标签时，中间那些会被直接跳过 ——
 * 这与「每块各一条」的差别只在极端操作下，肉眼几乎看不出来。
 *
 * @param tab 当前选中的标签。值变化即触发切换动画。
 * @param content 标签内容。会以 [tab] 为参数调用；退场期间还会以旧的
 *   标签值再调用一次，所以内容函数必须是**可重入**的（无副作用的渲染）。
 */
@Composable
fun <T> FclTabContentHost(
    tab: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    // 当前显示的内容；初始即为传入的 tab，所以首次组合不触发「切换」
    var current by remember { mutableStateOf(tab) }

    // 正在退场的内容；null 表示没有内容在退场
    var leaving by remember { mutableStateOf<T?>(null) }

    // 入场进度：0 = 停在下方偏移处，1 = 就位
    val enter = remember { Animatable(0f) }

    // 退场进度：1 = 就位，0 = 已滑到下方
    val exit = remember { Animatable(1f) }

    // ★ 入场位移按「面板高度的比例」算，不是固定 dp ★
    //
    // 原先固定 32dp：时长翻倍到 520ms 后，32dp 分摊到半秒里几乎看不出在动，
    // 观感会从「闪现」变成「内容凭空出现」，两头都不对。
    // 距离与时长相称才看得出「滑」这个动作。
    //
    // 具体比例见文件末尾的 [PANEL_ENTER_FRACTION]。

    // ── 初次展开：从下方滑入 ────────────────────────────────────────
    // 面板被收回后组合树会卸载（PanelHost 只在 progress > 0 时保留），
    // 所以下次展开会重新走一遍这里 —— 正好就是「每次展开都从下面伸出」。
    LaunchedEffect(Unit) {
        enter.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = ENTER_MS, easing = TabEasing)
        )
    }

    // ── 切换标签：旧的向下滑走 + 新的向上滑入，同时进行 ─────────────
    LaunchedEffect(tab) {
        if (tab == current) return@LaunchedEffect

        leaving = current
        current = tab

        // 新内容先归位到下方，旧内容保持在就位状态 —— 两条都从已知起点出发，
        // 避免快速连点时动画从半截位置跳变。
        enter.snapTo(0f)
        exit.snapTo(1f)

        coroutineScope {
            launch {
                enter.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = ENTER_MS, easing = TabEasing)
                )
            }
            launch {
                exit.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = EXIT_MS, easing = TabEasing)
                )
            }
        }

        // 两条都结束后才释放退场槽位，否则旧内容会在滑动途中突然消失
        leaving = null
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 退场内容画在下层：新内容从下方滑上来盖住它，视觉上像被推走
        leaving?.let { old ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // ★ 必须滑到面板 **下方之外** 才停 ★
                        //
                        // 早先用的是 `(1f - exit) * offsetPx`（32dp），
                        // 只下沉一点点就淡出消失 —— 看起来像「卡一下然后没了」，
                        // 因为它并没有真的离开视野，是在半路凭空消失的。
                        //
                        // 改成按 **自身高度** 位移：它一定滑到可视区之外。
                        translationY = (1f - exit.value) * size.height
                        // 同时淡出：纯位移在快速切换时会显得生硬，
                        // 叠一层透明度让新内容透出来，交接更自然。
                        alpha = exit.value.coerceIn(0f, 1f)
                    }
            ) {
                content(old)
            }
        }

        // 当前内容画在上层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // enter 0 → 1，translationY 从「下方偏移」回到 0（从下方升上来）。
                    // 偏移量按自身高度取比例，保证不同高度的面板观感一致。
                    translationY = (1f - enter.value) * size.height * PANEL_ENTER_FRACTION
                }
        ) {
            content(current)
        }
    }
}

/**
 * 入场时长。
 *
 * ★ 数值是「慢一倍」之后的 ★
 *
 * 原来 260ms / 200ms 实测下来几乎是闪现 —— 原因不只是时间短，
 * 更因为退场现在按**自身高度**位移（一屏多），距离一大，同样时长下
 * 速度就快得看不清过程。翻倍到 520 / 400 之后才看得出「滑」这个动作。
 *
 * 与面板展开（440ms）的关系：内容略慢于外框是刻意的 ——
 * 外框先到位、内容随后收尾，比两者同时停更有层次。
 */
private const val ENTER_MS = 520

/**
 * 退场时长。比入场略快。
 *
 * 旧内容稍早离开视线、新内容还在往里滑，能让「切换」的重心落在新内容上；
 * 两者等长时会显得两块僵持不下。
 */
private const val EXIT_MS = 400

/**
 * 入场时的位移，按**面板自身高度**取比例。
 *
 * 0.22 ≈ 五分之一屏高：
 * · 足够看出「从下方伸上来」，不会像是内容凭空出现；
 * · 又不至于让上半屏空出一大块 —— 那是 0.5 以上才会出现的问题。
 *
 * 用比例而不是固定 dp，是因为面板在不同屏幕高度下不一样高：
 * 写死像素会让高屏上几乎看不出动、矮屏上又滑得太远。
 */
private const val PANEL_ENTER_FRACTION = 0.22f

/** 缓入缓出，与面板进出的缓动保持一致。 */
private val TabEasing = FastOutSlowInEasing
