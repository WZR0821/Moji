# 构建与发布

## 环境

- JDK 17
- Android SDK 36
- 完整 Xcode；若 Xcode 不在 `/Applications`，设置 `DEVELOPER_DIR`

本机示例：

```bash
export JAVA_HOME=/Users/Shared/MojiToolchains/jdk17-ascii/Contents/Home
export DEVELOPER_DIR="$HOME/Downloads/Xcode.app/Contents/Developer"
```

## 常用命令

```bash
./gradlew :core:testAndroidHostTest
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:assembleRelease :androidApp:bundleRelease
./Scripts/build_unsigned_ipa.sh
./Scripts/release.sh
```

`version.properties` 是双端发布版本的唯一人工输入。变更版本时同步更新 Xcode 项目的构建设置；发布脚本会用版本号命名全部产物。

## 签名

`Scripts/create_android_keystore.sh` 首次生成 `/Users/Shared/MojiKeystore/moji-release.jks`，恢复信息保存在同目录的 `RECOVERY.txt`，两者权限均为仅当前用户可读。仓库里的 `keystore.properties` 被忽略。

密钥丢失后无法更新已安装的 Android App，必须离线备份。iOS 产物保持未签名，证书与描述文件不进入仓库。

## 统一验证与发布约束（2026-10-05）

```bash
# Android 单元测试、Debug 构建、静态检查，以及 iOS 模拟器编译
./Scripts/verify.sh

# 指定一个已有模拟器，额外执行 iOS 测试；替换为本机实际 UUID
MOJI_IOS_DESTINATION='platform=iOS Simulator,id=<SIMULATOR_UUID>' ./Scripts/verify.sh
```

发布入口现在要求干净且已提交的工作区，包括新增源码；同版本目标产物已存在时会拒绝覆盖。发布前提升 `version.properties` 中版本／构建号，并同步 Xcode 工程。发布会检查 core 与 Android 测试、Android Release 静态检查，再生成提交来源文件和哈希。iOS 模拟器测试仍需先通过上面的验证入口执行。

`build_unsigned_ipa.sh` 是独立构建工具，仍会更新它自己的 `dist/` 同名 IPA；正式交付使用 `release.sh` 的完整检查流程，不用单独构建脚本绕过来源和版本检查。

## 1.5.0 工作区构建

本次从包含 1.4.1 未提交修改的工作区完成开发，未自动创建 Git 提交。为保留旧产物，分别调用 Gradle／Xcode 到独立构建目录，并输出新的版本化文件；这些是工作区构建，不是 `release.sh` 的提交来源归档。

Android 可添加 `-PmojiBuildRoot=/absolute/isolated/path` 将模块缓存移出 iCloud 同步目录，避免冲突副本导致重复 class。本次目录为 `/Users/Shared/MojiBuilds/1.5.0`；iOS 设备构建目录为 `build/1.5.0-release-ios`。此设置不改变包名、版本或签名身份。
