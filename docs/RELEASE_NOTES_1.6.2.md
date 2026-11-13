# Moji 1.6.2（44）

作者：Jerry Wong。日期：2026-11-13。

本版只改 iOS 安装包与 App Group 的衔接。改完之后，用 SideStore 直接安装时，桌面小组件能读到 App 的数据。界面和功能都没有变化，Android 代码没有改动。

## 为什么要改

- 小组件和 App 是两个进程，只能通过 App Group 共用数据。
- 1.6.1 及以前的 IPA 在构建时关掉了签名，App 和小组件的签名里都没有声明 App Group。SideStore 是按签名里的声明替两者注册分组的，没有声明就不会注册，小组件因此拿不到数据。
- SideStore 重签时会把分组改名为 `group.com.raydon.minuteplan.<团队 ID>`。代码里原先写死的是 `group.com.raydon.minuteplan`，就算注册了也对不上。

## 改动

- **打包时带上分组声明。** `Scripts/build_unsigned_ipa.sh` 打包前先用临时签名（ad-hoc）给小组件、再给 App 签一次，把 `Moji/Moji.entitlements` 和 `MojiWidget/MojiWidget.entitlements` 里的 `group.com.raydon.minuteplan` 写进签名。写进去以后脚本会读回来核对，没写进去就停止打包。这个签名只用来带上声明，安装时 SideStore 会换成自己的签名。
- **运行时读取改过名的分组。** 新增 `Shared/AppGroupResolver.swift`，App 和小组件都编译它。启动时依次查找：
  1. `Info.plist` 里的 `ALTAppGroups`：SideStore 0.6 及以前、AltStore 会写入这个键。
  2. 可执行文件签名里的 `com.apple.security.application-groups`：SideStore 0.7 只写在这里。

  在找到的分组中，系统确实给了访问权限的才算数，其中优先选以原名开头的那个。都没有时（比如装在 LiveContainer 里），沿用原名，行为与 1.6.1 相同。
- **把旧数据搬进新分组。** App 每次启动时检查一次。分组名和原名不同，并且这次搬迁还没做过，就把之前留在 App 自己沙盒里的数据复制到新分组。这些数据是 1.6.1 由 SideStore 直接安装时留下的。
  - 复制的内容包括三个快照文件 `snapshot.json`、`snapshot.previous.json`、`before-restore.mojibackup`，以及所有以 `minuteplan.` 和 `moji.` 开头的设置。
  - 如果新分组里已经有计划、记录等内容，只补它缺少的设置，不覆盖已有数据。
  - 旧数据不删除。
  - 做完写入标记 `minuteplan.storage.appGroupMigration.v1`，之后不再重复。
  - 搬过数据后会刷新小组件。
  - 小组件进程读不到 App 的沙盒，所以搬迁只在 App 里进行。

## 安装方式

- **要由 SideStore 直接安装，不能装进 LiveContainer。** LiveContainer 不支持扩展，小组件在那里没法用。SideStore 问是否保留扩展时，选保留。
- **从 LiveContainer 换成直接安装，数据不会自动带过去**，因为两边是不同的沙盒。请按顺序操作：
  1. 先在 LiveContainer 里的墨记中，进入“回顾 → 数据备份”，点“导出完整备份”。
  2. 直接安装 1.6.2。
  3. 在新装的墨记里，同一页面点“从单个备份文件导入”。
- **如果 1.6.1 本来就是 SideStore 直接安装的**，覆盖安装 1.6.2 后第一次打开 App，就会自动完成上面的搬迁。
- 保留 App ID `com.raydon.minuteplan`、Widget ID `com.raydon.minuteplan.widget`、schema 13，数据格式没有变化。覆盖安装前仍建议先备份。

## Android

Android 代码没有改动。`version.properties` 跟着升到 1.6.2（44），是因为 iOS 打包脚本也从这里读版本号。本版没有生成 Android 安装包，1.6.1（43）的 APK 继续可用。

实际验证范围见 [验证记录](VERIFICATION_1.6.2.md)。
