# 安全说明 / Security Policy

叁省手账（Thrice Journal）是一款**离线优先**的 Android 应用：除用户主动发起的 AI 对话外，
它不发起任何网络请求，也没有自有服务器。下面说明信任边界与本项目的已知取舍。

## 支持版本

| 版本 | 是否提供安全修复 |
|---|---|
| 当前 main 分支 | 是 |
| 已发布的 release tag | 仅最新 tag |

发现漏洞请通过 [Issue](https://github.com/EdmundAshford/Thrice/issues) 私下联系维护者，
不要公开披露可利用的细节，给修复留出时间。

## 数据与隐私设计

- **全部业务数据（课表 / 任务 / 账单 / 专注 / 睡眠 / 笔记 / AI 对话）只存在本机 Room 数据库中**，不上传。
- **唯一的网络出口是 AI 对话**：请求发往用户在设置里手填的服务商地址，并携带用户自己填的 API Key。
  不配置 AI 时，应用可以在完全离线状态下使用其余所有功能。
- 接口地址必须是以 `https://` 开头的合法 URL（`AiUrlValidator` 强制校验），
  否则不会把 API Key 发往该主机。
- HTTP 客户端不挂载任何日志拦截器，响应体读取有 8 MB 上限。
- AI 工具调用**每次执行都重新读取最新的权限矩阵**（不是会话开始时的快照），
  未授权的工具根本不会下发给模型；工具层不使用任何原始 SQL 拼接，也不接受 AI 传入的文件路径。

## 已知取舍（不是漏洞，但你应该知道）

1. **API Key 明文存于本机**：使用 DataStore（`ai_secrets.preferences_pb`），未用 Keystore 加密。
   已通过 `backup_rules` / `data_extraction_rules` 排除在**云备份**之外；
   但**换机本地迁移（device-transfer）会带走** Key（为了换机免重填）。
   不接受该取舍的话，取消 `data_extraction_rules.xml` 中对应 `exclude` 行的注释即可。
2. **不拦截内网地址**：若用户手动把接口地址填成 `https://192.168.x.x`，Key 会发往该主机。
   这是「用户自带服务端点」设计的必然结果，请只填写你信任的服务商地址。
3. **自动备份的是 SQLite 主库文件**，不含 WAL 日志，最近未 checkpoint 的写入可能不在备份内。
   可靠的数据搬家请用应用内导出的 JSON 备份。
4. **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** 权限用于设置页的「忽略电池优化」入口
   （国产 ROM 上提醒准时性的必要手段）。上架 Google Play 前请移除该权限与对应入口。

## 报告漏洞时请附带

- 影响版本 / commit
- 复现步骤（如需设备信息，请说明 Android 版本与 ROM）
- 实际影响（例如「越权读取未授权数据」「密钥外泄」）
