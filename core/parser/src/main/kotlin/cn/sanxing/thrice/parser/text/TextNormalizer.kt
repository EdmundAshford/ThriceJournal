package cn.sanxing.thrice.parser.text

/**
 * 文本归一化：全角→半角、去零宽字符、统一换行、压缩连续空白。
 * 归一化是解析的**第一步**，之后所有解析器都工作在归一化后的文本上。
 */
object TextNormalizer {

    fun normalize(raw: String): String {
        var s = raw
        // 统一换行（Windows \r\n 与旧 Mac \r 都归一到 \n）
        s = s.replace("\r\n", "\n").replace('\r', '\n')
        // 去除零宽字符
        s = s.replace(Regex("[\u200B\u200C\u200D\uFEFF]"), "")
        // 全角 → 半角（ASCII 相关的标点 / 字母 / 数字）
        s = toHalfWidth(s)
        // 压缩行内连续空白（保留换行，避免破坏格式 A 的"行结构"）
        s = s.replace(Regex("[ \t]+"), " ")
        return s
    }

    /**
     * 全角 → 半角。仅处理全角 ASCII 区（U+FF01..U+FF5E）与全角空格（U+3000），
     * 中文字符（U+4E00..）不受影响。
     */
    private fun toHalfWidth(s: String): String {
        if (s.none { it.code in 0xFF01..0xFF5E || it == '\u3000' }) return s
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == '\u3000' -> sb.append(' ')                 // 全角空格
                ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar())
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
