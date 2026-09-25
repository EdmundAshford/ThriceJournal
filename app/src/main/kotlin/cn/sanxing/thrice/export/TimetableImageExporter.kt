package cn.sanxing.thrice.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import cn.sanxing.thrice.R
import cn.sanxing.thrice.data.domain.model.Course
import cn.sanxing.thrice.data.domain.model.CourseKind
import cn.sanxing.thrice.data.domain.model.SectionTime
import cn.sanxing.thrice.data.domain.model.Term
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 把当前周的整张课表渲染成一张长图（明信片构图 + 几何抽象 + 线条）。
 *
 * 实现选择：直接在 android.graphics.Canvas 上按与课表页同一套规则手绘
 * （同样的取色逻辑 UiUtils.courseColor / 12 色板、同样的小节映射、同样的卡片版式）。
 * 理由：Compose 截图（drawToBitmap/PixelCopy）需要挂载隐藏的 Composable 树且受 API 限制，
 * Canvas 手绘无任何视图依赖、可在后台线程执行、分辨率完全可控（防 OOM）。
 *
 * 防 OOM：宽度固定 1080px（不随超大屏暴涨），高度按内容精确计算；
 * ARGB_8888，不开 largeHeap。写 cacheDir/shared/ 后由 FileProvider 分享。
 */
object TimetableImageExporter {

    private const val WIDTH = 1080
    private const val PAD = 56f
    private const val TIME_COL = 150f
    private const val DAY_ROW = 96f
    private const val ROW_H = 104f
    private const val HEADER_H = 300f
    private const val OTHER_LINE_H = 64f

    /** 长图高度硬上限（px）：超过则不再向下绘制，防止自定义 30 节次时 OOM。 */
    private const val MAX_HEIGHT = 8000

    /** 「其他课程」最多列出的条数，超出部分省略。 */
    private const val MAX_OTHER_ROWS = 30

    private val coursePalette = listOf(
        0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFFE53935,
        0xFF8E24AA, 0xFF00ACC1, 0xFFFDD835, 0xFF6D4C41,
        0xFFEC407A, 0xFF5E35B1, 0xFF00897B, 0xFF7CB342
    )

    /** 渲染当前周长图，返回 PNG 文件（cacheDir/shared/timetable_week_N.png）。 */
    suspend fun exportWeek(
        context: Context,
        term: Term,
        week: Int,
        courses: List<Course>,          // 已按周过滤的网格课程
        otherCourses: List<Course>,     // 其他课程（长图底部列出）
        sectionTimes: List<SectionTime>,
        appTypeface: Typeface = Typeface.DEFAULT   // 用户所选编号字体（图片导出同步生效）
    ): File {
        val gridCourses = courses.filter { it.kind == CourseKind.GRID && it.dayOfWeek in 1..7 && week in it.weeks }
        val colW = (WIDTH - PAD * 2 - TIME_COL) / 7f
        val rows = sectionTimes.size.coerceAtLeast(1)
        val gridH = DAY_ROW + rows * ROW_H
        // 「其他课程」可能非常多（几百门），且用户可自定义到 30 节次；
        // 高度无上限会让 Bitmap 直接 OOM。截断列表并对高度设硬上限。
        val shownOthers = otherCourses.take(MAX_OTHER_ROWS)
        val otherH = if (shownOthers.isEmpty()) 0f
        else (OTHER_LINE_H * (shownOthers.size + 1) + 40f)
        val height = (HEADER_H + gridH + otherH + PAD * 2 + 80f)
            .toInt().coerceAtMost(MAX_HEIGHT)

        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val dir = File(context.cacheDir, "shared")
        if (!dir.exists() && !dir.mkdirs()) error("无法创建导出目录：${dir.path}")
        val file = File(dir, "timetable_week_$week.png")
        try {
            drawBackground(canvas, height)
            drawHeader(context, canvas, term, week)

            var top = HEADER_H
            drawGrid(context, canvas, top, colW, gridCourses, sectionTimes, rows, appTypeface)
            top += gridH

            if (shownOthers.isNotEmpty()) {
                drawOtherCourses(context, canvas, top, shownOthers)
            }

            drawPostmark(context, canvas, week, height, appTypeface)

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            // 1080 × N 的 ARGB_8888 是几十 MB，任何一步抛异常都必须回收
            bitmap.recycle()
        }
        return file
    }

    // ---------------- 绘制 ----------------

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
    }

    private val ink = AndroidColor.rgb(0x16, 0x28, 0x3A)
    private val inkMuted = AndroidColor.rgb(0x5C, 0x72, 0x85)
    private val accent = AndroidColor.rgb(0x2E, 0x6D, 0xA4)
    private val paper = AndroidColor.rgb(0xF4, 0xF8, 0xFC)

    /** 几何抽象背景：圆、三角、矩形、复杂凌乱线条（固定布局，不随机）。 */
    private fun drawBackground(canvas: Canvas, height: Int) {
        canvas.drawColor(paper)

        // 大块几何
        fillPaint.color = AndroidColor.argb(28, 0x2E, 0x6D, 0xA4)
        canvas.drawCircle(WIDTH * 0.88f, 130f, 220f, fillPaint)
        fillPaint.color = AndroidColor.argb(20, 0xFB, 0x8C, 0x00)
        canvas.drawRect(RectF(WIDTH * 0.05f, height * 0.55f, WIDTH * 0.3f, height * 0.72f), fillPaint)

        // 三角
        fillPaint.color = AndroidColor.argb(18, 0x43, 0xA0, 0x47)
        val tri = Path().apply {
            moveTo(WIDTH * 0.7f, height * 0.85f)
            lineTo(WIDTH * 0.85f, height * 0.85f)
            lineTo(WIDTH * 0.775f, height * 0.75f)
            close()
        }
        canvas.drawPath(tri, fillPaint)

        // 凌乱线条（低透明度，有序地乱）
        linePaint.color = AndroidColor.argb(46, 0x33, 0x50, 0x6B)
        linePaint.strokeWidth = 2f
        var y = 120f
        var i = 0
        while (y < height) {
            canvas.drawLine(0f, y, WIDTH.toFloat(), y + if (i % 2 == 0) 26f else -22f, linePaint)
            y += 210f
            i++
        }
        linePaint.strokeWidth = 1f
        canvas.drawLine(WIDTH * 0.62f, 0f, WIDTH * 0.8f, height.toFloat(), linePaint)
        canvas.drawLine(WIDTH * 0.66f, 0f, WIDTH * 0.86f, height.toFloat(), linePaint)
    }

    private fun drawHeader(context: Context, canvas: Canvas, term: Term, week: Int) {
        textPaint.color = ink
        textPaint.textSize = 66f
        textPaint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(context.getString(R.string.export_app_title), PAD, 120f, textPaint)

        textPaint.textSize = 34f
        textPaint.typeface = Typeface.DEFAULT
        textPaint.color = inkMuted
        canvas.drawText(term.name, PAD, 178f, textPaint)
        canvas.drawText(context.getString(R.string.week_format, week), PAD, 228f, textPaint)

        val timeStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val now = context.getString(R.string.export_generated_fmt, timeStamp)
        textPaint.textSize = 26f
        canvas.drawText(now, PAD, 272f, textPaint)

        // 标题下不对称横线（粗 + 细）
        linePaint.color = accent
        linePaint.strokeWidth = 6f
        canvas.drawLine(PAD, 296f, WIDTH * 0.4f, 296f, linePaint)
        linePaint.strokeWidth = 2f
        linePaint.alpha = 130
        canvas.drawLine(WIDTH * 0.44f, 296f, WIDTH - PAD, 296f, linePaint)
        linePaint.alpha = 255
    }

    private fun drawGrid(
        context: Context,
        canvas: Canvas,
        top: Float,
        colW: Float,
        courses: List<Course>,
        sectionTimes: List<SectionTime>,
        rows: Int,
        appTypeface: Typeface
    ) {
        val weekdayRes = listOf(
            R.string.day_mon, R.string.day_tue, R.string.day_wed, R.string.day_thu,
            R.string.day_fri, R.string.day_sat, R.string.day_sun
        )
        val weekdays = weekdayRes.map { context.getString(it) }
        val todayDow = LocalDate.now().dayOfWeek.value
        val gridLeft = PAD + TIME_COL
        val gridRight = WIDTH - PAD
        val gridBottom = top + DAY_ROW + rows * ROW_H

        // 星期栏
        textPaint.textSize = 30f
        textPaint.typeface = Typeface.DEFAULT_BOLD
        for (d in 1..7) {
            val cx = gridLeft + colW * (d - 1) + colW / 2
            val isToday = d == todayDow
            textPaint.color = if (isToday) accent else inkMuted
            val label = weekdays[d - 1]
            canvas.drawText(label, cx - textPaint.measureText(label) / 2, top + 60f, textPaint)
        }

        // 时间列 + 网格线
        textPaint.textSize = 24f
        textPaint.typeface = appTypeface
        textPaint.color = inkMuted
        linePaint.color = AndroidColor.argb(70, 0x33, 0x50, 0x6B)
        linePaint.strokeWidth = 1.5f
        for (r in 1..rows) {
            val st = sectionTimes.firstOrNull { it.sectionIndex == r }
            val y = top + DAY_ROW + (r - 1) * ROW_H
            val label = st?.let { "${it.startTime}\n${it.endTime}" }
            if (label != null) {
                canvas.drawText(st.startTime, PAD + 18f, y + ROW_H / 2 - 8f, textPaint)
                canvas.drawText(st.endTime, PAD + 18f, y + ROW_H / 2 + 26f, textPaint)
            }
            canvas.drawLine(gridLeft, y, gridRight, y, linePaint)
        }
        canvas.drawLine(gridLeft, gridBottom, gridRight, gridBottom, linePaint)
        canvas.drawLine(gridLeft, top + DAY_ROW, gridLeft, gridBottom, linePaint)
        canvas.drawLine(gridRight, top + DAY_ROW, gridRight, gridBottom, linePaint)
        for (d in 0..7) {
            val x = gridLeft + colW * d
            canvas.drawLine(x, top + DAY_ROW, x, gridBottom, linePaint)
        }
        // 今天列高亮
        fillPaint.color = AndroidColor.argb(18, 0x2E, 0x6D, 0xA4)
        canvas.drawRect(
            RectF(gridLeft + colW * (todayDow - 1), top + DAY_ROW,
                gridLeft + colW * todayDow, gridBottom), fillPaint
        )

        // 课程卡片（圆角色块 + 粗边 + 左侧粗条，与课表页同款）
        // 相邻小节的同一门课合并绘制（规则与课表页一致：同名 / 同地点 / 同老师 /
        // 同上课周，且小节首尾相接），底层数据不变。
        data class ExportCard(val course: Course, val first: Int, val last: Int)
        val cards = courses.groupBy { it.dayOfWeek }.flatMap { (_, dayCourses) ->
            val raw = dayCourses.map { c ->
                ExportCard(
                    course = c,
                    first = c.startSection.coerceIn(1, rows),
                    last = c.endSection.coerceIn(1, rows)
                )
            }.sortedWith(compareBy({ it.first }, { it.course.startSection }, { it.last }))
            val merged = ArrayList<ExportCard>()
            for (iv in raw) {
                val prev = merged.lastOrNull()
                val sameCourse = prev != null &&
                    prev.course.name == iv.course.name &&
                    prev.course.location == iv.course.location &&
                    prev.course.teacher == iv.course.teacher &&
                    prev.course.weeks == iv.course.weeks
                if (sameCourse && prev.last + 1 == iv.first) {
                    merged[merged.lastIndex] = prev.copy(last = iv.last)
                } else {
                    merged.add(iv)
                }
            }
            merged
        }
        for ((course, first, last) in cards) {
            val x0 = gridLeft + colW * (course.dayOfWeek - 1) + 6f
            val y0 = top + DAY_ROW + (first - 1) * ROW_H + 6f
            val x1 = gridLeft + colW * course.dayOfWeek - 6f
            val y1 = top + DAY_ROW + last * ROW_H - 6f
            val color = courseColor(course)

            fillPaint.color = android.graphics.Color.argb(72, AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color))
            canvas.drawRoundRect(RectF(x0, y0, x1, y1), 20f, 20f, fillPaint)
            linePaint.color = color
            linePaint.strokeWidth = 4f
            canvas.drawRoundRect(RectF(x0, y0, x1, y1), 20f, 20f, linePaint)
            // 左侧粗色条
            fillPaint.color = color
            canvas.drawRect(RectF(x0 + 6f, y0 + (y1 - y0) * 0.18f, x0 + 16f, y1 - (y1 - y0) * 0.18f), fillPaint)

            // 文字
            val textX = x0 + 28f
            textPaint.color = ink
            textPaint.typeface = Typeface.DEFAULT_BOLD
            textPaint.textSize = 26f
            val name = ellipsize(course.name, textPaint, x1 - textX - 14f)
            canvas.drawText(name, textX, y0 + 40f, textPaint)

            textPaint.typeface = Typeface.DEFAULT
            textPaint.textSize = 22f
            textPaint.color = inkMuted
            var ty = y0 + 74f
            val spanRows = last - first + 1
            // 单小节行空间有限，只在连堂（≥2 小节）卡片上绘制地点 / 教师
            val sub = if (spanRows >= 2) {
                listOfNotNull(course.location.takeIf { it.isNotBlank() }, course.teacher.takeIf { it.isNotBlank() })
            } else emptyList()
            for (s in sub) {
                if (ty > y1 - 12f) break
                canvas.drawText(ellipsize(s, textPaint, x1 - textX - 14f), textX, ty, textPaint)
                ty += 28f
            }
        }
    }

    private fun drawOtherCourses(context: Context, canvas: Canvas, top: Float, others: List<Course>) {
        var y = top + 60f
        textPaint.color = accent
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.textSize = 30f
        canvas.drawText(context.getString(R.string.export_other_courses_fmt, others.size), PAD, y, textPaint)
        y += OTHER_LINE_H
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = 26f
        textPaint.color = ink
        for (c in others) {
            val weeks = c.weeksRaw.ifBlank { formatWeeks(c.weeks) }
            val line = listOfNotNull(c.name, c.teacher.takeIf { it.isNotBlank() }, weeks.takeIf { it.isNotBlank() })
                .joinToString(" · ")
            canvas.drawText(ellipsize(line, textPaint, WIDTH - PAD * 2), PAD, y, textPaint)
            y += OTHER_LINE_H
        }
    }

    /** 左下角邮戳：虚线圆 + 周次。 */
    private fun drawPostmark(context: Context, canvas: Canvas, week: Int, height: Int, appTypeface: Typeface) {
        val cx = PAD + 90f
        val cy = height - PAD - 90f
        linePaint.color = accent
        linePaint.alpha = 190
        linePaint.strokeWidth = 4f
        linePaint.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        canvas.drawCircle(cx, cy, 76f, linePaint)
        linePaint.pathEffect = null
        linePaint.alpha = 255

        textPaint.color = accent
        textPaint.typeface = appTypeface
        textPaint.textSize = 30f
        val l1 = "叁省"
        canvas.drawText(l1, cx - textPaint.measureText(l1) / 2, cy - 12f, textPaint)
        linePaint.color = accent
        linePaint.strokeWidth = 2f
        canvas.drawLine(cx - 34f, cy + 6f, cx + 34f, cy + 6f, linePaint)
        textPaint.textSize = 26f
        val l2 = context.getString(R.string.week_format, week)
        canvas.drawText(l2, cx - textPaint.measureText(l2) / 2, cy + 44f, textPaint)
    }

    // ---------------- 与课表页一致的取色 / 工具 ----------------

    private fun courseColor(course: Course): Int {
        parseHex(course.colorHex)?.let { return it }
        var hash = 0
        for (ch in course.name) hash = 31 * hash + ch.code
        // 用 `and Int.MAX_VALUE` 而不是 abs()：abs(Int.MIN_VALUE) 仍是负数，
        // 负索引会让 coursePalette[...] 抛 ArrayIndexOutOfBoundsException。
        return coursePalette[(hash and Int.MAX_VALUE) % coursePalette.size].toInt()
    }

    private fun parseHex(hex: String): Int? {
        val s = hex.trim()
        if (!s.startsWith('#') || (s.length != 7 && s.length != 9)) return null
        return runCatching { AndroidColor.parseColor(s) }.getOrNull()
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var s = text
        while (s.isNotEmpty() && paint.measureText("$s…") > maxWidth) s = s.dropLast(1)
        return "$s…"
    }

    /** 与 UiUtils.formatWeeks 同规则（模块内聚，避免 app 层反向依赖）。 */
    private fun formatWeeks(weeks: Set<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.sorted()
        val sb = StringBuilder()
        var start = sorted[0]
        var prev = sorted[0]
        for (i in 1 until sorted.size) {
            val v = sorted[i]
            if (v == prev + 1) prev = v
            else {
                appendRange(sb, start, prev)
                start = v
                prev = v
            }
        }
        appendRange(sb, start, prev)
        return sb.toString()
    }

    private fun appendRange(sb: StringBuilder, start: Int, end: Int) {
        if (sb.isNotEmpty()) sb.append(',')
        if (start == end) sb.append(start) else sb.append(start).append('-').append(end)
    }
}
