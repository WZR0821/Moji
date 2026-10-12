# Moji 1.6.0（42）交付验证记录

日期：2026-10-12（JST）。作者：Jerry Wong。

## 结论与范围

本轮完成计划快捷编辑、入口及返回逻辑优化，并针对二级／三级页面调整双端布局。iOS 和 Android Release 均构建成功，安装包版本为 1.6.0（42）。本轮单元测试合计 206 项通过，另完成 66 组双端截图对照、4 条 Android 编辑操作验证及正式 APK 新装启动检查。

**这不是全设备、全状态、零像素差异的验收。** 截图中仍有原生字体、小图标、部分间距／换行、图表刻度及深色细节差异；计划高级设置和计划库等页面的可见高度也未完全一致。安装包可以交付试用，但不能据此宣称已达到全页面 1:1 像素复刻。

功能改动见 [发布说明](RELEASE_NOTES_1.6.0.md)。本轮收尾没有新增功能，没有提交或上传 GitHub。

## 构建与测试

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| iOS 单元测试 | 134 项通过，0 失败 | `build/1.6.0-qa/ios-tests-final.log` |
| Android 单元测试 | LocalInteraction 26 项、PlanQuickChange 8 项通过 | `/Users/Shared/MojiBuilds/1.6.0/androidApp/test-results/testDebugUnitTest/` |
| core 单元测试 | BackupContract 13 项、MojiPolicies 6 项、PlanWorkflow 19 项通过 | `/Users/Shared/MojiBuilds/1.6.0/core/test-results/testAndroidHostTest/` |
| Android Lint | 0 errors、43 warnings、3 hints；不是零告警 | `/Users/Shared/MojiBuilds/1.6.0/androidApp/reports/lint-results-debug.txt` |
| Android Debug／Release | 构建成功 | `build/1.6.0-qa/android-validation-final.log` |
| iOS Simulator Debug／设备 Release | 构建成功 | `build/1.6.0-qa/ios-visual-build-final.log`、`ios-release.log` |
| 安装包校验 | APK／IPA ZIP CRC 均通过；版本、签名状态与下文一致 | 交付包及 SHA-256 |

iOS 134 项测试在最终工具栏／视觉微调前执行，后续 Swift UI 变更已通过 Debug 和 Release 编译，没有再运行整套 iOS 测试。Android 最后一次测试包含专注尚未开始、运行、暂停、完成和自动进入休息时的状态提示断言；这不等于完整后台计时实机验收。

## 视觉检查

对照环境：iPhone 16／iOS 18.2 Simulator 与 Android API 36 Emulator。双端原图均为 1179 × 2556 像素，对应 393 × 852 逻辑尺寸；使用同一份 `Scripts/fixtures/workflow-1.6.json` 示例数据，检查日期为 2026-10-12。截图是 Debug 检查场景，不能替代 Release 真机行为验证。

原图在 `build/1.6.0-qa/ios/` 和 `android/`，共 132 张；`comparisons/` 包含 66 张并排图和 17 张汇总图（浅色 13、深色 4），左为 iOS、右为 Android。最后定向重拍后已按完整矩阵重新生成汇总图，未将局部重拍的残缺汇总图交付。

| 浅色分组 | 状态数 | 实际覆盖 |
| --- | ---: | --- |
| 计划与计划库 | 19 | 首页、展开／已结束、内容及日期／安排／类型／重复／提醒快捷编辑、新增类型、自选星期、新建／编辑／复制、高级设置、计划库／筛选／模板／批量选择 |
| 备忘 | 4 | 列表、已有正文、勾选清单、转换方式 |
| 时刻与专注 | 6 | 倒数日、纪念日、编辑、更多设置、专注、时长编辑 |
| 回顾与记录 | 9 | 总览、周／月总结、记录列表、计时／非计时记录编辑、已填周目标／复盘／下周计划 |
| 设置 | 10 | 索引、默认值、快速预设及编辑、番茄钟、提醒、外观、权限、备份、关于 |
| 日历 | 4 | 月、周、日、跳转日期 |
| 合计 | 52 | 精确路由清单见 `expected-matrix.json` |

深色另检查 14 个状态：计划展开、内容快捷编辑、安排、自选星期、高级设置、备忘清单、日子更多设置、专注时长、记录编辑、周目标、周复盘、设置索引、外观、关于。

最后复看了计划编辑／新建／高级设置、备忘正文／清单及相关深色状态。检查重点为输入内容可读性、长文字换行、字段入口、工具栏及底部操作位置；本轮图中未发现这些已检查区域出现文字重叠或主要操作被遮挡。备忘清单曾误采集首页，已修正浅／深色原图，并增加路由检查。

仍未消除的差异包括：原生字体度量与图标笔画、计划库行高和部分表单段间距、高级设置滚动位置、图表刻度与部分深色细节。状态栏、手势／三键导航、系统日期选择器、权限弹窗、文件选择器及 Apple／系统日历文案属于平台差异。**不能把应用内尚存的布局差异全部归为系统差异。**

## 操作验证

Android 在隔离测试数据中执行了以下 4 条流程，结果均通过：

1. 输入计划标题、保存、强制结束应用并重新启动，已保存内容仍存在。
2. 修改后选择继续编辑，随后放弃草稿，原持久化标题不被覆盖。
3. 在展开详情直接改日期，计划移到次日且保留原分类。
4. 关联计划后进入专注页，不自动开始，也不将待开始状态误报为暂停。

证据：`build/1.6.0-qa/manual/interaction-results.json`、同目录截图／界面树及 `manual-focus-final.log`。这是 Android 实际交互检查；iOS 的对应证据为界面截图和领域逻辑测试，未执行同等覆盖的全流程 UI 自动化。输入截图展示输入后的内容，并未展示完整软键盘，不宣称覆盖所有输入法／键盘避让情况。

正式 APK 另在只读、无快照的隔离 API 36 模拟器 `emulator-5556` 中安装。该隔离会话内旧测试包签名与 Release 不同，移除旧测试包后新装成功；未操作真机或修改原 AVD 的持久数据。未传入 QA 参数，正常冷启动进入空数据计划首页，进程存在，启动结果为 `Status: ok`，检查时 crash 日志为空。证据：`manual/android-release-launch.png`、`.xml` 和 `release-smoke.json`。这是新装启动检查，不是从用户现有版本覆盖升级的实测。

## 交付文件与校验值

- [Android APK](../dist/Android/Moji-v1.6.0-build42-release.apk)：已发布签名，versionName 1.6.0，versionCode 42，minSdk 26，targetSdk 36。
- [iOS IPA](../dist/iOS/Moji-v1.6.0-build42-unsigned.ipa)：arm64，包含 Widget，App 与 Widget 均为 1.6.0（42）；**未签名，需要重签后安装**，没有进行 iPhone 真机安装验证。
- [源码快照](../dist/Source/Moji-v1.6.0-build42-source.zip)：实际工作区内容，不是旧 HEAD；含文件校验清单及 `WORKSPACE_PROVENANCE.json`，排除本地签名密钥、被忽略的本地配置、构建目录和历史安装包。
- [截图与验证证据](../dist/Source/Moji-v1.6.0-build42-visual-qa.zip)：完整截图矩阵、手动操作证据、主要构建／检查日志及带 SHA-256 的证据清单；归档中的文件名将冒号转换为连字符以兼容解压工具。

```text
APK SHA-256
dce63a3a6515f4257e425c5851ed5945918e3969be141729c28876ff81d99f4b

IPA SHA-256
4290612bed6159c75dc7af8995841865aeada1c8e5db6a86168201bb432b0187

Android 发布证书 SHA-256
34ea9040c305632c4e30881b3cc868942a20648cc24293e8a6e7816904433a97
```

保留 schema 13、iOS App ID `com.raydon.minuteplan`、Widget ID `com.raydon.minuteplan.widget`、App Group `group.com.raydon.minuteplan`，以及 Android 包名 `com.raydon.moji.android`。Android 使用原发布证书；iOS 重签需保持可用的签名身份及兼容标识。覆盖安装前应备份数据。

## 未执行的范围

本轮没有进行全机型／大字体矩阵、iPhone 真机安装、双端完整备份恢复与升级链路、系统通知／精确闹钟／后台计时／Widget 的全量实机验收，也没有进行性能或耗电测试。构建与当前测试通过不代表全应用无缺陷；剩余视觉差异及未覆盖范围如上，交付时不以“完全一致”或“全面检测完成”代替这些限制。
