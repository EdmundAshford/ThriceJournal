package cn.sanxing.thrice.util

import kotlinx.coroutines.CancellationException

/**
 * 与标准 `runCatching` 等价，但会**原样抛出** [CancellationException]。
 *
 * 直接在协程里写 `runCatching { ... }` 会把取消信号当成普通失败吞掉：被取消的协程
 * 继续执行后续代码并「假装成功」，破坏结构化并发（典型症状：退出页面后仍在写库、
 * 重复发网络请求、把伪造的失败结果回灌给调用方）。
 *
 * 凡是协程内的容错调用都应使用本函数。
 *
 * @return 成功为 [Result.success]；非取消类异常为 [Result.failure]
 */
inline fun <R> runCatchingOrCancel(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }
