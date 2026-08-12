# Moji 双端架构

## 边界

- `core/`：无 UI 的 Kotlin Multiplatform 模块，保存跨端数据结构、备份 codec、日期归属、倒数计算、统计和番茄周期规则。
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
