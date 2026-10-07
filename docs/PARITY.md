# iOS / Android 功能对齐

| 能力 | iOS | Android |
| --- | --- | --- |
| 计划、重复、顺延、跳过 | SwiftUI + App Group | Compose + Room |
| 月/周/日历 | SwiftUI | Compose |
| 备忘和核对清单 | SwiftUI/UIKit 输入 | Compose 输入 |
| 倒数日与纪念日 | SwiftUI | Compose |
| 番茄钟 | Live Activity | 锁屏持续通知 |
| 系统日历 | EventKit | Calendar Provider |
| 提醒 | UserNotifications | AlarmManager |
| 自动备份 | security-scoped bookmark | SAF persisted URI + WorkManager |
| 三类桌面组件 | WidgetKit | AppWidget RemoteViews |
| 本地存储 | JSON/App Group | Room + SharedPreferences |

## 刻意保留的差异

- Android 没有灵动岛或 iOS Live Activity，使用持续通知显示倒计时与状态。
- iOS 文件夹授权书签和 Android SAF URI 都只对创建它的安装有效，不随备份迁移。
- 系统日历事件标识不跨平台复用；备份只保留用户“希望同步到日历”的意图。
- 本版本不包含 Wear OS 独立客户端。
