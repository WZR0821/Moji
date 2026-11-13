# Moji 1.6.2（44）交付验证记录

日期：2026-11-13（JST）。作者：Jerry Wong。

## 结论与范围

本版只改 iOS 的 App Group 衔接，内容见 [发布说明](RELEASE_NOTES_1.6.2.md)。目的是让 SideStore 直接安装后，小组件能读到 App 的数据。

已完成的验证：

- iOS 单元测试 147 项全部通过，其中 13 项是本版新增的。
- 模拟器覆盖升级点验：在旧式存储（与 1.6.1 由 SideStore 直装时相同）里灌入数据，再用一个分组被改名的包覆盖安装。数据完整搬进了改名后的分组，旧数据仍在，界面与升级前一致。
- 真机 IPA 的 App 和小组件签名里都带上了 `group.com.raydon.minuteplan`。

没有在 iPhone 上安装，没有经过真正的 SideStore 重签，也没有验证小组件实际显示数据。这三项要装到手机上确认。Android 代码没有改动，本版没有生成 Android 包，没有提交或上传 GitHub。

## 构建与测试

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| iOS 单元测试 | 147 项通过，0 失败（1.6.1 为 134 项，新增 `MojiAppGroupTests` 13 项） | `build/1.6.2-qa/ios-tests.log`；结果包 `/Users/Shared/MojiBuilds/1.6.2-queue/tests.xcresult` |
| 模拟器覆盖升级点验 | 数据搬迁完整，见下文 | `build/1.6.2-qa/e2e/` |
| iOS 设备 Release | 构建成功，11:00:38–11:03:02 | `build/1.6.2-qa/ios-release.log` |
| 安装包校验 | ZIP CRC 通过；App 和 Widget 都是 1.6.2（44）、arm64、iOS 17.0 起；两者签名里都有 App Group；没有描述文件 | `build/1.6.2-qa/package-check.txt` |

iOS 测试用模拟器 "Moji 1.5.0 QA"（iOS 18.2，`4DB16103`），关闭并行测试，没有克隆出别的模拟器。

测试跑了两次，都是 147 项通过：

- **第一次（10:48–10:52）**：之后在点验第一阶段发现，旧设置里有 `moji.` 开头的键（`moji.pomodoro.liveActivity.suppressedForTermination`），原先只搬 `minuteplan.` 开头的键。于是 10:54 改成两种前缀都搬，并在测试里补了断言。第一次的日志留在 `ios-tests-run1-before-moji-prefix.log`。
- **第二次（10:59–11:00）**：跑的是最终代码，上表的结果以这次为准。

所有源码最后一次改动在 10:54:13。第二阶段构建、第二次测试和 Release 构建都在这之后开始。

`MojiAppGroupTests` 覆盖以下内容：

- **分组选择**：签名里的改名分组优先；有多个时取最短的；只有签名、Info.plist 里没有 `ALTAppGroups`（SideStore 0.7 的情况）也能找到；都拿不到时退回原名；未签名的测试宿主保持原名。
- **签名解析**：从单架构和多架构（fat）的 Mach-O 里读出 entitlements，也能读磁盘上的可执行文件；遇到损坏或截断的输入时返回空，不会崩溃。
- **数据搬迁**：
  - 新分组没有数据时，整份复制，`moji.` 开头的设置也一起复制，其他应用的键不碰。
  - 新分组已有数据时，保留原数据，只补缺少的设置。
  - 搬迁只做一次。
  - 全新安装时没有可搬的数据。

## 模拟器覆盖升级点验

用户同意只用 "Moji 1.5.0 QA" 这一台模拟器。脚本和日志在 `build/1.6.2-qa/e2e/`，原件在 `/Users/Shared/MojiBuilds/1.6.2-queue/e2e/`。

**第一阶段（10:53）旧式存储。**

- 装的是本版代码的模拟器包，没有签名，所以拿不到任何分组，数据落在 App 自己的沙盒里：快照在 `Library/Application Support/MinutePlan/`，设置在 App 本地的 `group.com.raydon.minuteplan.plist`。这与 1.6.1 由 SideStore 直装时的存储位置和格式相同。用的不是 1.6.1 的原始二进制。
- 先卸载再安装，用 `Scripts/fixtures/workflow-1.6.json` 灌入数据：计划 6 条、记录 4 条、倒数日 3 个、备忘 3 条、模板 1 个、周目标 1 个、周复盘 1 条，自定义分类为“生活”。
- 截图 `phaseA-memos.png` 显示 3 条备忘。

**第二阶段（10:54–10:58）改名分组覆盖安装。**

- 用同一份代码构建模拟器包，分组声明换成 `group.com.raydon.minuteplan.QATEAM0001`，模拟 SideStore 改名后的样子。
- Xcode 给模拟器签名时，只把分组写进二进制的 `__entitlements` 段，签名里没有。所以又对小组件、App 依次用 `codesign --entitlements` 重签，把改名后的分组写进签名，Info.plist 不加 `ALTAppGroups`。这和 SideStore 0.7 的做法一样。
- 在第一阶段的数据上覆盖安装，不灌数据直接启动、截图、结束进程，然后检查容器。

**结果**（10:58 核对，详见 `e2e/migration-check.txt`）：

- 改名分组容器里有 `MinutePlan/snapshot.json`，与旧沙盒里的那份逐字节一致。内容条数与第一阶段相同。
- 旧设置 8 个键全部复制到分组的设置里，值完全相同，`moji.` 开头的那个键也在。另外多了标记 `minuteplan.storage.appGroupMigration.v1 = "copied"`。
- 旧沙盒里的快照和设置仍在，没有删除。
- `phaseB-memos.png` 与 `phaseA-memos.png` 一致，3 条备忘都在。

10:59 第二次单元测试把未签名的测试宿主装回模拟器。系统随即删掉了没有 App 再引用的改名分组容器，测试也改写了 App 沙盒里的快照。所以上面的结果是测试前当场抄下的，现在无法复查。

点验中的其他情况：

- 脚本里的 `print "---"` 在 zsh 里报 bad option，只影响日志里的分隔标题。
- 第一阶段列分组时输出 `ls: : No such file`：未签名的包本来就没有分组，属于预期。
- Xcode 16.2 的 `simctl get_app_container <设备> <App> <分组名>` 只打印用法说明，改用 `groups` 列出全部分组后取路径。

## 依据

SideStore 的行为是 2026-11-13 在 GitHub 读源码核实的，没有在真机上跑过：

- 0.6.3：`AltStore/Operations/ResignAppOperation.swift`。
- 0.7.0 develop 分支：`SideStore/Core/Operations/PipelineOperations/` 下的文件。

核实的内容：

- SideStore 读取每个二进制签名里的 `com.apple.security.application-groups`，注册为 `<原名>.<团队 ID>`，扩展也一样处理。
- 0.6.3 和 AltStore 会往 Info.plist 写 `ALTAppGroups`，0.7.0 不写。
- “未声明权限”检查只对 V2 及以上的源生效；本机本地源的 `apps.json` 没有 `version` 字段，按 V1 处理。

1.6.1 的 IPA 两个二进制都没有签名，签名解析读出来是空的。所以小组件拿不到数据这一点，能从包本身确认。

## 交付文件与校验值

- [iOS IPA](../dist/iOS/Moji-v1.6.2-build44-unsigned.ipa)：
  - arm64，包含 Widget，App 和 Widget 都是 1.6.2（44），11,042,421 字节。
  - 两个 bundle 都是 ad-hoc 签名，`codesign --verify --deep --strict` 通过；签名里只有 `group.com.raydon.minuteplan` 这一项 entitlement。
  - 没有 `embedded.mobileprovision`，要由 SideStore 重签后安装。
  - 没有在 iPhone 真机上装过。桌面上有同一份副本（`~/Desktop/Moji-v1.6.2-build44-unsigned.ipa`，校验值相同）。
- [源码快照](../dist/Source/Moji-v1.6.2-build44-source.zip)：当前工作区内容，182 个文件逐个校验过，附 [来源清单](../dist/Source/Moji-v1.6.2-build44-workspace-provenance.json)。快照是在写这份验证记录之前生成的，所以不含本文件。

IPA 是用打包脚本的一份副本打出来的：`build/1.6.2-qa/build_ipa_outside_icloud.sh`。和 `Scripts/build_unsigned_ipa.sh` 相比只改了 4 行：工程根目录写死、构建目录和输出目录改到 `/Users/Shared/MojiBuilds/1.6.2-ios-release`，以及对应的目录校验。这样做是为了不在 iCloud 同步的 Documents 里删除和生成几百 MB 的构建目录，1.6.1 也是在 iCloud 之外构建的。签名、核对、打包的命令与工程里的脚本完全相同。工程里的脚本本身没有原样跑过。

```text
IPA SHA-256
bbd4e7b7fb53c755f3ce51a04a53f444c814d793b69c1b9e44100884a89f4324

源码快照 SHA-256
076b6eb8cff886a6981f80575b178684e7a757d1d622ee49a217af71f4b13d32
```

保留 schema 13、iOS App ID `com.raydon.minuteplan`、Widget ID `com.raydon.minuteplan.widget`，声明的 App Group 仍是 `group.com.raydon.minuteplan`（安装后由 SideStore 改名）。

## 未执行的范围

- 没有在 iPhone 上安装，没有经过真正的 SideStore 重签和分组注册。
- 免费开发者账号能否注册 App Group 没有从源码里确认。
- 没有验证小组件实际读到并显示数据：本机模拟器的小组件库以前是空的，这次没有试。
- SideStore 0.6 的路径（从 Info.plist 的 `ALTAppGroups` 读分组）只有单元测试和解析器自检，没有做模拟器点验。
- “新分组已有数据时不覆盖”只有单元测试覆盖。
- 没有实测从 LiveContainer 导出备份再导入直装版的流程。
- 没有重新做截图对照，界面没有改动。
- 本版没有生成 Android 安装包。
- 构建和测试通过不代表应用没有其他缺陷。
