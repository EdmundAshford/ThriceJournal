package cn.sanxing.thrice.parser

/** 加载测试资源里的样例文本。 */
object TestSamples {
    fun load(name: String): String =
        checkNotNull(TestSamples::class.java.getResourceAsStream("/samples/$name")) {
            "缺少测试资源 /samples/$name"
        }.bufferedReader().use { it.readText() }
}
