package cn.sanxing.thrice.parser

import cn.sanxing.thrice.parser.model.ScheduleFormat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * golden 快照：把每个解析结果的 JSON 快照写入 build/test-golden/ 供人工核对，
 * 并与 src/test/resources/golden/ 下的"摘要基准"（18/13/6/2/0）做回归比对。
 *
 * 生成完整快照：`gradle :core:parser:test -DupdateGolden=true`
 */
@OptIn(ExperimentalSerializationApi::class)
class GoldenSnapshotTest {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Serializable
    data class Snapshot(
        val format: String,
        val rawCount: Int,
        val mergedCount: Int,
        val distinctNames: Int,
        val otherCount: Int,
        val conflictCount: Int
    )

    @Test
    fun `格式A golden 摘要与硬数字一致`() {
        val result = ImportScheduleUseCase().importText(TestSamples.load("sample_formatA.txt"))
        val snapshot = snapshotOf(result, ScheduleFormat.FORMAT_A)
        val golden = loadGolden("formatA.summary.json")
        assertEquals(golden, snapshot)

        dumpFull(result, "formatA.full.json")
    }

    @Test
    fun `格式B golden 摘要与硬数字一致`() {
        val result = ImportScheduleUseCase().importText(TestSamples.load("sample_formatB.txt"))
        val snapshot = snapshotOf(result, ScheduleFormat.FORMAT_B)
        val golden = loadGolden("formatB.summary.json")
        assertEquals(golden, snapshot)

        dumpFull(result, "formatB.full.json")
    }

    private fun snapshotOf(result: cn.sanxing.thrice.parser.model.ParseResult, format: ScheduleFormat) =
        Snapshot(
            format = format.name,
            rawCount = result.rawCourses.size,
            mergedCount = result.courses.size,
            distinctNames = result.courses.map { it.name }.distinct().size,
            otherCount = result.otherCourses.size,
            conflictCount = result.conflicts.size
        )

    private fun loadGolden(name: String): Snapshot {
        val text = checkNotNull(GoldenSnapshotTest::class.java.getResourceAsStream("/golden/$name")) {
            "缺少 golden 资源 /golden/$name"
        }.bufferedReader().use { it.readText() }
        return json.decodeFromString<Snapshot>(text)
    }

    /** 把完整解析结果快照写到 build/test-golden/（每次运行都生成，供人工核对与提交）。 */
    private fun dumpFull(result: cn.sanxing.thrice.parser.model.ParseResult, name: String) {
        val dir = File("build/test-golden")
        dir.mkdirs()
        File(dir, name).writeText(json.encodeToString(result))
        if (System.getProperty("updateGolden") == "true") {
            val goldenDir = File("src/test/resources/golden")
            goldenDir.mkdirs()
            File(goldenDir, name).writeText(json.encodeToString(result))
        }
        assertNotNull(result.rawText)
    }
}
