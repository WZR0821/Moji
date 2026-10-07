#!/bin/zsh
set -euo pipefail

script_dir="${0:A:h}"
cd "${script_dir:h}"

if [[ -z "${JAVA_HOME:-}" && -x /Users/Shared/MojiToolchains/jdk17-ascii/Contents/Home/bin/java ]]; then
  export JAVA_HOME=/Users/Shared/MojiToolchains/jdk17-ascii/Contents/Home
fi
./gradlew :core:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug :androidApp:lintDebug --console=plain

if [[ -z "${DEVELOPER_DIR:-}" && -d "$HOME/Downloads/Xcode.app/Contents/Developer" ]]; then
  export DEVELOPER_DIR="$HOME/Downloads/Xcode.app/Contents/Developer"
fi
if [[ -n "${MOJI_IOS_DESTINATION:-}" ]]; then
  xcodebuild test -project Moji.xcodeproj -scheme Moji \
    -destination "$MOJI_IOS_DESTINATION" \
    -derivedDataPath build/verification-ios CODE_SIGNING_ALLOWED=NO
else
  xcodebuild build -project Moji.xcodeproj -scheme Moji \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath build/verification-ios CODE_SIGNING_ALLOWED=NO
  print "iOS 已编译；未运行模拟器测试。设置 MOJI_IOS_DESTINATION 后重跑可执行完整测试。"
fi
