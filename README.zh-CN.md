# 叁省手账 · Thrice Journal

[English](README.md) | 简体中文

一款离线优先的 Android 个人管理 App：**课表 / 任务待办 / 记账 / 专注计时 / 睡眠记录 / 笔记** 六位一体，
并内置一个可由用户自带 API Key 驱动的 **AI 助手**。各类教务系统导出的课表（PDF / CSV / 粘贴文本）
可在 App 内一键识别导入，业务数据全部只保存在本机。

> **合规声明**：本 App 不自动登录任何网站或系统、不爬虫、不代操作；只处理用户主动导入的数据。
> 应用本身不内置任何服务器，唯一的网络请求发生在**用户自行配置 AI 服务商地址与 API Key 并主动发起对话时**
> （请求直接发往用户填写的服务商）。不配置 AI 即可在完全离线状态下使用全部其它功能。

## 下载与安装（普通用户）

1. 进入本仓库 **Releases** 页面：<https://github.com/EdmundAshford/ThriceJournal/releases>
2. 下载最新版本的 `Sanxing.apk`（约 62 MB，内置 12 款开源字体，无需其它依赖）
3. 传到手机安装；首次安装需在系统提示中允许「安装未知来源应用」（各品牌菜单名略有差异）

> 本 App 无需联网注册、不含广告与内购；所有数据只保存在手机本机。
> 体积较大的原因是包内随带了 12 款开源中文字体（约 45 MB），属正常现象。

## 功能一览

### 课表
- **智能导入**：Excel / WPS 或各类系统复制的表格、带中英文表头的 CSV、PDF 文本以及
  “课程名 周一 第1-2节 1-16周 教室”这类自由文本；星期、节次、周次（含单双周）自动提取，逐条可手动修正
- 周课表网格、调课 / 停课 / 补课例外标注、其他课程列表、全部课程页
- 多套**课表方案（学期）**：各自独立，随时切换
- 导入最后一步可新建并命名学期，也可导入到任意已有学期
- 课表长图分享（明信片风格手绘导出）、ICS 日历导出（全学期 / 本周）
- 课前提醒通知（精确闹钟，重启后自动重排）、Glance 桌面小组件（今日课程 / 本周迷你课表）

### 任务待办
- 日历 + 列表双视图；日期、起止时间、备注、类型、标签
- **重复规则**：每天 / 每周 / 每月 / 每年 / 间隔 N 天 / 法定工作日 / 艾宾浩斯 / 指定日期集，
  支持结束日期与次数上限、分次完成
- 到期通过系统精确闹钟弹出通知

### 账单
- 收支记录、备注、可自定义的分类与 15 色调色板
- 月 / 年统计：每日 / 每月支出柱图、分类占比环形图、收支结余与日均

### 专注
- 正计时 / 倒计时番茄钟，水球动画，暂停 / 放弃 / 完成
- 到点自动结算落库（精确闹钟 + 前台服务保活，杀进程也能恢复），结束通知，专注历史统计

### 睡眠
- 夜睡 / 午睡记录与补录，睡眠目标（时长或入睡 - 起床时刻）
- 入睡 / 午睡定时提醒，睡眠时长统计

### 笔记
- 文件夹分组、标签、Markdown 风格轻量渲染
- 每篇笔记可单独设定字号与字体（或跟随全局）

### AI 助手（可选）
- 多服务商配置：用户**自带**接口地址与 API Key（Key 存于独立 DataStore，不进备份、不随云备份外传）
- 可切换人设（系统提示词）、按域授予数据读写权限；获授权后可代为查询 / 修改本机课表、任务、账单、笔记等
- 对话历史在发送前自动截断，避免无限累积

### 个性化
- 10 套主题配色 + RGB 自定义颜色；自定义壁纸与主体浓度分别可调；深色模式
- 中英双语界面（跟随系统 / 手动切换）
- 可选的 12 档编号字体（见下文「字体」一节）

### 数据
- 本地备份 / 恢复（覆盖或合并）、按类导出与清空；Room 数据库 schema v11，10 条连续迁移（v1 → v11，无破坏性迁移）

## 技术栈

| 项 | 版本 |
|---|---|
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose（BOM 2024.10.01，Material3） |
| 构建 | AGP 8.7.3 + Gradle 8.9 + JDK 17 |
| SDK | compileSdk 35 / minSdk 26 / targetSdk 35 |
| 数据库 | Room 2.6.1（KSP），含连续迁移 |
| DI | Hilt 2.52（KSP） |
| 偏好 / 密钥 | DataStore Preferences 1.1.1 |
| 网络 | OkHttp 4.12（仅 AI 对话，用户自配服务商） |
| 提醒 / 后台 | AlarmManager 精确闹钟 + WorkManager 2.9.1 + 前台服务 |
| 小组件 | Glance AppWidget 1.1.0 |
| PDF 文本 | pdfbox-android 2.0.27.0 |

## 模块划分

```
:app          Android 应用（Compose UI / 提醒 / 小组件 / 导入导出 / AI 界面）
:core:data    Android library：Room / Repository / DataStore / AI 工具引擎
:core:parser  纯 Kotlin JVM：课表解析、ICS / 备份格式、提醒时间计算，零 Android 依赖，可单测
```

模块依赖单向：`:app → :core:data → :core:parser`。

## 如何编译

环境要求：**JDK 17**、**Android SDK Platform 35**（命令行构建还需 Build-Tools 与 Platform-Tools）。

仓库已含 gradle-wrapper.jar，首次构建前在项目根目录创建 `local.properties`（已被 git 忽略）：

```properties
sdk.dir=C\:\\Users\\你\\AppData\\Local\\Android\\Sdk
```

```bash
# Debug 直接编译安装（无需任何签名配置）
./gradlew :app:assembleDebug

# 只跑解析器纯 JVM 测试（不需要 Android 设备）
./gradlew :core:parser:test
```

> 依赖仓库默认先走阿里云镜像、官方源兜底；Gradle 发行包走腾讯云镜像。
> 海外网络可自行调整 `settings.gradle.kts` 与 `gradle/wrapper/gradle-wrapper.properties`。

Release 包默认**不带签名配置**，请在 `app/build.gradle.kts` 中加入自己的 `signingConfigs`
（或直接使用 debug 包自用）。仓库不含、也不会提交任何 keystore。

### 字体

`app/src/main/res/font/` 下的 12 个编号字体（f01.ttf … f12.ttf）**随仓库分发**，
全部为 **SIL OFL 1.1** 授权的开源字体（Noto Sans SC、站酷三款、霞鹜文楷四种、
马善政楷书、志莽行书、龙藏体、柳建毛草），可自由使用与再分发。

仓库内是**子集化后的修改版本**（只保留 ASCII + GB2312 码位，去除 `name` 表中的字体名、
hinting 与 OpenType 布局表），合计约 45 MB。依据 OFL「保留字体名」条款，
修改版本不得再使用原字体名，因此应用界面只显示编号 01–12。
字体名、版权与许可全文见 **[THIRD_PARTY_FONTS.md](THIRD_PARTY_FONTS.md)**。

> 仓库**只接受允许再分发的字体**。子集化不会改变字形轮廓，也无法去除
> `OS/2.achVendID` 等字厂标识，因此任何商业授权字体（漢儀 / 方正 / 文鼎 / 华康 等）
> 都不应进入本项目。

需要重新生成（例如增减字体）时：

```bash
pip install fonttools
python tools/fetch_open_fonts.py   # 下载 12 款原始 OFL 字体到 Fonts/（约 110 MB，已被 git 忽略）
python tools/subset_fonts.py       # 子集化输出到 app/src/main/res/font/fNN.ttf
```

新增 / 减少字体时，同步调整 `AppFontOption` 枚举（`ui/theme/Theme.kt`）的编号项即可，
字体选择器与实际可选项都是按包内真实存在的资源动态收窄的。

## 内置样例

- App 内「载入内置样例」使用 `app/src/main/assets/samples/` 下的两份 PDF，
  内容为**完全虚构的课表**（人名、学号、群号、教师、教室均为假数据）。
- 解析器的 golden 测试样例在 `core/parser/src/test/resources/samples/`。

## 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` | 仅 AI 对话使用，请求发往用户自己配置的服务商；不配置 AI 时无任何网络访问 |
| `POST_NOTIFICATIONS` | 课程 / 任务 / 睡眠 / 专注提醒（Android 13+ 运行时申请） |
| `SCHEDULE_EXACT_ALARM` | 在用户指定时刻准时提醒；未授权时自动降级非精确闹钟 |
| `RECEIVE_BOOT_COMPLETED` | 重启后自动重新排程 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 专注计时进行中常驻保活 |

除用户主动发起的 AI 对话外，应用不上传任何数据。

## 测试与调试

```bash
./gradlew :core:parser:test                 # 解析器 / ICS / 备份往返单测
./gradlew :core:parser:test -DupdateGolden=true   # 重新生成 golden 完整快照
```

- golden 基准：`core/parser/src/test/resources/golden/`
- 完整解析结果快照输出到各模块 `build/test-golden/`（供人工核对，不参与回归断言）

## 已知限制

- 数据库内少量历史种子数据（默认任务类型、账单默认分类名）在旧版本中以中文写入，不随系统语言切换，可自行改名或重置。
- 「法定工作日」规则内置 2026 年放假 / 调休数据；其它年份回落到周一至周五判定。
- AI API Key 以明文存于本机 DataStore（`ai_secrets.preferences_pb`），**不使用**硬件密钥库加密。
  已排除在云备份之外（不会上传到 Google Drive），但换机本地迁移（device-transfer）会带走，
  以换取「换机免重填」。若你在意，可取消 `data_extraction_rules.xml` 中对应 exclude 行的注释并重新构建。
- 自动备份（Auto Backup）备份的是 SQLite 主库文件，不含 WAL 日志，因此最近未 checkpoint 的写入可能丢失。
  需要完整可靠的数据搬家请使用应用内「备份 / 恢复」导出的 JSON。
- PDF 解析使用 pdfbox-android；超大 PDF（数十 MB / 上千页）可能触发内存不足。

## 开源协议

[MIT License](LICENSE)，署名 EdmundAshford。欢迎 Issue 与 PR：
<https://github.com/EdmundAshford/ThriceJournal>
