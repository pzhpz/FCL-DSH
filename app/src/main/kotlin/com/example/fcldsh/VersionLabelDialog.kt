package com.example.fcldsh

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 「给它起个名字」小窗 —— 安装前给版本加一个**显示标签**。
 *
 * ## 它改的是什么、不改什么
 *
 * 改的只是界面上显示的名字（存在 [VersionLabels] 里的一张映射表）。
 * **不改**版本目录名、不改 `DSH_HOME`、不改实例元数据 —— 那些都是身份标识，
 * 动了会让会话挂载失败、数据目录错位。详见 [VersionLabels] 的类注释。
 *
 * ## 为什么默认名可以直接确认
 *
 * 默认名来自调用方：
 * - 从 registry 下载 → 版本号本身（已带自动去重后缀，如 `0.2.1-rc.4-2`）；
 * - 从本地压缩包安装 → 压缩包文件名（去掉扩展名）。
 *
 * 两种都是「用户一眼能认出来」的东西，所以不做任何强制校验：
 * 用户直接按确定也能装，想改就改。
 *
 * ## 为什么不做重名检查
 *
 * 标签只用于显示，两个版本叫同一个名字不会造成功能问题（版本键仍然唯一）。
 * 强行加后缀反而让用户莫名其妙 —— 他明明输入的就是这个名字。
 * 只有**空名字**会被忽略（等价于「不起名」，退回显示版本号）。
 *
 * @param title 小窗标题
 * @param hint 副标题，解释这次起名的对象
 * @param defaultName 预填的默认名
 * @param onDismiss 取消
 * @param onConfirm 确认，传规范化后的名字；空串表示「不起名」
 */
@Composable
fun VersionLabelDialog(
    title: String,
    hint: String,
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    // 用 defaultName 做 key：同一个窗被复用于另一次安装时，
    // 输入框必须换成新的默认名，不能把上一次的残留带过去。
    var name by remember(defaultName) { mutableStateOf(defaultName) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.width(340.dp),
            shape = RoundedCornerShape(Fcl.Radius),
            color = Fcl.DialogBg
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = title,
                    color = Fcl.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = hint,
                    color = Fcl.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= VersionLabels.MAX_LEN * 2) name = it },
                    singleLine = true,
                    placeholder = { Text("留空则显示版本号", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "只影响这里的显示名；版本目录与数据目录不会变。",
                    color = Fcl.TextTertiary,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", fontSize = 12.sp, color = Fcl.TextSecondary)
                    }
                    TextButton(onClick = {
                        // 空输入 = 不起名（清除标签），所以直接放行
                        onConfirm(VersionLabels.normalize(name))
                    }) {
                        Text("确定", fontSize = 12.sp, color = Fcl.ThemeText)
                    }
                }
            }
        }
    }
}
