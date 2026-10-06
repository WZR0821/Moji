# Moji 双端架构

## 边界

- `core/`：无 UI 的 Kotlin Multiplatform 模块，保存数据结构、备份 codec、日期归属、倒数计算、统计和番茄周期规则。Android 已调用它；当前 iOS Xcode 工程仍以 `Shared/` 中的 Swift 规则为主，尚未完成业务规则接入 KMP。
- `ui/`：Android Compose 的主题与水墨组件。
- `androidApp/`：Android Activity、Room、系统日历、通知、后台备份和 App Widget。
- `Moji/`、`MojiWidget/`、`Shared/`：原有 iOS SwiftUI、Widget/Live Activity 和 App Group 数据层。

规则是“决定共享，画法原生”。Android 与 iOS 各自使用成熟的 UI、权限和存储能力，不把系统接口包装成虚假的公共层。

## 数据

Android 的 Room 数据库只保存一份事务化 `PlanSnapshot`，业务对象与 iOS schema 12 同名同值。设置存放在私有 SharedPreferences；导出时组装为 iOS 兼容的 `PlanBackupArchive(formatVersion=1)`。

以下数据只在本机保存，不进入跨端备份：文件夹 URI、通知授权、日历授权、Android 日历 event id、桌面组件实例配置。

## 身份

- Android applicationId：`com.raydon.moji.android`
- iOS App：`com.raydon.minuteplan`
- iOS Widget：`com.raydon.minuteplan.widget`
- iOS App Group：`group.com.raydon.minuteplan`

这些值发布后不得更改。

## 备份与恢复边界（2026-10-05）

- 归档只接受 `formatVersion=1`、`appIdentifier=com.raydon.moji`；支持 schema 1–12。旧快照必须有 `records`、`countdowns`、`lastUpdated`，可缺少后来加入的计划和备忘字段。
- 两端先辨别归档／旧快照。损坏归档不回退为旧快照；无效数据在修改本地数据前拒绝。
- 日期兼容整数秒、小数秒 ISO-8601。旧 `completed` 按 `completedLog` 读取；非精确时间记录允许起止相等。
- 日历事件 ID 仅属于原设备；导出与恢复会清除这些 ID，不改变原设备当前数据。
- Android 数据整理在后台运行；自动备份读 Room 已提交快照，并合并连续编辑的外部写入。恢复前保留私有本机副本，外部写入先校验临时副本。
- Android 快照与 SharedPreferences、iOS 快照与 UserDefaults 仍不是一个跨存储事务。中途断电或进程终止的恢复一致性，仍需后续日志化与故障注入验证。
