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
