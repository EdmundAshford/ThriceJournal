# Security Policy / 安全说明

Thrice Journal is an **offline-first** Android app: apart from AI conversations the user explicitly initiates,
it makes no network requests and runs no servers of its own. This document describes the trust boundaries
and the deliberate trade-offs of the project.

## Supported versions / 支持版本

| Version | Security fixes |
|---|---|
| Current `main` branch | ✅ Yes |
| Published release tags | Latest tag only / 仅最新 tag |

Report vulnerabilities via a **private** channel (e.g. [open an issue](https://github.com/EdmundAshford/ThriceJournal/issues) with minimal public detail, then follow up privately).
Please do not publicly disclose exploitable details before a fix is available. / 发现漏洞请先私下联系维护者，给修复留出时间。

## Data & privacy design / 数据与隐私设计

- **All user data (timetable / tasks / bills / focus / sleep / notes / AI chats) stays in a local Room database on the device.** Nothing is uploaded.
- **The only network egress is the AI chat**: requests go to the provider URL *you* enter in Settings, carrying *your* API key. Without AI configured, every other feature works fully offline.
- The endpoint must be a valid `https://` URL (enforced by `AiUrlValidator`); the API key is never sent to a host that fails validation.
- The HTTP client mounts no logging interceptors; response bodies are capped at 8 MB.
- AI tool calls re-read the latest permission matrix on **every** execution (not a session-start snapshot); unauthorized tools are never advertised to the model. The tool layer uses no raw SQL string concatenation and accepts no file paths from the model.
- 业务数据只存本机 Room 数据库，不上传；唯一网络出口是用户自带 Key 的 AI 对话。

## Known trade-offs (not vulnerabilities, but you should know) / 已知取舍

1. **API keys are stored in plaintext on-device** (DataStore `ai_secrets.preferences_pb`, not Keystore-encrypted).
   They are excluded from **cloud backup** via `backup_rules` / `data_extraction_rules`,
   but **device-to-device transfer does carry them over** (so you don't re-enter after switching phones).
   To reject this trade-off, uncomment the corresponding `<exclude>` line in `data_extraction_rules.xml`.
2. **Private addresses are not blocked**: if you enter `https://192.168.x.x` as the endpoint, your key goes there.
   This is inherent to the user-supplied-endpoint design — only configure providers you trust.
3. **Auto-backup exports the main SQLite file** without the WAL log; the most recent uncheckpointed writes may not be in the backup. For reliable migration use the in-app JSON backup export.
4. **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** powers the "ignore battery optimization" entry in Settings
   (needed for reliable reminders on Chinese ROMs). Remove this permission and its entry before publishing to Google Play.

## When reporting, please include / 报告时请附带

- Affected version / commit
- Steps to reproduce (include Android version and ROM if a device is involved)
- Actual impact (e.g. "unauthorized data read", "key exfiltration")
