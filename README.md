# Moji

> [!IMPORTANT]
> **本 APP 通过 GPT-5.6-sol 模型开发。**

Moji 是一款黑白朱红水墨风的 iOS / Android 效率 App，把计划、日历、备忘、番茄钟、倒数日和纪念日收进同一套轻量工作流。iOS 使用原生 SwiftUI，Android 使用 Jetpack Compose；Android 使用 Kotlin Multiplatform 核心中的日期、统计、番茄钟与备份规则；iOS 当前仍使用 Swift 实现，通过格式契约和双端测试维护兼容。应用不依赖账号体系或业务服务器。

作者：**Jerry Wong**。

## 主要功能

### 计划

首页用于快速记录和处理今日事项，计划可以设置日期、时段、精确时间、预计时长、分类及本地提醒，也能按每天、工作日、每周、自选星期或每月重复。月、周、日三种日历视图会统一展示未来计划、已完成事项和独立记录，计划还可以选择写入 Apple 日历。

未完成的旧计划默认顺延到今天，也可以在设置中关闭。顺延项目会在标题右侧显示一枚朱红方印，只标注顺延天数，不修改原始标题和说明。计划可以直接勾选、跳过、顺延或带入番茄钟；完成番茄钟后会生成真实专注记录，并按规则更新计划状态。

1.5.0 新增计划库：搜索标题及说明，按类型、状态、过去／今天／未来或日期范围筛选，批量改日期、改类型和归档，支持撤销。单个计划可以复制到今天或指定日期，保存为模板后再次添加；复制与模板实例不继承完成记录和重复序列。

### 备忘

备忘与计划相互独立，不参与完成率和专注统计。编辑页支持普通文本与核对清单，留空标题时会自动使用正文首行。备忘可以全文搜索、置顶、取消置顶和删除，所有内容都会随 Moji 数据快照一起备份和恢复。

已保存的整篇备忘或单条清单项目可转为计划，保留原说明和来源关联，同一个来源不会重复生成计划。

### 时刻与专注

倒数日用于未来日期，纪念日用于已经过去的日期；两者支持重复规则、重点标记、手动排序、按时间排序和 Apple 日历同步。重复纪念日可以同时显示下一次还有多少天，以及从最初日期起已经过去多久。

番茄钟支持专注、短休息和长休息，自定义时长、自动衔接、本地通知、声音触感与常亮设置。计时状态由 App 统一维护，切换页面或进入后台后仍会按照绝对结束时间推进。iOS 18 上可显示锁屏实时活动、灵动岛和 Apple Watch 智能叠放，并直接暂停、继续或结束；iOS 17 上仍可正常使用计时、通知和桌面小组件。

### 回顾

个人页集中展示本周摘要、日周月总结和最近记录。完整统计包括完成率、计划与实际用时、月度热力图、连续打卡及学习/工作趋势。设置中可调整计划默认值、番茄钟、提醒、外观、权限和数据备份。

周总结可设置完成数量／实际专注分钟目标，支持全部类型或指定类型；周复盘记录本周反馈和下周关注，可选待办移至下周。未计时完成项明确显示“未记录”，不会虚构专注时长。

## 小组件与数据

Moji 提供倒数日、纪念日和计划三个桌面小组件。倒数日与纪念日组件最多显示三个可选条目；计划组件显示三条事项，可直接完成、恢复或使用预设快速添加。App 与 Widget 通过兼容旧版本的 App Group 共享本地 JSON 快照。

业务数据默认保存在设备本地，没有 Moji 账号、第三方业务 SDK 或业务服务器。用户可以手动导出 JSON 备份，也可以在「文件」App 中指定文件夹自动保存最新快照和每日历史备份。重新签名或换机后，可从原文件夹选取备份恢复，并重新授权该文件夹继续自动备份。

## 双端版本

当前源码版本：`1.5.1 (build 41)`，在 1.5.0 的功能基础上修正双端视觉与深层页面交互。见 [1.5.1 更新说明](docs/RELEASE_NOTES_1.5.1.md)及 [验证与交付记录](docs/VERIFICATION_1.5.1.md)。1.5.0 从最新 `1.4.1 (build 39)` 合并原定 1.5／1.6 两步功能，历史记录见 [1.5.0 更新说明](docs/RELEASE_NOTES_1.5.0.md)。以下功能于 1.4.0 双端基线引入。

- 新增完整 Android 客户端：计划、月周日历、备忘、倒数与纪念、番茄钟、回顾和设置。
- Android 支持 Room 本地存储、系统日历、计划提醒、锁屏番茄通知、自动备份和三类桌面组件。
- iOS 与 Android 使用同一个版本真源和 Moji 备份格式；1.5.0 使用 schema 13，兼容读取旧 schema 12 备份，新增模板、周目标及复盘内容随快照保存。
- 新增一条命令生成签名 APK、签名 AAB、未签名 IPA、源码包与 SHA-256 校验文件。

> iOS IPA 仍为未签名包，需要使用个人或开发者证书重新签名。Android Release 使用首次发布时生成并固定保存的 Moji 专用密钥。

## 开发维护进展

2026-10-05 完成一轮数据安全、备份体验与主线程性能维护。具体修改、验证边界、阶段总结及后续优先级见 [开发总结与维护方案](docs/DEVELOPMENT_REVIEW_2026-10-05.md)。这些是当前源码的维护成果，本轮已生成 1.4.1（39）未签名 IPA；旧的 1.4.0 发布包不包含这些修改。

## 工程与构建

使用 Xcode 打开 `Moji.xcodeproj` 可运行 iOS；Android 工程位于 `androidApp/`。完整双端发布：

```bash
export JAVA_HOME=/path/to/jdk17/Contents/Home
export DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer
./Scripts/release.sh
```

单独构建 Android Debug：`./gradlew :androidApp:assembleDebug`。单独构建 iOS 未签名 IPA：`./Scripts/build_unsigned_ipa.sh`。构建结果位于 `dist/`。

## 重签与数据兼容

覆盖安装能否被 iOS 识别为同一个 App，取决于签名身份而不是 IPA 文件名。若要保留旧数据和小组件，请固定以下兼容标识，并为 App 与 Widget 配置同一个可用的 App Group：

1. App Bundle ID：`com.raydon.minuteplan`
2. Widget Bundle ID：`com.raydon.minuteplan.widget`
3. App Group：`group.com.raydon.minuteplan`

## 技术与授权

项目使用 Kotlin Multiplatform、Jetpack Compose、Room、WorkManager、SwiftUI、WidgetKit、ActivityKit、EventKit 和 UserNotifications。印章字形采用 [敬峰中山王篆](https://github.com/jeffi369/JFZSKSealScript)，依照 SIL Open Font License 1.1 使用，完整授权文件已随工程提供。产品与架构参考包括 Jerreader 的双端维护方法、[Kadō](https://github.com/scastiel/kado)、[Teymia Habit](https://github.com/amangeldybaiserkeev/TeymiaHabit) 和平台官方文档；本工程不包含参考项目的源代码。
