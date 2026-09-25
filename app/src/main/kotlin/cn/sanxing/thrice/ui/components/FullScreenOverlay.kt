package cn.sanxing.thrice.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.sanxing.thrice.ui.theme.LocalAppBackground

/**
 * 全屏覆盖页的容器（笔记编辑页、文件夹覆盖层等叠在另一页面之上的界面）。
 *
 * 为什么不能只写 `Scaffold(containerColor = MaterialTheme.colorScheme.surface)`：
 * 壁纸模式下 `colorScheme.surface` 会被 [cn.sanxing.thrice.ui.theme.SanxingTheme]
 * 替换成**半透明**色（跟随「主体界面浓度」设置），于是被覆盖的下层页面会透过纸面显形，
 * 形成文字重影（浓度越低越明显）。若改用不透明色，则壁纸在该页被完全遮住，
 * 与其它页面观感不一致。
 *
 * 本容器先在自身层内重绘一遍应用背景（壁纸 / 几何线条），再让内容以半透明纸面叠加其上：
 * 底层的不透明背景挡住了被覆盖的页面，视觉上却与普通页面完全一致。
 *
 * 用法：把整页内容（含 Scaffold）放进 [content]，容器色仍用半透明 surface。
 */
@Composable
fun FullScreenOverlay(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier.fillMaxSize()) {
        // 不透明背景：挡住被覆盖的页面，同时保留壁纸观感
        LocalAppBackground.current(Modifier.fillMaxSize())
        content()
    }
}
