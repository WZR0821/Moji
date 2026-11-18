# 墨记 Moji

> [!IMPORTANT]
> **本 APP 由 Claude Fable 5.2 及 Opus 5.5 开发完成。**

Moji 是一款黑白朱红水墨风的 iOS / Android 效率 App，把计划、日历、备忘、番茄钟、倒数日和纪念日收进同一套轻量工作流。iOS 使用原生 SwiftUI，Android 使用 Jetpack Compose。应用不依赖账号体系或业务服务器。

| 平台 | 当前版本 | 开发者 | 许可 |
|---|---|---|---|
| iOS 17+ · Android 8.0+ | 1.7.0（build 45） | Jerry Wong | [MIT](LICENSE) |

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

### 小组件

Moji 提供倒数日、纪念日和计划三个桌面小组件。倒数日与纪念日组件最多显示三个可选条目；计划组件显示三条事项，可直接完成、恢复或使用预设快速添加。

## 下载与安装

安装包在 [GitHub Releases](https://github.com/WZR0821/Moji/releases/latest)。

- **Android**：下载 APK 安装，需要 Android 8.0 及以上。Release 使用首次发布时生成并固定保存的 Moji 专用密钥签名，可以直接覆盖安装旧版。
- **iOS**：提供未签名 IPA，需要使用个人或开发者证书（SideStore、AltStore 等工具）重新签名后安装，最低 iOS 17。安装包里带桌面小组件。

### 重签与数据兼容

覆盖安装能否被 iOS 识别为同一个 App，取决于签名身份而不是 IPA 文件名。若要保留旧数据和小组件，请固定以下兼容标识，并为 App 与 Widget 配置同一个可用的 App Group：

1. App Bundle ID：`com.raydon.minuteplan`
2. Widget Bundle ID：`com.raydon.minuteplan.widget`
3. App Group：`group.com.raydon.minuteplan`

## 数据与隐私

业务数据默认保存在设备本地，没有 Moji 账号、第三方业务 SDK 或业务服务器。App 与 Widget 通过兼容旧版本的 App Group 共享本地 JSON 快照。

用户可以手动导出 JSON 备份，也可以在「文件」App 中指定文件夹自动保存最新快照和每日历史备份。重新签名或换机后，可从原文件夹选取备份恢复，并重新授权该文件夹继续自动备份。

iOS 与 Android 使用同一种 Moji 备份格式；1.5.0 起使用 schema 13，兼容读取旧 schema 12 备份，模板、周目标及复盘内容随快照保存。

## 开发与构建

使用 Xcode 打开 `Moji.xcodeproj` 可运行 iOS；Android 工程位于 `androidApp/`。完整双端发布：

```bash
export JAVA_HOME=/path/to/jdk17/Contents/Home
export DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer
./Scripts/release.sh
```

`./Scripts/release.sh` 一条命令生成签名 APK、签名 AAB、未签名 IPA、源码包与 SHA-256 校验文件。单独构建 Android Debug：`./gradlew :androidApp:assembleDebug`。单独构建 iOS 未签名 IPA：`./Scripts/build_unsigned_ipa.sh`。构建结果位于 `dist/`。

项目使用 Kotlin Multiplatform、Jetpack Compose、Room、WorkManager、SwiftUI、WidgetKit、ActivityKit、EventKit 和 UserNotifications。Android 使用 Kotlin Multiplatform 核心中的日期、统计、番茄钟与备份规则；iOS 当前仍使用 Swift 实现，通过格式契约和双端测试维护兼容。

更多说明：[构建与发布](docs/BUILD.md) · [双端架构](docs/ARCHITECTURE.md) · [开发规则](AGENTS.md)

## 项目结构

| 目录 | 用途 |
|---|---|
| `Moji/`、`MojiWidget/` | iOS App，以及桌面小组件和实时活动 |
| `Shared/` | iOS App 与 Widget 共用的数据模型、App Group 数据层和字体 |
| `MojiTests/` | iOS 单元测试 |
| `androidApp/` | Android App：Room、系统日历、通知、后台备份和桌面组件 |
| `core/` | 无 UI 的 Kotlin Multiplatform 模块：数据结构、备份格式、日期归属、倒数计算、统计和番茄周期规则 |
| `ui/` | Android Compose 的主题与水墨组件 |
| `Scripts/`、`Tools/` | 构建、发布和截图对照脚本，图标生成工具 |
| `docs/` | 架构、构建说明、各版本更新说明与验证记录 |

两端版本号保持一致，以 `version.properties` 为准；变更版本时同步更新 Xcode 工程的构建设置。

## 版本记录

- **1.7.0（45）**：以 iOS 为准重新对照双端截图，把 Android 的图标、颜色、字号和间距改得与 iOS 一致；iOS 只升版本号。[更新说明](docs/RELEASE_NOTES_1.7.0.md) · [验证记录](docs/VERIFICATION_1.7.0.md)
- **1.6.2（44）**：只改 iOS 安装包与 App Group 的衔接，让 SideStore 直接安装时桌面小组件能读到 App 的数据。[更新说明](docs/RELEASE_NOTES_1.6.2.md)
- **1.6.1（43）**：修正 Android 计划勾选的两处逻辑。[更新说明](docs/RELEASE_NOTES_1.6.1.md)
- **1.6.0（42）**：简化计划、备忘、时刻和回顾的编辑流程，增加字段直接编辑与未保存保护，收拢低频设置，并重新对照双端深层界面。[更新说明](docs/RELEASE_NOTES_1.6.0.md) · [验证与交付记录](docs/VERIFICATION_1.6.0.md)
- **1.5.1（41）**：双端深层界面修正。[更新说明](docs/RELEASE_NOTES_1.5.1.md)
- **1.5.0（40）**：计划库、模板、批量整理、备忘转计划、周目标与复盘。[更新说明](docs/RELEASE_NOTES_1.5.0.md)
- **1.4.1（39）**：2026-10-05 的一轮数据安全、备份体验与主线程性能维护，只生成了未签名 IPA。[更新说明](docs/RELEASE_NOTES_1.4.1.md) · [开发总结与维护方案](docs/DEVELOPMENT_REVIEW_2026-10-05.md)
- **1.4.0（38）**：首个 iOS / Android 双端版本。新增完整 Android 客户端（计划、月周日历、备忘、倒数与纪念、番茄钟、回顾和设置），支持 Room 本地存储、系统日历、计划提醒、锁屏番茄通知、自动备份和三类桌面组件。[更新说明](docs/RELEASE_NOTES_1.4.0.md)

安装包见 [Releases](https://github.com/WZR0821/Moji/releases)。

## 已知限制

- iOS 只提供未签名 IPA，需要自行重新签名后安装。
- 1.7.0 没有在 iPhone 或 Android 真机上安装，也没有从 1.6.x 覆盖升级的实测。
- 锁屏实时活动、灵动岛和 Apple Watch 智能叠放需要 iOS 18。
- 两端系统字体不同（iOS 苹方，Android 思源黑体），长段文字偶尔会在不同的字处换行。
- iOS 的业务规则仍是 Swift 实现，还没有接入 Kotlin Multiplatform 核心。

## 许可与致谢

Moji 自身的代码以 [MIT 许可证](LICENSE) 开源。印章字形采用 [敬峰中山王篆](https://github.com/jeffi369/JFZSKSealScript)，依照 SIL Open Font License 1.1 使用，完整授权文件已随工程提供；字体文件不在 MIT 范围内，仍按 OFL 1.1 授权。

产品与架构参考包括 Jerreader 的双端维护方法、[Kadō](https://github.com/scastiel/kado)、[Teymia Habit](https://github.com/amangeldybaiserkeev/TeymiaHabit) 和平台官方文档；本工程不包含参考项目的源代码。
