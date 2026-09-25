# core:parser —— 课表解析器（纯 Kotlin JVM 模块）

解析各类系统导出的课表文本（格式 A 表格型、格式 B 分页型、CSV、粘贴文本），
零 Android 依赖，可脱离 Android 单独跑测试。

## 包结构

```
cn.sanxing.thrice.parser
├── format/FormatDetector.kt    # 自动判定 A / B / CSV / 未知 + 置信度 + 依据
├── text/TextNormalizer.kt      # 全角→半角、去零宽、统一换行、压缩空白
├── text/PdfTextExtractor.kt    # 接口 + 带坐标的 PDF 文本模型（实现放 core:data）
├── parse/CommonParser.kt       # 别名表、kv 抽取、星期/节次/周次解析、学分粘连修复
├── parse/FormatAParser.kt      # 表格型 / 挤一行型
├── parse/FormatBParser.kt      # 分页型 / 结构化型（含 parsePositioned 按列定星期）
├── parse/OtherCourseParser.kt  # 「其他课程」段
├── parse/CsvParser.kt
├── merge/CourseMerger.kt       # 同课名+星期+节次 → 周次并集；教师分段保留
├── conflict/ConflictDetector.kt# 同天同节次且周次有交集才算冲突
├── model/ParsedSchedule.kt     # ParseResult / ParsedCourse / Conflict / 备份模型等
├── ics/IcsExporter.kt          # 手写 RFC 5545 ICS 导出（TZID + UTC DTSTAMP、字节折行）
├── backup/                     # 全量备份 JSON 的序列化 / 反序列化
├── reminder/ReminderCalculator.kt # 周次计算、下一次提醒时刻
└── ImportScheduleUseCase.kt    # 四段式导入管线 + reparseWithOverride + applyManualFix
```

## 如何运行测试

```bash
# 在项目根目录执行（JDK 17；无需 Android SDK）
./gradlew :core:parser:test
```

- 样例输入：`src/test/resources/samples/`（虚构数据，不含任何真实个人信息）
- golden 摘要基准：`src/test/resources/golden/`
- 重新生成完整 JSON 快照：`./gradlew :core:parser:test -DupdateGolden=true`
  （完整快照另写到 `build/test-golden/` 供人工核对）

## 关键容错点

- **字段别名**：课程名/教师/地点(场地)/星期/节次/周次/学分/考核方式/校区/教学班/教学班组成/选课备注/课程学时组成/周学时/总学时 等，中英、大小写、空白不敏感。
- **周次**：`1-16周`、`1-8,10-16周`、`5-7周(单)`、`8-14周(双)`、`6-8周,10-17周,19周`、`1,3,5,7`；
  `(单)/(双)` 只作用于紧邻区间（`5-7周(单),8-12周` = {5,7}∪{8..12}）。
- **节次**：`第1-2节`、`1-2节`、`1-2`、`0102`、`(1-2节)`。
- **星期**：`周一`、`星期一`、`礼拜一`、`1`。
- **粘连修复**：`学分:2.55-6高等数学A1*` → 学分 2.5，下一门节次 5-6。
- **跨行拼接**：字段名与冒号被换行拆开（`考核方式\n:考试`）、字段值跨行断开（`教学班组成\n:200201;…`）。
- **「其他课程」**：`课程名教师(共N周)/周次/备注`，`;` 分隔多条，周数校验（展开个数 ≠ N 时告警），
  不并入网格课程。
- **格式 B 星期**：由列位置决定，纯文本导出丢失列信息时星期置 0 并告警，PDF 坐标可还原。
- **ICS 导出**：事件时间使用 `Asia/Shanghai` 的 TZID 形式，DTSTAMP 为 UTC（带 Z）；
  开始小节缺时间配置的事件跳过，不伪造时刻。
