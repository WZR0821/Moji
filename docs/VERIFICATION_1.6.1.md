# Moji 1.6.1（43）交付验证记录

日期：2026-11-12（JST）。作者：Jerry Wong。

## 结论与范围

本版只修正 Android 计划勾选的两处逻辑（见 [发布说明](RELEASE_NOTES_1.6.1.md)），iOS 代码没有改动，两端版本号一起升到 1.6.1（43）。

改动后两端都完整测试过：单元测试合计 210 项通过（Android 38、core 38、iOS 134），Android 模拟器里按真实界面点验两处修正 8 项通过，正式 APK 新装冷启动正常，iOS Release 构建成功。

本轮没有重新做双端截图对照，界面没有改动。视觉方面以 1.6.0 的验证记录为准。没有提交或上传 GitHub。

## 构建与测试

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| Android 单元测试 | LocalInteraction 30 项、PlanQuickChange 8 项通过（1.6.0 为 26+8，新增 4 项覆盖本次修正） | `/Users/Shared/MojiBuilds/1.6.0-toggle-fix/androidApp/test-results/testDebugUnitTest/` |
| core 单元测试 | BackupContract 13 项、MojiPolicies 6 项、PlanWorkflow 19 项通过 | `/Users/Shared/MojiBuilds/1.6.0-toggle-fix/core/test-results/testAndroidHostTest/` |
| Android Lint | 0 errors、44 warnings、3 hints。比 1.6.0 多 1 条 `OldTargetApi`，原因是本机新装了 SDK 37，与代码无关 | `/Users/Shared/MojiBuilds/1.6.0-toggle-fix/androidApp/reports/lint-results-debug.txt` |
| Android Debug | 构建成功 | `build/1.6.1-qa/android-gradle-verify.log` |
| iOS 单元测试 | 134 项通过，0 失败 | `build/1.6.1-qa/ios-tests.log` |
| Android Release | 构建成功 | `/Users/Shared/MojiBuilds/1.6.1-queue/android-release.log` |
| iOS 设备 Release | 构建成功 | `/Users/Shared/MojiBuilds/1.6.1-queue/ios-release.log` |
| 安装包校验 | APK、IPA 的 ZIP CRC 都通过；版本、签名状态与下文一致 | `build/1.6.1-qa/package-check.txt` |

Gradle 这一组（verify.sh 的 Gradle 部分）在 15:09–15:13 一次跑完。其中 androidApp 单元测试读的是构建缓存，输入与 11:53 那次实际运行完全相同，所以结果文件的时间戳是 11:53。

iOS 测试在 15:57 跑，用模拟器 "Moji 1.5.0 QA"（iOS 18.2，`4DB16103`），关闭并行测试，没有克隆出别的模拟器。结果包：`/Users/Shared/MojiBuilds/1.6.1-ios-test/Logs/Test/Test-Moji-2026.11.12_15-57-15-+0900.xcresult`。

单元测试和模拟器点验是在升版本号之前跑的，跑的是 1.6.0（42）版本号的包。升版本号只改了 `version.properties`、`project.yml` 和 `project.pbxproj` 里的版本字段，没有改代码。正式包是升版本号之后构建的，下面的冷启动检查跑的就是 1.6.1（43）的正式 APK。

## 操作验证

Android 在只读、不用快照的 API 36 模拟器 `emulator-5556`（时区 Asia/Tokyo）里装了由修正后源码构建的 debug 包，运行 `Scripts/check_toggle_fix.py`。脚本先灌入 QA 示例数据，按界面实际点击，每一步都直接查 Room 数据库。8 项全部通过：

1. 示例数据里已完成的计划正好有 1 条完成记录。
2. 点“跳过”后，计划变成已跳过，并生成 1 个下一次计划。
3. 在“今日已结束”里点已跳过计划的“−”，计划恢复为未完成，没有变成已完成。
4. 恢复后，跳过时生成、还没开始的那个下一次计划被删掉。
5. 点已完成计划的“✓”，计划恢复为未完成。
6. 完成时生成的那条记录被删掉。
7. 之前中途结束的那条专注记录保留。
8. 强制结束应用再打开，两个计划仍是未完成，那条专注记录仍在。

证据：`build/1.6.1-qa/manual/toggle-results.json`，每一步的截图和界面树也在同一目录（`android-toggle-start`、`skip-ended`、`skip-reopened`、`completed-reopened`、`toggle-relaunch`）。

没有验证日历条目菜单里的“恢复为未完成”和桌面小组件的勾选。这两处调用的是同一个 `togglePlan`，单元测试覆盖了这个函数，但没有在界面上点过。iOS 没做对应的界面点验，iOS 代码本身没改。

正式 APK 在同一个模拟器里先卸载 debug 包再新装（两者签名不同），不带 QA 参数冷启动，进入空数据的计划首页：进程在（pid 2695），该包的 crash 日志 0 行，系统读到的版本是 1.6.1（43）。证据：`manual/release-smoke.json`、`android-release-launch.png`、`.xml`。这是新装启动检查，不是从 1.6.0 覆盖升级的实测。

## 中途停下的几次

这台 Intel Mac 按 `AGENTS.md` 的构建保护规则跑，下面几次是守护脚本或外部原因停下的，都没有算作通过：

- **iOS 第一次（15:39）**：编译已成功，模拟器开机那一刻 1 分钟负载冲到 115.75，超过上限，按规则停了，没跑测试。日志：`build/1.6.1-qa/ios-tests-stopped-first-attempt.log`。用户同意开机阶段放宽后分段再跑：开机峰值 440，WindowServer 最高 35%，等了 245 秒降到 20 以下才开始测试，测试阶段负载峰值 16。过程见 `ios-tests-guard.log`。
- **Android 点验第一次（15:58）**：脚本给 `adb shell am start` 传了空字符串参数，被 shell 吞掉，`am start` 报错，没开始点。已改脚本，不再传这个参数。日志：`android-toggle-first-attempt.log`。
- **Android 点验第二次（16:05）**：另一个项目的会话同时开始跑 iOS 检查，按“同一时间只跑一个构建”停在模拟器开机阶段。记录：`android-toggle-second-attempt-stopped.txt`。
- **第三次（16:14）**：跑到最后一步时，用户从 Apple 菜单重启了机器，系统记录为正常重启，不是崩溃。半途的截图移到了 `build/1.6.1-qa/interrupted-by-restart-1614/`。
- **队列第一次（16:58）**：Android 正式包已构建成功；装好 debug 包后，守护脚本把队列自己调起的 `xcodebuild -find`（查询命令，不是构建）误判成别的构建，停了。修正判断条件后从模拟器这一步接着跑，17:06 通过。

## 交付文件与校验值

- [Android APK](../dist/Android/Moji-v1.6.1-build43-release.apk)：已用发布证书签名（v2），versionName 1.6.1，versionCode 43，minSdk 26，targetSdk 36，14,665,496 字节。
- [iOS IPA](../dist/iOS/Moji-v1.6.1-build43-unsigned.ipa)：arm64，包含 Widget，App 和 Widget 都是 1.6.1（43），10,917,442 字节。**未签名，要重签后才能安装**，没有在 iPhone 真机上装过。
- [源码快照](../dist/Source/Moji-v1.6.1-build43-source.zip)：当前工作区的实际内容，179 个文件逐个校验过，附 [来源清单](../dist/Source/Moji-v1.6.1-build43-workspace-provenance.json)。不含本地签名密钥、被忽略的本地配置、构建目录和历史安装包。

```text
APK SHA-256
d2e66a8f65362fa45afcce90268cc345a1e9e409cca45f3ce47d18aa6de68236

IPA SHA-256
65fa9bfc9c662fbd6ff33d9d4a5ce26bf357f4da1443add5ddc1c1bb19d6bb9f

源码快照 SHA-256
1be6e9bacfc4e9f7e4b720796ae4751605da5d2eeb8dc7f114047ec485a0dea4

Android 发布证书 SHA-256（与 1.6.0 相同）
34ea9040c305632c4e30881b3cc868942a20648cc24293e8a6e7816904433a97
```

保留 schema 13、iOS App ID `com.raydon.minuteplan`、Widget ID `com.raydon.minuteplan.widget`、App Group `group.com.raydon.minuteplan`，以及 Android 包名 `com.raydon.moji.android`。覆盖安装前请先备份。

## 未执行的范围

没有重新做双端截图对照，没有在 iPhone 或 Android 真机上安装，没有实测从 1.6.0 覆盖升级，没有在界面上点过日历菜单和桌面小组件的勾选。专注进行中直接点勾选框，两端的处理仍不一样，本版没有改。构建和测试通过不代表应用没有其他缺陷。
