# Moji

> [!IMPORTANT]
> **本 APP 通过 GPT-5.6-sol 模型开发。**

Moji 是一款黑白朱红水墨风的原生 SwiftUI 效率 App，集计划、备忘、番茄钟、倒数日与纪念日于一体。最低支持 iOS 17，已使用 Xcode 16.2 / iOS 18.2 SDK 验证。

## 主要功能

- **计划**：今日清单、自建月/周/日日历、重复计划、顺延提醒、本地通知及 Apple 日历同步。
- **备忘**：全文搜索、置顶、核对清单，以及随应用数据一起备份和恢复。
- **专注**：番茄钟、真实专注记录、锁屏实时活动、灵动岛与 Apple Watch 智能叠放。
- **时刻**：倒数日和纪念日、重复日期、重点标记、手动或按时间排序。
- **总结**：日、周、月统计，包含完成率、计划与实际用时、热力图和趋势图。
- **小组件**：倒数日、纪念日和计划组件；计划组件支持完成、恢复及快速添加。

应用数据默认保存在设备本地，不包含 Moji 账号、第三方业务 SDK 或业务服务器。用户可将 JSON 快照导出到「文件」App，并指定文件夹进行自动备份。

## v1.3.1 正式版

当前版本：`1.3.1 (build 37)`

- 修正「今日计划」归属：未来计划不再提前出现，已完成计划只进入当天归档。
- 未完成的旧计划可选择是否顺延到今天，默认开启。
- 顺延计划使用朱红方印显示天数，App 与计划小组件保持一致。
- App、小组件和番茄钟共用同一套今日计划判断逻辑。

安装包与完整源码压缩包请前往 [Moji v1.3.1 Release](https://github.com/WZR0821/Moji/releases/tag/v1.3.1) 下载。

> Release 中的 IPA 未签名，需要使用个人或开发者证书重新签名后才能安装到 iPhone。请勿将证书、私钥或描述文件提交到公开仓库。

## 工程与构建

直接使用 Xcode 打开 `Moji.xcodeproj`。构建未签名 IPA：

```bash
DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer \
  ./Scripts/build_unsigned_ipa.sh
```

生成文件位于 `dist/`。该目录默认不会提交到 Git；正式构建包通过 GitHub Release 发布。

## 重签与数据兼容

若要覆盖安装并保留既有数据和小组件，请固定以下兼容标识，并为 App 与 Widget 配置同一个可用的 App Group：

1. App Bundle ID：`com.raydon.minuteplan`
2. Widget Bundle ID：`com.raydon.minuteplan.widget`
3. App Group：`group.com.raydon.minuteplan`

换签后的新沙盒通常无法继承原文件夹授权。首次启动后，请从原备份文件夹恢复数据，并重新选择该文件夹作为自动备份位置。

## 技术与授权

- SwiftUI、WidgetKit、ActivityKit、EventKit、UserNotifications
- [敬峰中山王篆](https://github.com/jeffi369/JFZSKSealScript)，依照 SIL Open Font License 1.1 使用，字体授权文件已随工程提供。
- 产品与架构参考包括 [Kadō](https://github.com/scastiel/kado)、[Teymia Habit](https://github.com/amangeldybaiserkeev/TeymiaHabit) 与 Apple 官方文档。

本工程不包含参考项目的源代码，界面与业务逻辑均为独立实现。
